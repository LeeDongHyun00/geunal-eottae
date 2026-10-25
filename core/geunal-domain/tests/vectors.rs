//! core/vectors/entries.json pins the CBOR of every entry kind (protocol §12), so a later build decodes what this one
//! wrote. `GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-domain --locked --test vectors` regenerates the file; the plain
//! test run only checks it.

use std::path::PathBuf;

use geunal_domain::entry::{Choice, Entry, Mode, Stage, decode, encode};
use serde_json::{Value, json};

fn path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../vectors/entries.json")
}

fn cases() -> Vec<(&'static str, Entry)> {
    vec![
        (
            "text",
            Entry::Text {
                id: "0192f1e0-0000-7000-8000-000000000001".into(),
                body: "토요일 7시 어때?".into(),
                reply_to: None,
            },
        ),
        (
            "text_reply",
            Entry::Text {
                id: "0192f1e0-0000-7000-8000-000000000002".into(),
                body: "좋아".into(),
                reply_to: Some("0192f1e0-0000-7000-8000-000000000001".into()),
            },
        ),
        (
            "media_chat",
            Entry::Media {
                id: "0192f1e0-0000-7000-8000-000000000003".into(),
                object: "chat/2026/11/0192f1e0".into(),
                key: (0u8..32).collect(),
                size: 204_800,
                mime: "image/jpeg".into(),
                sha256: (100u8..132).collect(),
                thumb: Some(vec![0xff, 0xd8, 0xff, 0xe0]),
                name: None,
                appt: None,
            },
        ),
        (
            "media_appointment_file",
            Entry::Media {
                id: "0192f1e0-0000-7000-8000-000000000004".into(),
                object: "appt/2026/11/0192f1e1".into(),
                key: (32u8..64).collect(),
                size: 1_048_576,
                mime: "application/pdf".into(),
                sha256: (132u8..164).collect(),
                thumb: None,
                name: Some("예약확인서.pdf".into()),
                appt: Some("0192f1e0-0000-7000-8000-0000000000a1".into()),
            },
        ),
        ("media_remove", Entry::MediaRemove { id: "0192f1e0-0000-7000-8000-000000000004".into() }),
        (
            "request_vote",
            Entry::Request {
                appt: "0192f1e0-0000-7000-8000-0000000000a1".into(),
                day: "2026-11-07".into(),
                mode: Mode::Vote,
                start: None,
                end: None,
                activity: None,
                note: Some("저녁 어때?".into()),
                leader_options: false,
            },
        ),
        (
            "request_dictator_timed",
            Entry::Request {
                appt: "0192f1e0-0000-7000-8000-0000000000a2".into(),
                day: "2026-11-14".into(),
                mode: Mode::Dictator,
                start: Some(19 * 60),
                end: Some(21 * 60),
                activity: Some("영화".into()),
                note: None,
                leader_options: true,
            },
        ),
        (
            "request_update",
            Entry::RequestUpdate {
                appt: "0192f1e0-0000-7000-8000-0000000000a2".into(),
                mode: None,
                start: Some(18 * 60),
                end: Some(20 * 60),
                activity: None,
                note: Some("한 시간 당김".into()),
            },
        ),
        (
            "lead_transfer",
            Entry::LeadTransfer {
                appt: "0192f1e0-0000-7000-8000-0000000000a1".into(),
                to: "6f1d2c3b-0000-4000-8000-000000000002".into(),
            },
        ),
        ("response_accept", Entry::Response { appt: "0192f1e0-0000-7000-8000-0000000000a1".into(), accept: true }),
        ("response_later", Entry::Response { appt: "0192f1e0-0000-7000-8000-0000000000a1".into(), accept: false }),
        (
            "poll_time",
            Entry::Poll {
                appt: "0192f1e0-0000-7000-8000-0000000000a1".into(),
                stage: Stage::Time,
                options: vec!["19:00-21:00".into(), "13:00-15:00".into(), "09:00-11:00".into()],
            },
        ),
        (
            "poll_option",
            Entry::PollOption {
                appt: "0192f1e0-0000-7000-8000-0000000000a1".into(),
                stage: Stage::Activity,
                option: "보드게임 카페".into(),
            },
        ),
        (
            "poll_vote_option",
            Entry::PollVote {
                appt: "0192f1e0-0000-7000-8000-0000000000a1".into(),
                stage: Stage::Time,
                choice: Choice::Opt("13:00-15:00".into()),
            },
        ),
        (
            "poll_vote_any",
            Entry::PollVote {
                appt: "0192f1e0-0000-7000-8000-0000000000a1".into(),
                stage: Stage::Time,
                choice: Choice::Any,
            },
        ),
        ("poll_close", Entry::PollClose { appt: "0192f1e0-0000-7000-8000-0000000000a1".into(), stage: Stage::Time }),
        ("cancel", Entry::Cancel { appt: "0192f1e0-0000-7000-8000-0000000000a1".into() }),
    ]
}

fn document() -> Value {
    let cases: Vec<Value> = cases()
        .into_iter()
        .map(|(name, entry)| {
            let cbor = encode(&entry);
            json!({ "name": name, "kind": entry.kind(), "entry": entry, "cbor_hex": hex::encode(&cbor), "bytes": cbor.len() })
        })
        .collect();
    json!({
        "version": 1,
        "generated_by": "cd core && GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-domain --locked --test vectors",
        "envelope": { "v": 1, "note": "CBOR of Envelope { v, entry }; byte fields are CBOR byte strings (protocol §4)" },
        "cases": cases,
    })
}

#[test]
fn entries_json_matches_this_build() {
    if std::env::var_os("GEUNAL_WRITE_VECTORS").is_some() {
        let text = serde_json::to_string_pretty(&document()).unwrap() + "\n";
        std::fs::write(path(), text).unwrap();
    }
    let text = std::fs::read_to_string(path())
        .expect("core/vectors/entries.json exists; regenerate with GEUNAL_WRITE_VECTORS=1");
    let file: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(file["version"], 1);
    let expected = cases();
    let cases = file["cases"].as_array().unwrap();
    assert_eq!(cases.len(), expected.len(), "the case list changed; regenerate the vectors");
    for (case, (name, entry)) in cases.iter().zip(expected) {
        assert_eq!(case["name"], name);
        let cbor = hex::decode(case["cbor_hex"].as_str().unwrap()).unwrap();
        assert_eq!(decode(&cbor).unwrap(), entry, "{name}: the committed CBOR decodes to the same entry");
        assert_eq!(encode(&entry), cbor, "{name}: this build writes the committed bytes");
        let from_json: Entry = serde_json::from_value(case["entry"].clone()).unwrap();
        assert_eq!(from_json, entry, "{name}: the JSON form in the file is the same entry");
    }
}
