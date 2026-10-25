//! The M0 gate (kickoff §8): three headless clients make a group through the local API and gateway, trade entries,
//! one is kicked and rejoins, and every client derives the same appointment state. The clients are the same `Core`
//! the apps call.
//!
//! The API shapes the core assumes are the ones in geunal-sync/src/api.rs. Until the API serves guest sign-in, this
//! test reports itself as skipped; `GEUNAL_E2E_REQUIRE=1` (set once the backend M0 lands) turns that into a failure.

use std::sync::Arc;
use std::time::{Duration, Instant};

use geunal_e2e::{MemorySecrets, Quiet, api_url, gateway_url};
use geunal_ffi::{Core, CoreError, Mode, RealtimeEvent, Session, Stage};
use pollster::block_on;

struct Client {
    core: Arc<Core>,
    session: Session,
    _dir: tempfile::TempDir,
}

impl Client {
    fn user(&self) -> &str {
        &self.session.user_id
    }

    fn sync(&self) {
        block_on(self.core.clone().sync()).unwrap();
    }

    fn digest(&self, group: &str) -> String {
        self.core.group_state(group.to_owned()).unwrap().digest
    }

    fn texts(&self, group: &str) -> Vec<String> {
        self.core.messages(group.to_owned()).unwrap().into_iter().map(|m| m.body).collect()
    }

    /// Reads gateway events until `pred` accepts one or the deadline passes.
    fn wait_for_event(&self, timeout: Duration, pred: impl Fn(&RealtimeEvent) -> bool) -> Option<RealtimeEvent> {
        let deadline = Instant::now() + timeout;
        while Instant::now() < deadline {
            let remaining = deadline.saturating_duration_since(Instant::now()).as_millis() as u64;
            match block_on(self.core.clone().next_realtime_event(remaining.max(1))).unwrap() {
                Some(event) if pred(&event) => return Some(event),
                Some(_) => continue,
                None => return None,
            }
        }
        None
    }
}

/// Signs a fresh device in as a guest. `None` when the API has no guest sign-in yet.
fn client(name: &str) -> Option<Client> {
    let dir = tempfile::tempdir().unwrap();
    let core = Core::open(
        dir.path().to_str().unwrap().to_owned(),
        api_url(),
        Arc::new(Quiet),
        Arc::new(MemorySecrets::default()),
    )
    .unwrap();
    match block_on(core.clone().sign_in_guest(name.to_owned(), "android".to_owned())) {
        Ok(session) => Some(Client { core, session, _dir: dir }),
        Err(CoreError::Api { status, code, .. }) if status == 401 || status == 404 || status == 405 => {
            skip(&format!("the API answered {status} {code} to POST /auth/guest: no M0 auth stub yet"));
            None
        }
        Err(e) => panic!("sign-in failed: {e}"),
    }
}

fn skip(reason: &str) {
    if std::env::var_os("GEUNAL_E2E_REQUIRE").is_some() {
        panic!("M0 gate cannot run: {reason}");
    }
    eprintln!("SKIPPED (set GEUNAL_E2E_REQUIRE=1 to fail instead): {reason}");
}

#[test]
fn three_clients_make_a_group_trade_entries_survive_a_kick_and_a_rejoin_and_agree() {
    let Some(a) = client("a") else { return };
    let b = client("b").expect("the API signed one guest in, so it signs the others in too");
    let c = client("c").expect("the API signed one guest in, so it signs the others in too");

    // Group: A creates, B and C ask to join, A adds them, B and C pick up their Welcomes.
    let group = block_on(a.core.clone().create_group(Some("셋".into()))).unwrap();
    let code = block_on(a.core.clone().create_invite(group.clone())).unwrap();
    block_on(b.core.clone().join_invite(code.clone())).unwrap();
    block_on(c.core.clone().join_invite(code.clone())).unwrap();
    a.sync();
    b.sync();
    c.sync();
    let mut users = vec![a.user().to_owned(), b.user().to_owned(), c.user().to_owned()];
    users.sort();
    assert_eq!(a.core.members(group.clone()).unwrap(), users);
    assert_eq!(b.core.members(group.clone()).unwrap(), users);
    assert_eq!(c.core.members(group.clone()).unwrap(), users);

    // Realtime: B listens on its device topic and gets A's message as an `entry` event (gateway.md §10).
    block_on(b.core.clone().connect_realtime(gateway_url())).unwrap();
    assert!(b.wait_for_event(Duration::from_secs(10), |e| matches!(e, RealtimeEvent::Joined)).is_some(), "joined");
    block_on(a.core.clone().send_text(group.clone(), "실시간으로 가나요?".into())).unwrap();
    let event = b.wait_for_event(Duration::from_secs(10), |e| matches!(e, RealtimeEvent::Entry { .. }));
    assert!(matches!(&event, Some(RealtimeEvent::Entry { group_id, .. }) if *group_id == group), "{event:?}");
    assert_eq!(b.texts(&group), vec!["실시간으로 가나요?"]);

    // Entries: a request, two accepts, a time poll, two votes, a chat line.
    let appt = block_on(a.core.clone().request_date(
        group.clone(),
        "2026-11-07".into(),
        Mode::Vote,
        None,
        None,
        None,
        Some("저녁 어때?".into()),
        false,
    ))
    .unwrap();
    b.sync();
    c.sync();
    block_on(b.core.clone().respond(group.clone(), appt.clone(), true)).unwrap();
    block_on(c.core.clone().respond(group.clone(), appt.clone(), true)).unwrap();
    a.sync();
    let options = vec!["19:00-21:00".to_owned(), "13:00-15:00".to_owned(), "09:00-11:00".to_owned()];
    block_on(a.core.clone().open_poll(group.clone(), appt.clone(), Stage::Time, options)).unwrap();
    b.sync();
    c.sync();
    block_on(b.core.clone().vote(group.clone(), appt.clone(), Stage::Time, Some("13:00-15:00".into()))).unwrap();
    block_on(c.core.clone().vote(group.clone(), appt.clone(), Stage::Time, None)).unwrap();
    block_on(c.core.clone().send_text(group.clone(), "C의 채팅".into())).unwrap();

    // Kick: A removes C; C hears it through the log and loses the group's keys; A and B carry on.
    a.sync();
    block_on(a.core.clone().remove_member(group.clone(), c.user().to_owned())).unwrap();
    c.sync();
    let c_group = c.core.groups().unwrap().into_iter().find(|g| g.group_id == group).unwrap();
    assert_eq!(c_group.epoch, None, "removed: MLS state dropped");
    block_on(a.core.clone().send_text(group.clone(), "C 없이 한 말".into())).unwrap();
    b.sync();
    c.sync();
    assert!(!c.texts(&group).iter().any(|t| t == "C 없이 한 말"));

    // Rejoin: C asks again, A adds it and files the newcomer bundle, C derives the live appointment from it.
    block_on(c.core.clone().join_invite(code)).unwrap();
    a.sync();
    c.sync();
    assert!(c.core.groups().unwrap().into_iter().find(|g| g.group_id == group).unwrap().epoch.is_some());
    block_on(a.core.clone().vote(group.clone(), appt.clone(), Stage::Time, Some("13:00-15:00".into()))).unwrap();
    b.sync();
    c.sync();
    a.sync();

    // Same log, same state (kickoff §3 principle 3).
    assert_eq!(a.digest(&group), b.digest(&group));
    assert_eq!(a.digest(&group), c.digest(&group));
    let state = a.core.group_state(group.clone()).unwrap();
    let view = state.appointments.iter().find(|v| v.appt == appt).unwrap();
    assert_eq!(view.leader, a.user());
    assert_eq!(view.participants.len(), 3);
    let poll = view.polls.iter().find(|p| p.stage == Stage::Time).unwrap();
    assert!(poll.closed, "every participant voted");
    assert_eq!(poll.result.as_deref(), Some("13:00-15:00"));
    assert_eq!((view.start, view.end), (Some(13 * 60), Some(15 * 60)));
    assert_eq!(c.texts(&group), vec!["C의 채팅"], "chat from before the kick stays, chat during it is gone");
    assert_eq!(a.texts(&group), vec!["실시간으로 가나요?", "C의 채팅", "C 없이 한 말"]);
}
