//! Date-setting and votes (kickoff §5.4): the group's appointments are a function of the entry log in server order
//! (kickoff §3 principle 3). Leader-only entries are checked here, the latest response and vote per person win, and
//! every clock judgement reads the server's `received_at`, never the device clock (protocol §4).

use std::collections::{BTreeMap, BTreeSet};

use chrono::{DateTime, NaiveDate, Utc};

use crate::availability::parse_time_option;
use crate::entry::{Choice, Entry, Mode, Stage};
use crate::time::{kst_midnight, parse_day};

/// `options` are the three recommendations followed by member-added options in log order; `votes` hold the latest choice
/// per participant. "Any" adds to nothing, no counted vote means recommendation #1, and a tie goes to the option listed first.
pub fn tally(options: &[String], votes: &[Choice]) -> Option<String> {
    let counts: Vec<usize> =
        options.iter().map(|o| votes.iter().filter(|v| matches!(v, Choice::Opt(x) if x == o)).count()).collect();
    let best = *counts.iter().max()?;
    if best == 0 {
        return options.first().cloned();
    }
    counts.iter().position(|&c| c == best).map(|i| options[i].clone())
}

/// One decrypted entry with what the server attached to it. The author comes from the MLS sender credential, not
/// from the entry, so it cannot be forged (protocol §4).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogEntry {
    pub seq: i64,
    pub author_id: String,
    pub received_at: DateTime<Utc>,
    pub entry: Entry,
}

/// One stage's vote. `votes` holds the latest choice of each current participant; a member who leaves takes their
/// vote with them, so the "everyone voted" check and the tally see the same people.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Poll {
    /// The leader's three recommendations, then added options in log order.
    pub options: Vec<String>,
    pub votes: BTreeMap<String, Choice>,
    pub closed: bool,
    /// The tally, set once the poll closes.
    pub result: Option<String>,
}

impl Poll {
    fn close(&mut self) {
        let votes: Vec<Choice> = self.votes.values().cloned().collect();
        self.closed = true;
        self.result = tally(&self.options, &votes);
    }
}

/// An appointment as the log has shaped it so far. Times are minutes after 00:00 Asia/Seoul on `day`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Appointment {
    pub appt: String,
    pub leader: String,
    pub requester: String,
    pub day: NaiveDate,
    pub mode: Mode,
    pub start: Option<u16>,
    pub end: Option<u16>,
    pub activity: Option<String>,
    pub note: Option<String>,
    /// Copied from the request: only the leader may add poll options.
    pub leader_options: bool,
    /// The latest response per member; `true` is accept.
    pub responses: BTreeMap<String, bool>,
    pub polls: BTreeMap<Stage, Poll>,
    pub cancelled: bool,
    pub requested_at: DateTime<Utc>,
    /// Whether any accept was ever seen: the request stops being tentative and survives its day.
    pub ever_accepted: bool,
}

impl Appointment {
    /// The leader and everyone whose latest response is accept.
    pub fn participants(&self) -> BTreeSet<String> {
        let mut set: BTreeSet<String> =
            self.responses.iter().filter(|(_, accept)| **accept).map(|(user, _)| user.clone()).collect();
        set.insert(self.leader.clone());
        set
    }

    pub fn is_participant(&self, user: &str) -> bool {
        self.leader == user || self.responses.get(user).copied().unwrap_or(false)
    }

    /// Whether an accept was ever written, even if everyone has since said "later".
    pub fn accepted_once(&self) -> bool {
        self.ever_accepted
    }

    /// Only in the requester's calendar, as a draft: no accept yet and not cancelled.
    pub fn is_tentative(&self) -> bool {
        !self.ever_accepted && !self.cancelled
    }

    /// The appointment day has ended in Asia/Seoul by `now`.
    pub fn is_past(&self, now: DateTime<Utc>) -> bool {
        self.day.succ_opt().is_some_and(|next| now >= kst_midnight(next))
    }

    pub fn poll(&self, stage: Stage) -> Option<&Poll> {
        self.polls.get(&stage)
    }

    /// Takes a time result into the appointment's start and end, or an activity result into its title. A member-added
    /// time option in free text leaves the time unset; the leader can still fix it with a request update.
    fn apply_result(&mut self, stage: Stage) {
        let Some(result) = self.polls.get(&stage).and_then(|p| p.result.clone()) else { return };
        match stage {
            Stage::Time => {
                if let Some((start, end)) = parse_time_option(&result) {
                    self.start = Some(start);
                    self.end = Some(end);
                }
            }
            Stage::Activity => self.activity = Some(result),
        }
    }

    fn close_poll(&mut self, stage: Stage) {
        if let Some(poll) = self.polls.get_mut(&stage) {
            if poll.closed {
                return;
            }
            poll.close();
        }
        self.apply_result(stage);
    }

    /// After the participant set or a vote changed: drop votes of people who are no longer participants and close
    /// any open poll in which every current participant has voted.
    fn settle_open_polls(&mut self) {
        let participants = self.participants();
        let stages: Vec<Stage> = self.polls.iter().filter(|(_, p)| !p.closed).map(|(s, _)| *s).collect();
        for stage in stages {
            let poll = self.polls.get_mut(&stage).expect("stage listed from this map");
            poll.votes.retain(|user, _| participants.contains(user));
            if participants.iter().all(|u| poll.votes.contains_key(u)) {
                self.close_poll(stage);
            }
        }
    }

    /// Clock rules at instant `at`: a poll still open at 00:00 of the appointment day closes.
    fn advance(&mut self, at: DateTime<Utc>) {
        if at >= kst_midnight(self.day) {
            let stages: Vec<Stage> = self.polls.iter().filter(|(_, p)| !p.closed).map(|(s, _)| *s).collect();
            for stage in stages {
                self.close_poll(stage);
            }
        }
    }
}

/// Everything the log says about a group's appointments.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct GroupState {
    pub appointments: BTreeMap<String, Appointment>,
}

impl GroupState {
    /// See [`derive`].
    pub fn derive(log: &[LogEntry], now: DateTime<Utc>) -> Self {
        derive(log, now)
    }

    /// Applies every clock rule up to `at`: polls close at 00:00 of their day, and a request nobody ever accepted
    /// disappears once its day has passed. An accepted appointment stays as a past one.
    fn advance(&mut self, at: DateTime<Utc>) {
        for appointment in self.appointments.values_mut() {
            appointment.advance(at);
        }
        self.appointments.retain(|_, a| a.ever_accepted || !a.is_past(at));
    }

    fn apply(&mut self, log_entry: &LogEntry) {
        let author = log_entry.author_id.as_str();
        if let Entry::Request { appt, day, mode, start, end, activity, note, leader_options } = &log_entry.entry {
            // The first request for an id wins; a repeat (a resend, or a collision) changes nothing.
            if self.appointments.contains_key(appt) {
                return;
            }
            let Some(day) = parse_day(day) else { return };
            self.appointments.insert(
                appt.clone(),
                Appointment {
                    appt: appt.clone(),
                    leader: author.to_string(),
                    requester: author.to_string(),
                    day,
                    mode: *mode,
                    start: *start,
                    end: *end,
                    activity: activity.clone(),
                    note: note.clone(),
                    leader_options: *leader_options,
                    responses: BTreeMap::new(),
                    polls: BTreeMap::new(),
                    cancelled: false,
                    requested_at: log_entry.received_at,
                    ever_accepted: false,
                },
            );
            return;
        }

        let Some(appt_id) = appointment_id(&log_entry.entry) else { return };
        let Some(a) = self.appointments.get_mut(appt_id) else { return };
        // A cancelled appointment is over; nothing written afterwards revives or reshapes it.
        if a.cancelled {
            return;
        }
        let is_leader = a.leader == author;
        match &log_entry.entry {
            Entry::RequestUpdate { mode, start, end, activity, note, .. } if is_leader => {
                // `None` leaves a field as it is; an update names only what changes.
                if let Some(mode) = mode {
                    a.mode = *mode;
                }
                if start.is_some() {
                    a.start = *start;
                }
                if end.is_some() {
                    a.end = *end;
                }
                if activity.is_some() {
                    a.activity = activity.clone();
                }
                if note.is_some() {
                    a.note = note.clone();
                }
            }
            Entry::LeadTransfer { to, .. } if is_leader => {
                // The old leader keeps whatever response they wrote; without an accept they are no longer a participant.
                a.leader = to.clone();
                a.settle_open_polls();
            }
            Entry::Response { accept, .. } => {
                a.responses.insert(author.to_string(), *accept);
                a.ever_accepted |= *accept;
                a.settle_open_polls();
            }
            Entry::Poll { stage, options, .. } if is_leader => {
                // Opening or reopening: the leader's device fixed the recommendations, and earlier votes are void.
                a.polls.insert(*stage, Poll { options: options.clone(), ..Poll::default() });
            }
            Entry::PollOption { stage, option, .. } => {
                if a.leader_options && !is_leader {
                    return;
                }
                if let Some(poll) = a.polls.get_mut(stage)
                    && !poll.closed
                    && !poll.options.contains(option)
                {
                    poll.options.push(option.clone());
                }
            }
            Entry::PollVote { stage, choice, .. } => {
                if !a.is_participant(author) {
                    return;
                }
                let Some(poll) = a.polls.get_mut(stage) else { return };
                if poll.closed {
                    return;
                }
                if let Choice::Opt(option) = choice
                    && !poll.options.contains(option)
                {
                    return;
                }
                poll.votes.insert(author.to_string(), choice.clone());
                a.settle_open_polls();
            }
            Entry::PollClose { stage, .. } if is_leader => a.close_poll(*stage),
            Entry::Cancel { .. } if is_leader => a.cancelled = true,
            // Leader-only entries from anyone else, and chat or media entries, change no appointment.
            _ => {}
        }
    }
}

fn appointment_id(entry: &Entry) -> Option<&str> {
    match entry {
        Entry::Request { appt, .. }
        | Entry::RequestUpdate { appt, .. }
        | Entry::LeadTransfer { appt, .. }
        | Entry::Response { appt, .. }
        | Entry::Poll { appt, .. }
        | Entry::PollOption { appt, .. }
        | Entry::PollVote { appt, .. }
        | Entry::PollClose { appt, .. }
        | Entry::Cancel { appt, .. } => Some(appt),
        Entry::Text { .. } | Entry::Media { .. } | Entry::MediaRemove { .. } => None,
    }
}

/// The group's appointments after `log`, which must be in `seq` order, as of `now`, the latest server time the device
/// has seen. Clock rules run before each entry at its `received_at` and once more at `now`, so an entry received
/// after a deadline sees the deadline already applied. The same log and `now` always give the same state.
pub fn derive(log: &[LogEntry], now: DateTime<Utc>) -> GroupState {
    let mut state = GroupState::default();
    for log_entry in log {
        state.advance(log_entry.received_at);
        state.apply(log_entry);
    }
    state.advance(now);
    state
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{Duration, TimeZone};

    const APPT: &str = "a1";
    const DAY: &str = "2026-11-07";

    /// 00:00 Asia/Seoul on the appointment day.
    fn day_midnight() -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 11, 6, 15, 0, 0).unwrap()
    }

    /// Entries a minute apart from 2026-11-01 10:00Z, well before the appointment day.
    struct Log {
        entries: Vec<LogEntry>,
        at: DateTime<Utc>,
    }

    impl Log {
        fn new() -> Self {
            Self { entries: vec![], at: Utc.with_ymd_and_hms(2026, 11, 1, 10, 0, 0).unwrap() }
        }

        fn push(&mut self, author: &str, entry: Entry) -> &mut Self {
            let at = self.at + Duration::minutes(1);
            self.push_at(at, author, entry)
        }

        fn push_at(&mut self, at: DateTime<Utc>, author: &str, entry: Entry) -> &mut Self {
            self.at = at;
            let seq = self.entries.len() as i64 + 1;
            self.entries.push(LogEntry { seq, author_id: author.into(), received_at: at, entry });
            self
        }

        fn now(&self) -> DateTime<Utc> {
            self.at
        }

        fn state(&self) -> GroupState {
            derive(&self.entries, self.at)
        }

        fn appointment(&self) -> Appointment {
            self.state().appointments.get(APPT).cloned().expect("appointment in state")
        }
    }

    fn request(leader_options: bool) -> Entry {
        Entry::Request {
            appt: APPT.into(),
            day: DAY.into(),
            mode: Mode::Vote,
            start: None,
            end: None,
            activity: None,
            note: Some("저녁".into()),
            leader_options,
        }
    }

    fn response(accept: bool) -> Entry {
        Entry::Response { appt: APPT.into(), accept }
    }

    fn poll(stage: Stage, options: &[&str]) -> Entry {
        Entry::Poll { appt: APPT.into(), stage, options: options.iter().map(|o| o.to_string()).collect() }
    }

    fn option(stage: Stage, option: &str) -> Entry {
        Entry::PollOption { appt: APPT.into(), stage, option: option.into() }
    }

    fn vote(stage: Stage, option: &str) -> Entry {
        Entry::PollVote { appt: APPT.into(), stage, choice: Choice::Opt(option.into()) }
    }

    fn vote_any(stage: Stage) -> Entry {
        Entry::PollVote { appt: APPT.into(), stage, choice: Choice::Any }
    }

    fn text(body: &str) -> Entry {
        Entry::Text { id: body.into(), body: body.into(), reply_to: None }
    }

    fn names(set: &BTreeSet<String>) -> Vec<&str> {
        set.iter().map(String::as_str).collect()
    }

    const TIMES: [&str; 3] = ["19:00-21:00", "13:00-15:00", "09:00-11:00"];

    /// A requested appointment with bomi accepted and the time poll open.
    fn with_time_poll() -> Log {
        let mut log = Log::new();
        log.push("ara", request(false)).push("bomi", response(true)).push("ara", poll(Stage::Time, &TIMES));
        log
    }

    #[test]
    fn adds_the_appointment_to_the_requesters_calendar_as_tentative_on_request() {
        let mut log = Log::new();
        log.push("ara", request(false));
        let a = log.appointment();
        assert_eq!((a.leader.as_str(), a.requester.as_str()), ("ara", "ara"));
        assert_eq!(a.day, NaiveDate::from_ymd_opt(2026, 11, 7).unwrap());
        assert!(a.is_tentative(), "the requester sees a draft until the first accept");
        assert!(!a.accepted_once());
        assert_eq!(names(&a.participants()), vec!["ara"]);
        assert_eq!(a.requested_at, log.now());
        assert_eq!((a.mode, a.start, a.end, a.note.as_deref()), (Mode::Vote, None, None, Some("저녁")));

        // A second request with the same id, even from someone else, changes nothing; a bad day is no request.
        log.push("bomi", request(true));
        assert_eq!(log.appointment(), a);
        let Entry::Request { mode, start, end, activity, note, leader_options, .. } = request(false) else {
            unreachable!()
        };
        let bad_day = Entry::Request {
            appt: "a2".into(),
            day: "2026-13-40".into(),
            mode,
            start,
            end,
            activity,
            note,
            leader_options,
        };
        log.push("bomi", bad_day);
        assert!(!log.state().appointments.contains_key("a2"));
    }

    #[test]
    fn adds_the_appointment_to_a_members_calendar_on_accept_and_removes_it_on_later() {
        let mut log = Log::new();
        log.push("ara", request(false)).push("bomi", response(true));
        let a = log.appointment();
        assert_eq!(names(&a.participants()), vec!["ara", "bomi"]);
        assert!(!a.is_tentative(), "the first accept turns the draft into an appointment");

        log.push("bomi", response(false));
        let a = log.appointment();
        assert_eq!(names(&a.participants()), vec!["ara"], "the latest response per member wins");
        assert!(a.accepted_once() && !a.is_tentative(), "an accept once seen keeps the appointment confirmed");

        // "Later" then accept is a late join.
        log.push("bomi", response(true));
        assert_eq!(names(&log.appointment().participants()), vec!["ara", "bomi"]);
    }

    #[test]
    fn ignores_request_updates_polls_and_cancels_not_written_by_the_current_leader() {
        let mut log = Log::new();
        log.push("ara", request(false)).push("bomi", response(true)).push("ara", poll(Stage::Time, &TIMES));
        let before = log.state();
        log.push(
            "bomi",
            Entry::RequestUpdate {
                appt: APPT.into(),
                mode: Some(Mode::Dictator),
                start: Some(600),
                end: Some(720),
                activity: Some("볼링".into()),
                note: None,
            },
        )
        .push("bomi", poll(Stage::Activity, &["영화", "볼링", "노래방"]))
        .push("bomi", poll(Stage::Time, &["10:00-12:00"]))
        .push("bomi", Entry::PollClose { appt: APPT.into(), stage: Stage::Time })
        .push("bomi", Entry::LeadTransfer { appt: APPT.into(), to: "bomi".into() })
        .push("bomi", Entry::Cancel { appt: APPT.into() })
        .push("chan", Entry::Cancel { appt: APPT.into() });
        assert_eq!(log.state(), before);

        // The same entries from the leader take effect.
        log.push(
            "ara",
            Entry::RequestUpdate {
                appt: APPT.into(),
                mode: Some(Mode::Dictator),
                start: Some(600),
                end: Some(720),
                activity: Some("볼링".into()),
                note: None,
            },
        );
        let a = log.appointment();
        assert_eq!(
            (a.mode, a.start, a.end, a.activity.as_deref()),
            (Mode::Dictator, Some(600), Some(720), Some("볼링"))
        );
        assert_eq!(a.note.as_deref(), Some("저녁"), "fields left out of the update stay");
        log.push("ara", Entry::Cancel { appt: APPT.into() });
        let a = log.appointment();
        assert!(a.cancelled && !a.is_tentative());
        assert!(log.state().appointments.contains_key(APPT), "a cancelled appointment stays so calendars drop it");
    }

    #[test]
    fn moves_the_leader_role_on_lead_transfer() {
        let mut log = Log::new();
        log.push("ara", request(false))
            .push("bomi", response(true))
            .push("ara", Entry::LeadTransfer { appt: APPT.into(), to: "bomi".into() });
        let a = log.appointment();
        assert_eq!((a.leader.as_str(), a.requester.as_str()), ("bomi", "ara"));
        // ara never accepted, so without the leader role she is no longer a participant.
        assert_eq!(names(&a.participants()), vec!["bomi"]);

        // Leader-only entries now belong to bomi.
        log.push("ara", poll(Stage::Time, &TIMES));
        assert!(log.appointment().polls.is_empty());
        log.push("bomi", poll(Stage::Time, &TIMES));
        assert!(log.appointment().polls.contains_key(&Stage::Time));

        // A former leader who accepts is an ordinary participant again.
        log.push("ara", response(true));
        assert_eq!(names(&log.appointment().participants()), vec!["ara", "bomi"]);
    }

    #[test]
    fn removes_an_unaccepted_request_once_its_day_has_passed_in_asia_seoul() {
        let mut log = Log::new();
        log.push("ara", request(false));
        let end_of_day = Utc.with_ymd_and_hms(2026, 11, 7, 15, 0, 0).unwrap(); // 00:00 KST on 11-08
        let just_before = end_of_day - Duration::seconds(1);
        assert!(derive(&log.entries, just_before).appointments.contains_key(APPT), "23:59:59 KST is still the day");
        assert!(derive(&log.entries, end_of_day).appointments.is_empty(), "the draft vanishes everywhere");

        // An accept received after the day is over finds no appointment to accept.
        let mut late = Log::new();
        late.push("ara", request(false)).push_at(end_of_day, "bomi", response(true));
        assert!(late.state().appointments.is_empty());

        // An accepted appointment stays as a past one.
        let mut accepted = Log::new();
        accepted.push("ara", request(false)).push("bomi", response(true));
        let state = derive(&accepted.entries, end_of_day);
        let a = state.appointments.get(APPT).expect("kept");
        assert!(a.is_past(end_of_day));
        assert!(!a.is_past(just_before));
    }

    #[test]
    fn closes_a_poll_when_every_current_participant_has_voted() {
        let mut log = with_time_poll();
        log.push("ara", vote(Stage::Time, "13:00-15:00"));
        let p = log.appointment().poll(Stage::Time).unwrap().clone();
        assert!(!p.closed, "bomi has not voted yet");

        // A non-participant's vote and a vote for an unknown option do not count.
        log.push("chan", vote(Stage::Time, "19:00-21:00")).push("bomi", vote(Stage::Time, "22:00-23:00"));
        let p = log.appointment().poll(Stage::Time).unwrap().clone();
        assert!(!p.closed && p.votes.len() == 1);

        log.push("bomi", vote(Stage::Time, "13:00-15:00"));
        let a = log.appointment();
        let p = a.poll(Stage::Time).unwrap();
        assert!(p.closed);
        assert_eq!(p.result.as_deref(), Some("13:00-15:00"));
        assert_eq!((a.start, a.end), (Some(13 * 60), Some(15 * 60)), "the time result becomes the appointment time");

        // Nothing changes a closed poll: no vote, no new option.
        log.push("ara", vote(Stage::Time, "19:00-21:00")).push("ara", option(Stage::Time, "22:00-23:00"));
        assert_eq!(log.appointment().poll(Stage::Time), Some(p));
    }

    #[test]
    fn closes_a_poll_when_the_last_participant_without_a_vote_leaves() {
        let mut log = with_time_poll();
        log.push("ara", vote(Stage::Time, "09:00-11:00")).push("bomi", response(false));
        let p = log.appointment().poll(Stage::Time).unwrap().clone();
        assert!(p.closed, "everyone still taking part has voted");
        assert_eq!(p.result.as_deref(), Some("09:00-11:00"));
    }

    #[test]
    fn closes_a_poll_at_00_00_of_the_appointment_day_by_server_receive_time() {
        // By a later entry: a chat message received at midnight closes the poll before it is applied.
        let mut log = with_time_poll();
        log.push("bomi", vote(Stage::Time, "09:00-11:00"))
            .push_at(day_midnight(), "chan", text("다들 내일 봐요"))
            .push_at(day_midnight() + Duration::seconds(1), "ara", vote(Stage::Time, "19:00-21:00"));
        let a = log.appointment();
        let p = a.poll(Stage::Time).unwrap();
        assert!(p.closed);
        assert_eq!(p.result.as_deref(), Some("09:00-11:00"), "ara's vote came after the deadline");
        assert_eq!((a.start, a.end), (Some(9 * 60), Some(11 * 60)));

        // By `now` alone, with no entry after the deadline.
        let mut quiet = with_time_poll();
        quiet.push("bomi", vote(Stage::Time, "09:00-11:00"));
        let open = derive(&quiet.entries, day_midnight() - Duration::seconds(1));
        assert!(!open.appointments[APPT].poll(Stage::Time).unwrap().closed);
        let closed = derive(&quiet.entries, day_midnight());
        assert!(closed.appointments[APPT].poll(Stage::Time).unwrap().closed);

        // The leader may close earlier.
        let mut early = with_time_poll();
        early
            .push("bomi", vote(Stage::Time, "09:00-11:00"))
            .push("ara", Entry::PollClose { appt: APPT.into(), stage: Stage::Time });
        assert_eq!(early.appointment().poll(Stage::Time).unwrap().result.as_deref(), Some("09:00-11:00"));
    }

    #[test]
    fn picks_the_first_recommendation_when_every_vote_is_any() {
        let mut log = with_time_poll();
        log.push("ara", vote_any(Stage::Time)).push("bomi", vote_any(Stage::Time));
        let a = log.appointment();
        assert_eq!(a.poll(Stage::Time).unwrap().result.as_deref(), Some("19:00-21:00"));
        assert_eq!((a.start, a.end), (Some(19 * 60), Some(21 * 60)));
    }

    #[test]
    fn resolves_a_tie_by_list_order_recommendations_first_then_added_options_in_log_order() {
        let mut log = Log::new();
        log.push("ara", request(false))
            .push("bomi", response(true))
            .push("chan", response(true))
            .push("ara", poll(Stage::Activity, &["식사", "카페", "영화"]))
            .push("chan", option(Stage::Activity, "볼링"))
            .push("bomi", option(Stage::Activity, "노래방"))
            .push("bomi", option(Stage::Activity, "볼링")); // already present: not duplicated
        let p = log.appointment().poll(Stage::Activity).unwrap().clone();
        assert_eq!(p.options, vec!["식사", "카페", "영화", "볼링", "노래방"]);

        // 노래방 and 볼링 tie at one vote each; 볼링 was added first.
        log.push("ara", vote(Stage::Activity, "노래방"))
            .push("bomi", vote(Stage::Activity, "볼링"))
            .push("chan", vote_any(Stage::Activity));
        let a = log.appointment();
        assert_eq!(a.poll(Stage::Activity).unwrap().result.as_deref(), Some("볼링"));
        assert_eq!(a.activity.as_deref(), Some("볼링"), "the activity result becomes the title");

        // A recommendation beats an added option on a tie, and a changed vote replaces the earlier one.
        let mut log = Log::new();
        log.push("ara", request(false))
            .push("bomi", response(true))
            .push("ara", poll(Stage::Activity, &["식사", "카페", "영화"]))
            .push("bomi", option(Stage::Activity, "볼링"))
            .push("ara", vote(Stage::Activity, "식사"))
            .push("ara", vote(Stage::Activity, "영화"))
            .push("bomi", vote(Stage::Activity, "볼링"));
        assert_eq!(log.appointment().poll(Stage::Activity).unwrap().result.as_deref(), Some("영화"));
    }

    #[test]
    fn resets_votes_when_the_leader_reopens_a_poll() {
        let mut log = with_time_poll();
        log.push("ara", vote(Stage::Time, "13:00-15:00")).push("bomi", option(Stage::Time, "22:00-23:00"));
        let p = log.appointment().poll(Stage::Time).unwrap().clone();
        assert_eq!((p.votes.len(), p.options.len()), (1, 4));

        log.push("ara", poll(Stage::Time, &["18:00-20:00", "12:00-14:00", "10:00-12:00"]));
        let p = log.appointment().poll(Stage::Time).unwrap().clone();
        assert_eq!(
            p,
            Poll { options: vec!["18:00-20:00".into(), "12:00-14:00".into(), "10:00-12:00".into()], ..Poll::default() }
        );

        // Reopening a closed poll also works, and the earlier result stops applying only when the new one closes.
        log.push("ara", vote(Stage::Time, "12:00-14:00")).push("bomi", vote(Stage::Time, "12:00-14:00"));
        assert_eq!(log.appointment().start, Some(12 * 60));
        log.push("ara", poll(Stage::Time, &["09:00-11:00"]));
        let a = log.appointment();
        assert!(!a.poll(Stage::Time).unwrap().closed && a.poll(Stage::Time).unwrap().votes.is_empty());
        assert_eq!(a.start, Some(12 * 60), "the appointment keeps its time until the new poll decides");
    }

    #[test]
    fn ignores_options_added_by_non_leaders_when_the_request_copied_leader_options() {
        let mut log = Log::new();
        log.push("ara", request(true))
            .push("bomi", response(true))
            .push("ara", poll(Stage::Activity, &["식사", "카페", "영화"]))
            .push("bomi", option(Stage::Activity, "볼링"))
            .push("chan", option(Stage::Activity, "노래방"))
            .push("ara", option(Stage::Activity, "방탈출"));
        assert_eq!(log.appointment().poll(Stage::Activity).unwrap().options, vec!["식사", "카페", "영화", "방탈출"]);

        // Without the flag, any member adds, taking part or not.
        let mut open = Log::new();
        open.push("ara", request(false))
            .push("ara", poll(Stage::Activity, &["식사", "카페", "영화"]))
            .push("chan", option(Stage::Activity, "노래방"));
        assert_eq!(open.appointment().poll(Stage::Activity).unwrap().options, vec!["식사", "카페", "영화", "노래방"]);
    }

    #[test]
    fn derives_the_same_state_from_the_same_log_on_two_independent_runs() {
        let mut log = Log::new();
        log.push("ara", request(false))
            .push("bomi", response(true))
            .push("chan", response(true))
            .push("ara", poll(Stage::Time, &TIMES))
            .push("chan", option(Stage::Time, "22:00-23:00"))
            .push("bomi", vote(Stage::Time, "22:00-23:00"))
            .push("chan", response(false))
            .push("ara", poll(Stage::Activity, &["식사", "카페", "영화"]))
            .push("bomi", vote(Stage::Activity, "카페"))
            .push("ara", Entry::LeadTransfer { appt: APPT.into(), to: "bomi".into() })
            .push("ara", text("잘 부탁해"))
            .push("bomi", Entry::PollClose { appt: APPT.into(), stage: Stage::Activity })
            .push("ara", response(true))
            .push_at(day_midnight() + Duration::hours(1), "ara", vote(Stage::Time, "19:00-21:00"));
        let first = derive(&log.entries, log.now());
        let second = derive(&log.entries, log.now());
        assert_eq!(first, second);
        let a = &first.appointments[APPT];
        assert_eq!(a.leader, "bomi");
        assert_eq!(a.activity.as_deref(), Some("카페"));
        assert!(a.poll(Stage::Time).unwrap().closed, "closed at midnight before ara's late vote");
    }

    // The tally examples from kickoff §5.4, on the function alone.
    fn opts(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    fn opt(s: &str) -> Choice {
        Choice::Opt(s.into())
    }

    #[test]
    fn tally_picks_the_option_with_the_most_votes() {
        let o = opts(&["A", "B", "C"]);
        assert_eq!(tally(&o, &[opt("B"), opt("B"), opt("A")]).as_deref(), Some("B"));
    }

    #[test]
    fn tally_adds_any_to_no_option() {
        let o = opts(&["A", "B", "C"]);
        assert_eq!(tally(&o, &[Choice::Any, Choice::Any, opt("C")]).as_deref(), Some("C"));
    }

    #[test]
    fn tally_falls_back_to_the_first_recommendation_when_no_vote_counts() {
        let o = opts(&["A", "B", "C"]);
        assert_eq!(tally(&o, &[]).as_deref(), Some("A"));
        assert_eq!(tally(&o, &[Choice::Any, Choice::Any]).as_deref(), Some("A"));
        assert_eq!(tally(&o, &[opt("Z")]).as_deref(), Some("A"), "a vote for an unknown option counts for nothing");
        assert_eq!(tally(&[], &[Choice::Any]), None, "no options, no result");
    }

    #[test]
    fn tally_breaks_a_tie_by_list_order() {
        let o = opts(&["A", "B", "C", "added1", "added2"]);
        assert_eq!(tally(&o, &[opt("added2"), opt("added1")]).as_deref(), Some("added1"));
        assert_eq!(tally(&o, &[opt("added1"), opt("C")]).as_deref(), Some("C"));
    }
}
