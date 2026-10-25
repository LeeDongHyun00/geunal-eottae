//! The two properties rust-core §9 asks for: the same log always derives the same state, and the tally does not depend
//! on the order votes arrive in.

use chrono::{DateTime, Duration, TimeZone, Utc};
use geunal_domain::appointments::{LogEntry, derive, tally};
use geunal_domain::entry::{Choice, Entry, Mode, Stage};
use proptest::prelude::*;

const USERS: [&str; 3] = ["ara", "bomi", "chan"];
const APPTS: [&str; 2] = ["a1", "a2"];
const OPTIONS: [&str; 5] = ["19:00-21:00", "13:00-15:00", "09:00-11:00", "식사", "볼링"];

fn user() -> impl Strategy<Value = String> {
    prop::sample::select(USERS.to_vec()).prop_map(str::to_string)
}

fn appt() -> impl Strategy<Value = String> {
    prop::sample::select(APPTS.to_vec()).prop_map(str::to_string)
}

fn option() -> impl Strategy<Value = String> {
    prop::sample::select(OPTIONS.to_vec()).prop_map(str::to_string)
}

fn stage() -> impl Strategy<Value = Stage> {
    prop_oneof![Just(Stage::Time), Just(Stage::Activity)]
}

fn choice() -> impl Strategy<Value = Choice> {
    prop_oneof![3 => option().prop_map(Choice::Opt), 1 => Just(Choice::Any)]
}

// Days around the log's time span, plus one that does not parse, so expiry and midnight closing both get exercised.
fn day() -> impl Strategy<Value = String> {
    prop::sample::select(vec!["2026-11-01", "2026-11-02", "2026-11-03", "2026-11-05", "2026-11-31"])
        .prop_map(str::to_string)
}

fn entry() -> impl Strategy<Value = Entry> {
    prop_oneof![
        (appt(), day(), any::<bool>(), any::<bool>()).prop_map(|(appt, day, dictator, leader_options)| {
            Entry::Request {
                appt,
                day,
                mode: if dictator { Mode::Dictator } else { Mode::Vote },
                start: None,
                end: None,
                activity: None,
                note: None,
                leader_options,
            }
        }),
        (appt(), prop::option::of(0u16..1440), prop::option::of(option())).prop_map(|(appt, start, activity)| {
            Entry::RequestUpdate { appt, mode: None, start, end: start.map(|s| s + 60), activity, note: None }
        }),
        (appt(), user()).prop_map(|(appt, to)| Entry::LeadTransfer { appt, to }),
        (appt(), any::<bool>()).prop_map(|(appt, accept)| Entry::Response { appt, accept }),
        (appt(), stage(), prop::collection::vec(option(), 0..4)).prop_map(|(appt, stage, options)| Entry::Poll {
            appt,
            stage,
            options
        }),
        (appt(), stage(), option()).prop_map(|(appt, stage, option)| Entry::PollOption { appt, stage, option }),
        (appt(), stage(), choice()).prop_map(|(appt, stage, choice)| Entry::PollVote { appt, stage, choice }),
        (appt(), stage()).prop_map(|(appt, stage)| Entry::PollClose { appt, stage }),
        appt().prop_map(|appt| Entry::Cancel { appt }),
        Just(Entry::Text { id: "t".into(), body: "안녕".into(), reply_to: None }),
    ]
}

/// A log in server order: `received_at` advances by a random number of hours per entry over about a week.
fn log() -> impl Strategy<Value = Vec<LogEntry>> {
    prop::collection::vec((user(), entry(), 0i64..12), 0..40).prop_map(|rows| {
        let mut at = Utc.with_ymd_and_hms(2026, 10, 31, 12, 0, 0).unwrap();
        rows.into_iter()
            .enumerate()
            .map(|(i, (author_id, entry, hours))| {
                at += Duration::hours(hours);
                LogEntry { seq: i as i64 + 1, author_id, received_at: at, entry }
            })
            .collect()
    })
}

fn now() -> impl Strategy<Value = DateTime<Utc>> {
    (0i64..24 * 10).prop_map(|h| Utc.with_ymd_and_hms(2026, 10, 31, 12, 0, 0).unwrap() + Duration::hours(h))
}

proptest! {
    #[test]
    fn the_same_log_derives_the_same_state_on_two_independent_runs(log in log(), now in now()) {
        let first = derive(&log, now);
        let second = derive(&log, now);
        prop_assert_eq!(first, second);
    }

    #[test]
    fn derived_state_only_ever_names_leaders_and_participants_from_the_log(log in log(), now in now()) {
        // A sanity check on the rules themselves: every open poll's votes come from current participants, and every
        // closed poll carries the tally of its own votes.
        let state = derive(&log, now);
        for a in state.appointments.values() {
            let participants = a.participants();
            for poll in a.polls.values() {
                if !poll.closed {
                    prop_assert!(poll.votes.keys().all(|u| participants.contains(u)));
                    prop_assert!(poll.result.is_none());
                } else {
                    let votes: Vec<Choice> = poll.votes.values().cloned().collect();
                    prop_assert_eq!(&poll.result, &tally(&poll.options, &votes));
                }
            }
        }
    }

    #[test]
    fn tally_does_not_depend_on_the_order_of_votes(
        options in prop::collection::vec(option(), 0..5),
        (votes, shuffled) in prop::collection::vec(choice(), 0..8).prop_flat_map(|v| (Just(v.clone()), Just(v).prop_shuffle())),
    ) {
        prop_assert_eq!(tally(&options, &votes), tally(&options, &shuffled));
    }
}
