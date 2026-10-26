//! What a device signed out by another sign-in keeps (protocol §13 "기기 해제"): its own calendars, events and people.
//! The groups, their MLS state and what they shared go.

use geunal_store::{CalendarRow, GroupKind, OutboxKind, PersonRow, Store};

const KEY: [u8; 32] = [7; 32];

fn calendar(id: &str, kind: &str) -> CalendarRow {
    CalendarRow {
        id: id.into(),
        name: id.into(),
        color: "mint".into(),
        kind: kind.into(),
        blocks_free: true,
        group_id: (kind == "group").then(|| "g1".into()),
        visible: true,
        default_alert: None,
        position: 0,
        device_source_id: None,
    }
}

fn person(id: &str, source: &str) -> PersonRow {
    PersonRow {
        id: id.into(),
        name: id.into(),
        birthday_md: Some("08-15".into()),
        is_lunar: false,
        leap_month: false,
        birth_year: None,
        source: source.into(),
        group_user_id: (source == "group").then(|| "u2".into()),
        group_id: (source == "group").then(|| "g1".into()),
        alert: None,
        suggest: true,
        contact_id: None,
    }
}

fn rows(store: &Store, table: &str) -> i64 {
    store.connection().query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0)).unwrap()
}

#[test]
fn a_revoked_device_forgets_its_groups_and_keeps_its_own_data() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();
    store.upsert_group("g1", GroupKind::Circle, Some("동기")).unwrap();
    store.enqueue("g1", OutboxKind::App, b"queued", "2026-10-26T00:00:00Z").unwrap();
    store.index_text("g1", 1, "토요일에 보자").unwrap();
    store
        .write(|db| {
            db.put_calendar(&calendar("mine", "personal"))?;
            db.put_calendar(&calendar("theirs", "group"))?;
            db.put_person(&person("mom", "manual"))?;
            db.put_person(&person("friend", "group"))
        })
        .unwrap();
    // The tables mls-rs-provider-sqlite makes when an engine first opens the file.
    store
        .connection()
        .execute_batch(
            "CREATE TABLE mls_group (group_id BLOB PRIMARY KEY, snapshot BLOB NOT NULL) WITHOUT ROWID;
             CREATE TABLE epoch (group_id BLOB, epoch_id INTEGER, epoch_data BLOB NOT NULL,
               FOREIGN KEY (group_id) REFERENCES mls_group (group_id) ON DELETE CASCADE
               PRIMARY KEY (group_id, epoch_id)) WITHOUT ROWID;
             CREATE TABLE key_package (id BLOB PRIMARY KEY, expiration INTEGER, data BLOB NOT NULL) WITHOUT ROWID;
             CREATE TABLE psk (psk_id BLOB PRIMARY KEY, data BLOB NOT NULL) WITHOUT ROWID;
             CREATE TABLE kvs (key TEXT PRIMARY KEY, value BLOB NOT NULL) WITHOUT ROWID;
             INSERT INTO mls_group VALUES (x'01', x'02');
             INSERT INTO epoch VALUES (x'01', 0, x'03');
             INSERT INTO key_package VALUES (x'04', 0, x'05');
             INSERT INTO psk VALUES (x'06', x'07');
             INSERT INTO kvs VALUES ('k', x'08');",
        )
        .unwrap();

    store.write(|db| db.wipe_group_state()).unwrap();

    assert!(store.groups().unwrap().is_empty());
    assert!(store.outbox("g1").unwrap().is_empty());
    assert!(store.search_text("토요일").unwrap().is_empty());
    for table in ["mls_group", "epoch", "key_package", "psk", "kvs"] {
        assert_eq!(rows(&store, table), 0, "{table}");
    }
    let calendars: Vec<String> = store.read(|db| db.calendars()).unwrap().into_iter().map(|c| c.id).collect();
    assert_eq!(calendars, ["mine"]);
    let people: Vec<String> = store.read(|db| db.people()).unwrap().into_iter().map(|p| p.id).collect();
    assert_eq!(people, ["mom"]);
}

#[test]
fn a_device_that_never_opened_an_engine_has_no_mls_tables_to_clear() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();
    store.write(|db| db.wipe_group_state()).unwrap();
}
