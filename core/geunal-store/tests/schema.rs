//! The schema from docs/parts/rust-core.md §4 arrives through migrations, and the repositories round-trip their rows.

use std::time::Duration;

use geunal_store::{
    AvailabilityRow, CalendarRow, EntryRow, EventRow, GroupKind, HolidayRow, OutboxKind, SCHEMA_VERSION, Store,
    StoreError,
};

const KEY: [u8; 32] = [7; 32];

fn open() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();
    (dir, store)
}

fn entry(group: &str, seq: i64, at: &str) -> EntryRow {
    EntryRow {
        group_id: group.into(),
        seq,
        author_id: "u1".into(),
        kind: "text".into(),
        body: vec![1, 2, 3],
        received_at: at.into(),
    }
}

#[test]
fn applies_the_migrations_once_and_records_the_schema_version() {
    let (dir, store) = open();
    assert_eq!(store.schema_version().unwrap(), SCHEMA_VERSION);
    let tables: Vec<String> = {
        let conn = store.connection();
        let mut stmt = conn
            .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .unwrap();
        stmt.query_map([], |r| r.get(0)).unwrap().map(Result::unwrap).collect()
    };
    for expected in [
        "calendars",
        "events",
        "event_overrides",
        "reminders",
        "shift_templates",
        "people",
        "suggestion_stats",
        "groups",
        "group_shares",
        "entries",
        "availability",
        "state_keys",
        "outbox",
        "media_cache",
        "event_attachments",
        "chat_fts",
        "settings",
        "holidays",
        "holiday_years",
        "alpha_stats",
        "backup_records",
    ] {
        assert!(tables.iter().any(|t| t == expected), "missing table {expected}: {tables:?}");
    }
    // mls-rs-provider-sqlite owns PRAGMA user_version; the store leaves it alone.
    let user_version: i64 = store.connection().query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap();
    assert_eq!(user_version, 0);
    drop(store);
    // Reopening runs no migration twice.
    let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();
    assert_eq!(store.schema_version().unwrap(), SCHEMA_VERSION);
}

#[test]
fn refuses_a_database_from_a_newer_build() {
    let (dir, store) = open();
    store.connection().execute("INSERT INTO schema_migrations VALUES (99, 'later')", []).unwrap();
    drop(store);
    assert!(matches!(Store::open(&dir.path().join("geunal.db"), &KEY), Err(StoreError::SchemaTooNew(99))));
}

#[test]
fn the_write_lock_excludes_a_second_holder_until_released() {
    let (_dir, store) = open();
    let held = store.write_lock(Duration::from_millis(100)).unwrap();
    assert!(matches!(store.write_lock(Duration::from_millis(100)), Err(StoreError::LockTimeout)));
    drop(held);
    store.write_lock(Duration::from_millis(100)).unwrap();
}

#[test]
fn keeps_the_cursor_and_returns_entries_with_bundle_entries_first() {
    let (_dir, store) = open();
    store.upsert_group("g", GroupKind::Circle, Some("동네 친구")).unwrap();
    assert_eq!(store.cursor("g").unwrap(), 0);
    assert!(store.insert_entry(&entry("g", 5, "2026-11-01T10:00:00Z")).unwrap());
    assert!(!store.insert_entry(&entry("g", 5, "2026-11-01T10:00:00Z")).unwrap(), "a seq is stored once");
    store.insert_entry(&entry("g", 3, "2026-11-01T09:00:00Z")).unwrap();
    // Newcomer-bundle entries have no seq; they count down from -1 and sort before the log.
    assert_eq!(store.lowest_entry_seq("g").unwrap(), 0);
    store.insert_entry(&entry("g", -1, "2026-10-30T09:00:00Z")).unwrap();
    assert_eq!(store.lowest_entry_seq("g").unwrap(), -1);
    store.set_cursor("g", 5).unwrap();
    let seqs: Vec<i64> = store.entries("g").unwrap().iter().map(|e| e.seq).collect();
    assert_eq!(seqs, vec![-1, 3, 5]);
    assert_eq!(store.group("g").unwrap().unwrap().cursor, 5);
    assert_eq!(store.group("g").unwrap().unwrap().name.as_deref(), Some("동네 친구"));
    // Upserting without a name keeps the one we had.
    store.upsert_group("g", GroupKind::Circle, None).unwrap();
    assert_eq!(store.group("g").unwrap().unwrap().name.as_deref(), Some("동네 친구"));
}

#[test]
fn removing_a_group_takes_its_rows_along() {
    let (_dir, store) = open();
    store.upsert_group("g", GroupKind::Dm, None).unwrap();
    store.insert_entry(&entry("g", 1, "2026-11-01T10:00:00Z")).unwrap();
    store.put_state_key("g", 1, &[1; 32], "2026-11-01T10:00:00Z").unwrap();
    store.enqueue("g", OutboxKind::App, b"x", "2026-11-01T10:00:00Z").unwrap();
    store.index_text("g", 1, "안녕").unwrap();
    store.remove_group("g").unwrap();
    assert!(store.group("g").unwrap().is_none());
    assert!(store.entries("g").unwrap().is_empty());
    assert!(store.state_key("g", 1).unwrap().is_none());
    assert!(store.outbox_head("g").unwrap().is_none());
    assert!(store.search_text("안녕").unwrap().is_empty());
}

#[test]
fn sends_the_outbox_in_order_per_group() {
    let (_dir, store) = open();
    let a = store.enqueue("g1", OutboxKind::App, b"first", "2026-11-01T10:00:00Z").unwrap();
    store.enqueue("g2", OutboxKind::Commit, b"other", "2026-11-01T10:00:01Z").unwrap();
    let b = store.enqueue("g1", OutboxKind::State, b"second", "2026-11-01T10:00:02Z").unwrap();
    assert_eq!(store.outbox_groups().unwrap(), vec!["g1", "g2"]);
    let head = store.outbox_head("g1").unwrap().unwrap();
    assert_eq!((head.id, head.kind, head.payload.as_slice()), (a, OutboxKind::App, b"first".as_slice()));
    store.bump_attempts(a).unwrap();
    assert_eq!(store.outbox_head("g1").unwrap().unwrap().attempts, 1);
    store.dequeue(a).unwrap();
    assert_eq!(store.outbox_head("g1").unwrap().unwrap().id, b);
    store.drop_outbox_commits("g2").unwrap();
    assert!(store.outbox_head("g2").unwrap().is_none());
}

#[test]
fn prunes_state_keys_by_age_and_count() {
    let (_dir, store) = open();
    store.upsert_group("g", GroupKind::Circle, None).unwrap();
    for epoch in 0..60 {
        let day = format!("2026-{:02}-{:02}T00:00:00Z", 1 + epoch / 28, 1 + epoch % 28);
        store.put_state_key("g", epoch, &[epoch as u8; 32], &day).unwrap();
    }
    store.prune_state_keys("g", "2026-01-05T00:00:00Z").unwrap();
    assert_eq!(store.state_key_count("g").unwrap(), 50);
    assert!(store.state_key("g", 3).unwrap().is_none(), "older than the cut-off");
    assert!(store.state_key("g", 9).unwrap().is_none(), "beyond the newest 50");
    assert_eq!(store.state_key("g", 59).unwrap(), Some([59; 32]));
}

#[test]
fn stores_availability_per_member_and_overwrites_it() {
    let (_dir, store) = open();
    store.upsert_group("g", GroupKind::Circle, None).unwrap();
    let mut row = AvailabilityRow {
        group_id: "g".into(),
        user_id: "u".into(),
        window_start: "2026-11-01".into(),
        days: 56,
        bits: vec![0xff; 336],
        epoch: 1,
    };
    store.put_availability(&row).unwrap();
    row.epoch = 2;
    row.bits[0] = 0x0f;
    store.put_availability(&row).unwrap();
    assert_eq!(store.availability("g").unwrap(), vec![row]);
    store.remove_availability("g", "u").unwrap();
    assert!(store.availability("g").unwrap().is_empty());
}

#[test]
fn keeps_calendars_and_events() {
    let (_dir, store) = open();
    let calendar = CalendarRow {
        id: "c".into(),
        name: "개인".into(),
        color: "tangerine".into(),
        kind: "personal".into(),
        blocks_free: true,
        group_id: None,
        visible: true,
        default_alert: None,
        position: 0,
        device_source_id: None,
    };
    let event = EventRow {
        id: "e".into(),
        calendar_id: "c".into(),
        title: "회식".into(),
        starts_at: "2026-11-07T19:00:00+09:00".into(),
        ends_at: "2026-11-07T22:00:00+09:00".into(),
        all_day: false,
        tz: "Asia/Seoul".into(),
        rrule: None,
        exdates: None,
        note: None,
        device_event_id: None,
        group_ref: Some("appt-1".into()),
        tentative: true,
        updated_at: "2026-11-01T10:00:00Z".into(),
        alert: Some("30".into()),
        shift_template_id: None,
        created_at: Some("2026-11-01T10:00:00Z".into()),
        origin: Some("typed".into()),
    };
    store
        .write(|db| -> Result<(), StoreError> {
            db.put_calendar(&calendar)?;
            db.put_event(&event)
        })
        .unwrap();
    store
        .read(|db| -> Result<(), StoreError> {
            assert_eq!(db.calendars()?, vec![calendar.clone()]);
            assert_eq!(db.events()?, vec![event.clone()]);
            assert_eq!(db.events_for_appointment("appt-1")?.len(), 1);
            assert_eq!(db.events_between("2026-11-07T21:00:00+09:00", "2026-11-08T00:00:00+09:00")?.len(), 1);
            assert!(db.events_between("2026-11-07T22:00:00+09:00", "2026-11-08T00:00:00+09:00")?.is_empty());
            Ok(())
        })
        .unwrap();
    // The events go with their calendar.
    store.write(|db| db.delete_calendar("c")).unwrap();
    assert!(store.read(|db| db.events()).unwrap().is_empty());
}

#[test]
fn rolls_back_a_write_whose_closure_fails() {
    let (_dir, store) = open();
    let result: Result<(), StoreError> = store.write(|db| {
        db.set_setting("k", "v")?;
        Err(StoreError::Malformed("stop".into()))
    });
    assert!(result.is_err());
    assert_eq!(store.read(|db| db.setting("k")).unwrap(), None);
}

#[test]
fn replaces_a_holiday_year_whole_and_keeps_two_holidays_on_one_day() {
    let (_dir, store) = open();
    let day = |d: &str, n: &str| HolidayRow { day: d.into(), name: n.into(), substitute: false };
    store
        .write(|db| {
            db.replace_holiday_year(2025, &[day("2025-05-05", "어린이날"), day("2025-05-05", "부처님오신날")], "t1")?;
            db.replace_holiday_year(2025, &[day("2025-05-05", "어린이날"), day("2025-05-06", "대체공휴일")], "t2")
        })
        .unwrap();
    let rows = store.read(|db| db.holidays_between("2025-01-01", "2025-12-31")).unwrap();
    assert_eq!(rows, vec![day("2025-05-05", "어린이날"), day("2025-05-06", "대체공휴일")]);
    assert_eq!(store.read(|db| db.holiday_years()).unwrap(), vec![2025]);
}

#[test]
fn finds_a_word_inside_a_longer_word_and_falls_back_to_like_for_two_letter_queries() {
    let (_dir, store) = open();
    store.index_text("g", 1, "토요일에 볼까?").unwrap();
    store.index_text("g", 2, "일요일은 어때?").unwrap();
    assert_eq!(store.search_text("토요일").unwrap(), vec![("g".to_owned(), 1)]);
    assert_eq!(store.search_text("요일").unwrap(), vec![("g".to_owned(), 1), ("g".to_owned(), 2)]);
    assert!(store.search_text("").unwrap().is_empty());
}
