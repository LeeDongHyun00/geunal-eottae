//! core/vectors/backups.json keeps one sealed archive per format version (protocol §10, §12), so every later build
//! proves it still opens and restores what an older app uploaded. `GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-store
//! --locked --test vectors` adds or rewrites this version's entry and keeps the earlier ones; the plain test run only
//! checks them.

use std::collections::BTreeMap;
use std::path::PathBuf;

use geunal_crypto::backup;
use geunal_store::Store;
use geunal_store::archive::{self, Archive, Calendar, Event, Person, ShiftTemplate};
use serde_json::{Value, json};

const GENERATED_BY: &str = "cd core && GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-store --locked --test vectors";
const NOTIFICATIONS: &str =
    r#"{"event_alert":10,"all_day_alert":360,"next_up_bar":true,"birthday_alerts":true,"leave_suggestions":false}"#;

const AMK: [u8; 32] = [0x42; 32];
const KEY: [u8; 32] = [7; 32];

fn sample() -> Archive {
    Archive {
        version: archive::VERSION,
        made_at: "2026-10-26T13:05:00+09:00".into(),
        calendars: vec![Calendar {
            id: "0192f1e0-0000-7000-8000-0000000000c1".into(),
            name: "개인".into(),
            color: "tangerine".into(),
            blocks_free: true,
            visible: true,
            default_alert: Some("10".into()),
            position: 0,
        }],
        events: vec![
            Event {
                id: "0192f1e0-0000-7000-8000-0000000000e1".into(),
                calendar_id: "0192f1e0-0000-7000-8000-0000000000c1".into(),
                title: "운동".into(),
                starts_at: "2026-10-27T19:00:00+09:00".into(),
                ends_at: "2026-10-27T21:00:00+09:00".into(),
                all_day: false,
                tz: "Asia/Seoul".into(),
                rrule: Some("FREQ=WEEKLY;BYDAY=TU".into()),
                exdates: Some(r#"["2026-11-03T19:00:00+09:00"]"#.into()),
                note: Some("2층".into()),
                alert: None,
                shift_template_id: None,
                updated_at: "2026-10-20T10:00:00+09:00".into(),
            },
            Event {
                id: "0192f1e0-0000-7000-8000-0000000000e2".into(),
                calendar_id: "0192f1e0-0000-7000-8000-0000000000c1".into(),
                title: "연차".into(),
                starts_at: "2026-11-02T00:00:00+09:00".into(),
                ends_at: "2026-11-03T00:00:00+09:00".into(),
                all_day: true,
                tz: "Asia/Seoul".into(),
                rrule: None,
                exdates: None,
                note: None,
                alert: Some("off".into()),
                shift_template_id: None,
                updated_at: "2026-10-21T09:00:00+09:00".into(),
            },
        ],
        shift_templates: vec![ShiftTemplate {
            id: "0192f1e0-0000-7000-8000-0000000000a1".into(),
            name: "마감".into(),
            start_hm: "22:00".into(),
            end_hm: "02:00".into(),
            color: "mint".into(),
            calendar_id: None,
            alert: Some("60".into()),
            tint: 3,
            position: 0,
        }],
        people: vec![Person {
            id: "0192f1e0-0000-7000-8000-0000000000b1".into(),
            name: "엄마".into(),
            birthday_md: Some("08-15".into()),
            is_lunar: true,
            leap_month: false,
            birth_year: Some(1968),
            source: "manual".into(),
            alert: None,
            suggest: true,
            contact_id: None,
        }],
        settings: BTreeMap::from([("notifications".to_owned(), NOTIFICATIONS.to_owned())]),
    }
}

fn nonce_prefix() -> [u8; 19] {
    std::array::from_fn(|i| i as u8)
}

fn entry() -> Value {
    let archive = sample();
    let cbor = archive::encode(&archive);
    json!({
        "archive_version": archive.version,
        "archive": archive,
        "cbor": hex::encode(&cbor),
        "nonce_prefix": hex::encode(nonce_prefix()),
        "object": hex::encode(backup::seal_with_nonce(&AMK, &nonce_prefix(), &cbor)),
    })
}

fn path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../vectors/backups.json")
}

#[test]
fn backups_json_restores_every_version_and_matches_this_build() {
    let current = entry();
    if std::env::var_os("GEUNAL_WRITE_VECTORS").is_some() {
        // Earlier versions stay: a later app must still restore what an older one uploaded.
        let mut archives: Vec<Value> = std::fs::read_to_string(path())
            .ok()
            .and_then(|text| serde_json::from_str::<Value>(&text).ok())
            .and_then(|file| file["archives"].as_array().cloned())
            .unwrap_or_default()
            .into_iter()
            .filter(|a| a["archive_version"] != current["archive_version"])
            .collect();
        archives.push(current);
        let file = json!({
            "version": 1,
            "generated_by": GENERATED_BY,
            "amk": hex::encode(AMK),
            "aad": String::from_utf8(backup::AAD.to_vec()).unwrap(),
            "archives": archives,
        });
        std::fs::write(path(), serde_json::to_string_pretty(&file).unwrap() + "\n").unwrap();
        return;
    }
    let file: Value = serde_json::from_str(
        &std::fs::read_to_string(path())
            .expect("core/vectors/backups.json exists; regenerate with GEUNAL_WRITE_VECTORS=1"),
    )
    .unwrap();
    let amk: [u8; 32] = hex::decode(file["amk"].as_str().unwrap()).unwrap().try_into().unwrap();
    let archives = file["archives"].as_array().unwrap();
    assert!(archives.contains(&current), "this version's entry is what this build writes");
    for a in archives {
        let object = hex::decode(a["object"].as_str().unwrap()).unwrap();
        let cbor = backup::open(&amk, &object).unwrap();
        assert_eq!(hex::encode(&cbor), a["cbor"]);
        let decoded = archive::decode(&cbor).unwrap();
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();
        let restored = store.write(|db| db.restore_archive(&decoded)).unwrap();
        assert_eq!(restored.events as usize, a["archive"]["events"].as_array().unwrap().len());
    }
}
