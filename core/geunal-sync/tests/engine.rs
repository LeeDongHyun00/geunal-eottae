//! The sync engine against the in-process fake server: Welcomes, epoch conflicts, stale epochs, expired cursors,
//! gaps, state blobs, and three devices computing one group state after a kick and a rejoin (rust-core §9, the M0
//! gate rehearsed in one process).

use std::sync::{Arc, Mutex};

use chrono::{TimeZone, Utc};
use geunal_domain::availability::Availability;
use geunal_domain::entry::{Choice, Entry, Mode, Stage};
use geunal_mls::{Identity, MlsClient};
use geunal_store::Store;
use geunal_sync::fake::{FakeClient, FakeServer};
use geunal_sync::realtime::Event;
use geunal_sync::{Alert, Change, Engine, Listener, Session, sign_in};

#[derive(Default)]
struct Changes(Mutex<Vec<Change>>);

impl Listener for Changes {
    fn on_change(&self, change: Change) {
        self.0.lock().unwrap().push(change);
    }
}

struct Device {
    engine: Engine<FakeClient>,
    session: Session,
    changes: Arc<Changes>,
    key: [u8; 32],
    secret: Vec<u8>,
    _dir: tempfile::TempDir,
}

impl Device {
    fn user(&self) -> &str {
        &self.session.user_id
    }

    fn changes(&self) -> Vec<Change> {
        std::mem::take(&mut *self.changes.0.lock().unwrap())
    }

    fn events(&self, server: &FakeServer) -> Vec<Event> {
        server.take_events(&self.session.device_id).into_iter().filter_map(|v| serde_json::from_value(v).ok()).collect()
    }

    /// A second engine over the same store file and identity: the notification extension beside the app.
    async fn extension(&self) -> Engine<FakeClient> {
        let db = self._dir.path().join("geunal.db");
        let store = Store::open(&db, &self.key).unwrap();
        let api = self.engine.api().clone_session();
        let identity = Identity::new(self.session.user_id.clone(), self.session.device_id.clone());
        let mls = MlsClient::open(&db, &self.key, identity, &self.secret).unwrap();
        Engine::new(store, mls, api, Arc::new(Changes::default()))
    }
}

async fn device(server: &FakeServer, name: &str, n: u8) -> Device {
    let dir = tempfile::tempdir().unwrap();
    let db = dir.path().join("geunal.db");
    let key = [n; 32];
    let store = Store::open(&db, &key).unwrap();
    let (secret, _) = MlsClient::generate_signing_key().unwrap();
    let api = server.client();
    let session = sign_in(&api, name, name, "android", &secret).await.unwrap();
    let identity = Identity::new(session.user_id.clone(), session.device_id.clone());
    let mls = MlsClient::open(&db, &key, identity, &secret).unwrap();
    let changes = Arc::new(Changes::default());
    let engine = Engine::new(store, mls, api, changes.clone())
        .with_clock(|| Utc.with_ymd_and_hms(2026, 11, 1, 9, 0, 0).unwrap());
    engine.publish_key_packages(4).await.unwrap();
    Device { engine, session, changes, key, secret, _dir: dir }
}

fn server() -> FakeServer {
    let server = FakeServer::new();
    server.set_now(Utc.with_ymd_and_hms(2026, 11, 1, 9, 0, 0).unwrap());
    server
}

/// A creates the group and adds B by user id; B picks up the Welcome.
async fn pair(server: &FakeServer) -> (Device, Device, String) {
    let mut a = device(server, "a", 1).await;
    let mut b = device(server, "b", 2).await;
    let group = a.engine.create_group(Some("동네 친구")).await.unwrap();
    a.engine.add_members(&group, &[b.user().to_owned()]).await.unwrap();
    b.engine.sync().await.unwrap();
    (a, b, group)
}

fn text(id: &str, body: &str) -> Entry {
    Entry::Text { id: id.into(), body: body.into(), reply_to: None }
}

fn texts(dev: &Device, group: &str) -> Vec<String> {
    dev.engine
        .log(group)
        .unwrap()
        .into_iter()
        .filter_map(|e| match e.entry {
            Entry::Text { body, .. } => Some(body),
            _ => None,
        })
        .collect()
}

#[tokio::test]
async fn adds_a_device_with_a_welcome_and_rejects_a_commit_for_an_epoch_the_server_has_passed() {
    let server = server();
    let (mut a, mut b, group) = pair(&server).await;
    assert_eq!(a.engine.epoch(&group).unwrap(), Some(1));
    assert_eq!(b.engine.epoch(&group).unwrap(), Some(1));
    let mut roster = b.engine.roster(&group).unwrap().into_iter().map(|i| i.user_id).collect::<Vec<_>>();
    roster.sort();
    let mut expected = vec![a.user().to_owned(), b.user().to_owned()];
    expected.sort();
    assert_eq!(roster, expected);
    assert!(b.changes().contains(&Change::Group { group_id: group.clone() }));
    // The Welcome names the Add commit's seq, and B reads the log from the item after it instead of from 0.
    assert_eq!(b.engine.store().cursor(&group).unwrap(), server.log_len(&group) as i64);

    // Both queue a self-update on epoch 1. A's goes first; B's is built on an epoch the server has passed, gets 409,
    // and is rebuilt on epoch 2.
    a.engine
        .store()
        .enqueue(&group, geunal_store::OutboxKind::Commit, &commit_payload(), "2026-11-01T09:00:00Z")
        .unwrap();
    b.engine
        .store()
        .enqueue(&group, geunal_store::OutboxKind::Commit, &commit_payload(), "2026-11-01T09:00:00Z")
        .unwrap();
    a.engine.flush_outbox().await.unwrap();
    b.engine.flush_outbox().await.unwrap();
    assert_eq!(server.epoch_conflicts(), 1);
    assert_eq!(server.group_epoch(&group), Some(3));
    a.engine.sync().await.unwrap();
    assert_eq!((a.engine.epoch(&group).unwrap(), b.engine.epoch(&group).unwrap()), (Some(3), Some(3)));
    let hi = text("t1", "안녕");
    b.engine.send(&group, &hi, Alert::All, Some("chat")).await.unwrap();
    a.engine.sync().await.unwrap();
    assert_eq!(texts(&a, &group), vec!["안녕"]);
    assert_eq!(texts(&b, &group), vec!["안녕"]);
}

fn commit_payload() -> Vec<u8> {
    // The CBOR of Outgoing::Commit(CommitIntent::SelfUpdate), as the engine would queue for a weekly key refresh.
    let mut out = Vec::new();
    ciborium::into_writer(&serde_json::json!({"Commit": "SelfUpdate"}), &mut out).unwrap();
    out
}

#[tokio::test]
async fn re_seals_own_availability_after_every_epoch_change() {
    let server = server();
    let mut a = device(&server, "a", 1).await;
    let mut b = device(&server, "b", 2).await;
    let group = a.engine.create_group(None).await.unwrap();
    let mut availability = Availability::all_free("2026-11-01".into(), 56);
    availability.mark_busy(26, 30);
    a.engine.set_availability(&group, &availability).unwrap();
    a.engine.flush_outbox().await.unwrap();
    let blob = &server.state_blobs(&group)[0];
    assert_eq!((blob.kind.as_str(), blob.epoch), ("availability", 0));

    a.engine.add_members(&group, &[b.user().to_owned()]).await.unwrap();
    let blobs = server.state_blobs(&group);
    let mine = blobs.iter().find(|b| b.kind == "availability").unwrap();
    assert_eq!(mine.epoch, 1, "sealed again under the new epoch's key");
    b.engine.sync().await.unwrap();
    let seen = b.engine.group_availability(&group).unwrap();
    assert_eq!(seen.len(), 1);
    assert_eq!((seen[0].user_id.as_str(), seen[0].epoch, seen[0].bits.clone()), (a.user(), 1, availability.bits));
}

#[tokio::test]
async fn treats_a_member_whose_availability_blob_cannot_be_opened_as_absent() {
    let server = server();
    let (mut a, mut b, group) = pair(&server).await;
    a.engine.set_availability(&group, &Availability::all_free("2026-11-01".into(), 56)).unwrap();
    a.engine.flush_outbox().await.unwrap();
    b.engine.sync().await.unwrap();
    assert_eq!(b.engine.group_availability(&group).unwrap().len(), 1);
    server.corrupt_state(&group, "availability", a.user());
    b.engine.sync().await.unwrap();
    assert!(b.engine.group_availability(&group).unwrap().is_empty(), "no readable blob, so absent");
}

#[tokio::test]
async fn rejoins_by_external_commit_after_cursor_expired_and_keeps_local_history() {
    let server = server();
    let (mut a, mut b, group) = pair(&server).await;
    a.engine.send(&group, &text("t1", "첫 메시지"), Alert::All, Some("chat")).await.unwrap();
    b.engine.sync().await.unwrap();
    assert_eq!(texts(&b, &group), vec!["첫 메시지"]);
    // B falls behind for a month: the mailbox dropped everything up to A's next message.
    a.engine.send(&group, &text("t2", "놓친 메시지"), Alert::All, Some("chat")).await.unwrap();
    server.expire_log(&group, server.log_len(&group) as i64);
    let before = b.engine.epoch(&group).unwrap().unwrap();
    b.engine.sync().await.unwrap();
    assert!(b.engine.epoch(&group).unwrap().unwrap() > before, "a new epoch from the external commit");
    assert_eq!(texts(&b, &group), vec!["첫 메시지"], "local history stays; the dropped message is gone for good");
    a.engine.sync().await.unwrap();
    assert_eq!(a.engine.roster(&group).unwrap().len(), 2, "old leaf removed, new leaf in");
    a.engine.send(&group, &text("t3", "다시 함께"), Alert::All, Some("chat")).await.unwrap();
    b.engine.sync().await.unwrap();
    assert_eq!(texts(&b, &group), vec!["첫 메시지", "다시 함께"]);
    b.engine.send(&group, &text("t4", "저도요"), Alert::All, Some("chat")).await.unwrap();
    a.engine.sync().await.unwrap();
    assert_eq!(texts(&a, &group), vec!["첫 메시지", "놓친 메시지", "다시 함께", "저도요"]);
}

#[tokio::test]
async fn re_encrypts_and_resends_a_message_refused_as_stale_epoch() {
    let server = server();
    let (mut a, mut b, group) = pair(&server).await;
    // A queues a message in epoch 1, then B moves the group to epoch 2 before A sends.
    a.engine.enqueue_entry(&group, &text("t1", "늦게 보냄"), Alert::All, Some("chat")).unwrap();
    b.engine
        .store()
        .enqueue(&group, geunal_store::OutboxKind::Commit, &commit_payload(), "2026-11-01T09:00:00Z")
        .unwrap();
    b.engine.flush_outbox().await.unwrap();
    a.engine.flush_outbox().await.unwrap();
    assert_eq!(server.stale_epochs(), 1);
    assert_eq!(a.engine.epoch(&group).unwrap(), Some(2));
    b.engine.sync().await.unwrap();
    assert_eq!(texts(&b, &group), vec!["늦게 보냄"]);
}

#[tokio::test]
async fn fetches_the_gap_when_a_pushed_seq_skips_ahead() {
    let server = server();
    let (mut a, mut b, group) = pair(&server).await;
    for i in 1..=3 {
        a.engine.send(&group, &text(&format!("t{i}"), &format!("메시지 {i}")), Alert::All, Some("chat")).await.unwrap();
    }
    let events = b.events(&server);
    let last = events.iter().rev().find(|e| matches!(e, Event::Entry { .. })).cloned().unwrap();
    assert!(matches!(&last, Event::Entry { s, .. } if *s == b.engine.store().cursor(&group).unwrap() + 3));
    b.engine.handle_event(last).await.unwrap();
    assert_eq!(texts(&b, &group), vec!["메시지 1", "메시지 2", "메시지 3"]);

    // The next event is contiguous and carries the ciphertext and the server time: applied without a fetch, and
    // stored under the same received_at the log would give.
    a.engine.send(&group, &text("t4", "메시지 4"), Alert::All, Some("chat")).await.unwrap();
    let next = b.events(&server).into_iter().find(|e| matches!(e, Event::Entry { .. })).unwrap();
    let Event::Entry { at: Some(at), c: Some(_), .. } = &next else { panic!("{next:?}") };
    let at = at.clone();
    let reads = server.log_reads();
    b.engine.handle_event(next).await.unwrap();
    assert_eq!(server.log_reads(), reads, "the pushed item was applied in place");
    assert_eq!(texts(&b, &group).len(), 4);
    let stored = b.engine.log(&group).unwrap().pop().unwrap();
    assert_eq!(stored.received_at, chrono::DateTime::parse_from_rfc3339(&at).unwrap());
    assert!(b.changes().contains(&Change::Chat { group_id: group.clone() }));

    // The sender kept its own entry under the server's clock from the 201, so both sides agree without a log echo.
    let sent = a.engine.log(&group).unwrap().pop().unwrap();
    assert_eq!(sent.received_at, stored.received_at);
    assert_eq!(a.engine.store().cursor(&group).unwrap(), sent.seq, "the cursor moved past the sender's own item");
}

#[tokio::test]
async fn never_decrypts_the_same_application_message_twice_across_the_app_and_the_extension() {
    let server = server();
    let (mut a, b, group) = pair(&server).await;
    a.engine.send(&group, &text("t1", "알림으로 먼저"), Alert::All, Some("chat")).await.unwrap();
    // The extension, holding the write lock, processes the pushed item and stores the plaintext.
    let mut extension = b.extension().await;
    {
        let _lock = extension.store().write_lock(std::time::Duration::from_secs(5)).unwrap();
        let event = b.events(&server).into_iter().find(|e| matches!(e, Event::Entry { .. })).unwrap();
        extension.handle_event(event).await.unwrap();
    }
    assert_eq!(extension.log(&group).unwrap().len(), 1);
    // The app then catches up from its cursor: the entry is already there, nothing is decrypted again, no error.
    let mut b = b;
    b.engine.sync().await.unwrap();
    assert_eq!(texts(&b, &group), vec!["알림으로 먼저"]);
    a.engine.send(&group, &text("t2", "그 다음"), Alert::All, Some("chat")).await.unwrap();
    b.engine.sync().await.unwrap();
    assert_eq!(texts(&b, &group), vec!["알림으로 먼저", "그 다음"]);
}

/// The M0 gate in one process: three devices make a group, trade entries, one is kicked and rejoins, and every device
/// derives the same appointment state (kickoff §8).
#[tokio::test]
async fn three_devices_derive_the_same_state_after_a_kick_and_a_rejoin() {
    let server = server();
    let mut a = device(&server, "a", 1).await;
    let mut b = device(&server, "b", 2).await;
    let mut c = device(&server, "c", 3).await;
    let group = a.engine.create_group(Some("셋")).await.unwrap();
    a.engine.add_members(&group, &[b.user().to_owned(), c.user().to_owned()]).await.unwrap();
    b.engine.sync().await.unwrap();
    c.engine.sync().await.unwrap();
    assert_eq!(a.engine.roster(&group).unwrap().len(), 3);

    let request = Entry::Request {
        appt: "appt-1".into(),
        day: "2026-11-07".into(),
        mode: Mode::Vote,
        start: None,
        end: None,
        activity: None,
        note: Some("저녁 어때?".into()),
        leader_options: false,
    };
    a.engine.send(&group, &request, Alert::All, Some("plan")).await.unwrap();
    b.engine.sync().await.unwrap();
    c.engine.sync().await.unwrap();
    b.engine
        .send(
            &group,
            &Entry::Response { appt: "appt-1".into(), accept: true },
            Alert::Users(vec![a.user().into()]),
            Some("plan"),
        )
        .await
        .unwrap();
    c.engine
        .send(
            &group,
            &Entry::Response { appt: "appt-1".into(), accept: true },
            Alert::Users(vec![a.user().into()]),
            Some("plan"),
        )
        .await
        .unwrap();
    a.engine.sync().await.unwrap();
    let poll = Entry::Poll {
        appt: "appt-1".into(),
        stage: Stage::Time,
        options: vec!["19:00-21:00".into(), "13:00-15:00".into(), "09:00-11:00".into()],
    };
    a.engine.send(&group, &poll, Alert::All, Some("plan")).await.unwrap();
    b.engine.sync().await.unwrap();
    c.engine.sync().await.unwrap();
    b.engine
        .send(
            &group,
            &Entry::PollVote { appt: "appt-1".into(), stage: Stage::Time, choice: Choice::Opt("13:00-15:00".into()) },
            Alert::None,
            None,
        )
        .await
        .unwrap();
    c.engine
        .send(
            &group,
            &Entry::PollVote { appt: "appt-1".into(), stage: Stage::Time, choice: Choice::Any },
            Alert::None,
            None,
        )
        .await
        .unwrap();
    c.engine.send(&group, &text("t1", "C의 채팅"), Alert::All, Some("chat")).await.unwrap();

    // A kicks C; C hears about it through the log and loses the group's keys.
    a.engine.sync().await.unwrap();
    a.engine.remove_member(&group, c.user()).await.unwrap();
    c.engine.sync().await.unwrap();
    assert_eq!(c.engine.epoch(&group).unwrap(), None, "removed: MLS state dropped");
    a.engine.send(&group, &text("t2", "C 없이 한 말"), Alert::All, Some("chat")).await.unwrap();
    b.engine.sync().await.unwrap();
    c.engine.sync().await.unwrap();
    assert!(!texts(&c, &group).contains(&"C 없이 한 말".to_owned()));

    // A adds C again and files a newcomer bundle with the live appointment's entries.
    a.engine.add_members(&group, &[c.user().to_owned()]).await.unwrap();
    c.engine.sync().await.unwrap();
    assert!(c.engine.epoch(&group).unwrap().is_some());
    a.engine
        .send(
            &group,
            &Entry::PollVote { appt: "appt-1".into(), stage: Stage::Time, choice: Choice::Opt("13:00-15:00".into()) },
            Alert::None,
            None,
        )
        .await
        .unwrap();
    b.engine.sync().await.unwrap();
    c.engine.sync().await.unwrap();
    a.engine.sync().await.unwrap();

    let (sa, sb, sc) = (
        a.engine.group_state(&group).unwrap(),
        b.engine.group_state(&group).unwrap(),
        c.engine.group_state(&group).unwrap(),
    );
    assert_eq!(sa, sb);
    assert_eq!(sa, sc);
    let appt = &sa.appointments["appt-1"];
    assert_eq!(appt.leader, a.user());
    assert_eq!(appt.participants().len(), 3);
    let poll = appt.poll(Stage::Time).unwrap();
    assert!(poll.closed, "every participant voted");
    assert_eq!(poll.result.as_deref(), Some("13:00-15:00"));
    assert_eq!((appt.start, appt.end), (Some(13 * 60), Some(15 * 60)));
    assert_eq!(texts(&c, &group), vec!["C의 채팅"], "chat from the kicked period is not in the bundle");
    assert_eq!(texts(&a, &group), vec!["C의 채팅", "C 없이 한 말"]);
}
