//! The backup archive (protocol §10): what it carries and leaves out, and how a restore merges into a store. The
//! sealed archives every later build must restore are in tests/vectors.rs.

use std::collections::BTreeMap;

use geunal_store::archive::{self, ArchiveError, OwnData, Restored};
use geunal_store::{CalendarRow, Db, EventRow, PersonRow, ShiftTemplateRow, Store, SuggestionStatRow};
use serde_json::json;

const KEY: [u8; 32] = [7; 32];
const NOTIFICATIONS: &str =
    r#"{"event_alert":10,"all_day_alert":360,"next_up_bar":true,"birthday_alerts":true,"leave_suggestions":false}"#;

fn open() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();
    (dir, store)
}

fn calendar(id: &str, name: &str, kind: &str) -> CalendarRow {
    CalendarRow {
        id: id.into(),
        name: name.into(),
        color: "mint".into(),
        kind: kind.into(),
        blocks_free: true,
        group_id: (kind == "group").then(|| "g1".into()),
        visible: true,
        default_alert: None,
        position: 0,
        device_source_id: (kind == "device_mirror").then(|| "device-cal".into()),
    }
}

fn event(id: &str, calendar_id: &str, title: &str, day: &str) -> EventRow {
    EventRow {
        id: id.into(),
        calendar_id: calendar_id.into(),
        title: title.into(),
        starts_at: format!("{day}T19:00:00+09:00"),
        ends_at: format!("{day}T21:00:00+09:00"),
        all_day: false,
        tz: "Asia/Seoul".into(),
        rrule: None,
        exdates: None,
        note: None,
        device_event_id: None,
        group_ref: None,
        tentative: false,
        updated_at: "2026-10-20T10:00:00+09:00".into(),
        alert: None,
        shift_template_id: None,
        created_at: Some("2026-10-20T10:00:00+09:00".into()),
        origin: Some("typed".into()),
    }
}

fn person(id: &str, name: &str, source: &str) -> PersonRow {
    PersonRow {
        id: id.into(),
        name: name.into(),
        birthday_md: Some("03-14".into()),
        is_lunar: false,
        leap_month: false,
        birth_year: None,
        source: source.into(),
        group_user_id: (source == "group").then(|| "u9".into()),
        group_id: (source == "group").then(|| "g1".into()),
        alert: None,
        suggest: true,
        contact_id: None,
    }
}

/// A store with a bit of everything: own data the backup carries and the rest it leaves out.
fn fill(db: &Db<'_>) {
    db.put_calendar(&calendar("c-own", "개인", "personal")).unwrap();
    db.put_calendar(&calendar("c-shift", "알바", "personal")).unwrap();
    db.put_calendar(&calendar("c-device", "회사", "device_mirror")).unwrap();
    db.put_calendar(&calendar("c-group", "주말모임", "group")).unwrap();
    db.put_event(&EventRow {
        rrule: Some("FREQ=WEEKLY;BYDAY=TU".into()),
        exdates: Some(r#"["2026-11-03T19:00:00+09:00"]"#.into()),
        note: Some("2층".into()),
        alert: Some("30".into()),
        ..event("e-gym", "c-own", "운동", "2026-10-27")
    })
    .unwrap();
    db.put_shift_template(&ShiftTemplateRow {
        id: "t-close".into(),
        name: "마감".into(),
        start_hm: "22:00".into(),
        end_hm: "02:00".into(),
        color: "mint".into(),
        calendar_id: Some("c-shift".into()),
        alert: Some("60".into()),
        tint: 4,
        position: 0,
    })
    .unwrap();
    db.put_event(&EventRow {
        shift_template_id: Some("t-close".into()),
        origin: Some("shift".into()),
        ..event("e-shift", "c-shift", "마감", "2026-10-28")
    })
    .unwrap();
    db.put_event(&EventRow {
        device_event_id: Some("dev-1".into()),
        ..event("e-device", "c-device", "회의", "2026-10-29")
    })
    .unwrap();
    db.put_event(&EventRow { group_ref: Some("appt-1".into()), ..event("e-group", "c-group", "저녁", "2026-10-30") })
        .unwrap();
    db.put_person(&PersonRow {
        is_lunar: true,
        leap_month: true,
        alert: Some("off".into()),
        ..person("p-mom", "엄마", "manual")
    })
    .unwrap();
    db.put_person(&PersonRow {
        birth_year: Some(1998),
        contact_id: Some("contact-7".into()),
        ..person("p-minsu", "민수", "contacts")
    })
    .unwrap();
    db.put_person(&person("p-group", "지은", "group")).unwrap();
    db.set_setting("notifications", NOTIFICATIONS).unwrap();
    db.set_setting("changed_at", "2026-10-26T04:00:00Z").unwrap();
    db.put_suggestion_stat(&SuggestionStatRow {
        title: "운동".into(),
        weekday: 2,
        start_hm: "19:00".into(),
        end_hm: Some("21:00".into()),
        calendar_id: Some("c-own".into()),
        all_day: false,
        uses: 3,
        last_used_at: "2026-10-20T10:00:00Z".into(),
        hint_offered: false,
    })
    .unwrap();
}

#[test]
fn carries_own_calendars_events_templates_people_and_account_settings_only() {
    let (_dir, store) = open();
    let archive = store
        .write(|db| {
            fill(db);
            db.archive("2026-10-26T13:05:00+09:00")
        })
        .unwrap();
    assert_eq!(archive.version, archive::VERSION);
    assert_eq!(archive.made_at, "2026-10-26T13:05:00+09:00");
    let ids = |v: Vec<&str>| v.into_iter().map(String::from).collect::<Vec<_>>();
    assert_eq!(archive.calendars.iter().map(|c| c.id.clone()).collect::<Vec<_>>(), ids(vec!["c-own", "c-shift"]));
    let mut events: Vec<String> = archive.events.iter().map(|e| e.id.clone()).collect();
    events.sort();
    assert_eq!(events, ids(vec!["e-gym", "e-shift"]));
    let gym = archive.events.iter().find(|e| e.id == "e-gym").unwrap();
    assert_eq!(gym.rrule.as_deref(), Some("FREQ=WEEKLY;BYDAY=TU"));
    assert_eq!(gym.exdates.as_deref(), Some(r#"["2026-11-03T19:00:00+09:00"]"#));
    assert_eq!((gym.note.as_deref(), gym.alert.as_deref()), (Some("2층"), Some("30")));
    assert_eq!(archive.shift_templates.len(), 1);
    let mut people: Vec<String> = archive.people.iter().map(|p| p.id.clone()).collect();
    people.sort();
    assert_eq!(people, ids(vec!["p-minsu", "p-mom"]));
    assert_eq!(archive.settings, BTreeMap::from([("notifications".to_owned(), NOTIFICATIONS.to_owned())]));
    assert_eq!(store.read(|db| db.own_data()).unwrap(), OwnData { events: 2, people: 2 });
}

#[test]
fn restoring_merges_into_a_store_and_replaces_the_empty_default_calendar() {
    let (_a, source) = open();
    let archive = source
        .write(|db| {
            fill(db);
            db.archive("2026-10-26T13:05:00+09:00")
        })
        .unwrap();

    // A new device: its own empty "개인", a calendar of its own with an event, and a stale copy of one backed-up event.
    let (_b, target) = open();
    let restored = target
        .write(|db| {
            db.put_calendar(&calendar("c-new-default", "개인", "personal")).unwrap();
            db.put_calendar(&calendar("c-local", "운동 기록", "personal")).unwrap();
            db.put_event(&event("e-local", "c-local", "달리기", "2026-10-31")).unwrap();
            db.put_event(&EventRow { title: "옛 제목".into(), ..event("e-gym", "c-local", "x", "2026-10-27") })
                .unwrap();
            db.restore_archive(&archive)
        })
        .unwrap();
    assert_eq!(restored, Restored { calendars: 2, events: 2, shift_templates: 1, people: 2 });

    target
        .read(|db| {
            let mut calendars: Vec<String> = db.calendars()?.into_iter().map(|c| c.id).collect();
            calendars.sort();
            assert_eq!(calendars, ["c-local", "c-own", "c-shift"], "the empty default went, the local one stayed");
            let gym = db.event("e-gym")?.unwrap();
            assert_eq!((gym.title.as_str(), gym.calendar_id.as_str()), ("운동", "c-own"), "the backup wins");
            assert_eq!(gym.origin.as_deref(), Some("restore"));
            assert_eq!(gym.created_at, None, "nothing to undo or count");
            assert!(db.event("e-local")?.is_some(), "local-only events stay");
            assert_eq!(db.shift_template("t-close")?.unwrap().alert.as_deref(), Some("60"));
            assert!(db.person("p-mom")?.unwrap().leap_month);
            assert_eq!(db.setting("notifications")?.as_deref(), Some(NOTIFICATIONS));
            assert_eq!(db.setting("changed_at")?, None, "device settings stay on their device");
            Ok::<_, geunal_store::StoreError>(())
        })
        .unwrap();
}

#[test]
fn discarding_removes_own_data_and_keeps_device_calendars() {
    let (_dir, store) = open();
    store
        .write(|db| {
            fill(db);
            db.discard_own_data()?;
            let kinds: Vec<String> = db.calendars()?.into_iter().map(|c| c.kind).collect();
            assert!(kinds.iter().all(|k| k != "personal"));
            assert!(kinds.iter().any(|k| k == "device_mirror"));
            assert!(db.event("e-gym")?.is_none() && db.event("e-device")?.is_some());
            assert!(db.shift_templates()?.is_empty() && db.suggestion_stats()?.is_empty());
            let people: Vec<String> = db.people()?.into_iter().map(|p| p.source).collect();
            assert_eq!(people, ["group"]);
            assert_eq!(db.setting("notifications")?, None);
            assert_eq!(db.own_data()?, OwnData::default());
            Ok::<_, geunal_store::StoreError>(())
        })
        .unwrap();
}

#[test]
fn refuses_a_newer_archive_and_bytes_that_are_not_one() {
    let (_dir, store) = open();
    let mut newer = store.read(|db| db.archive("2026-10-26T13:05:00+09:00")).unwrap();
    newer.version = archive::VERSION + 1;
    assert!(
        matches!(archive::decode(&archive::encode(&newer)), Err(ArchiveError::Newer(v)) if v == archive::VERSION + 1)
    );
    assert!(matches!(archive::decode(b"not cbor"), Err(ArchiveError::Malformed(_))));
    // An archive of this version with fields a later one may add still reads.
    let mut extended = Vec::new();
    ciborium::into_writer(
        &json!({ "version": 1, "made_at": "2026-10-26T13:05:00+09:00", "photos": [] }),
        &mut extended,
    )
    .unwrap();
    assert_eq!(archive::decode(&extended).unwrap().made_at, "2026-10-26T13:05:00+09:00");
}
