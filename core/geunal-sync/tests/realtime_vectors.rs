//! core/vectors/realtime_events.json, published by the API: what it writes on Redis and what the gateway pushes on the
//! device channel. The core must read both forms (protocol §12).

use std::path::PathBuf;

use geunal_sync::realtime::{Event, parse_frame};
use serde_json::{Value, json};

#[test]
fn reads_every_realtime_event_the_api_publishes() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../vectors/realtime_events.json");
    let file: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    assert_eq!(file["version"], 1);
    let device_id = file["device_id"].as_str().unwrap();
    let topic = format!("device:{device_id}");
    let mut checked = 0;
    for case in file["cases"].as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let redis_message = case["redis_message"].as_str().unwrap();
        let direct = serde_json::from_str::<Event>(redis_message);
        let Some(event) = case["event"].as_str() else {
            // The gateway drops it; the core never sees it, but it must not choke on the Redis form either.
            assert!(direct.is_err() || case["dropped"].as_bool() == Some(true), "{name}");
            continue;
        };
        let frame = json!([Value::Null, "9", topic, event, case["payload"]]).to_string();
        let pushed = parse_frame(&frame, &topic);
        assert_eq!(pushed.len(), 1, "{name}: one event from the frame");
        let direct = direct.unwrap_or_else(|e| panic!("{name}: the Redis form does not parse: {e}"));
        assert_eq!(pushed[0], direct, "{name}: the pushed form and the Redis form agree");
        if let Event::Entry { c: Some(c), .. } = &pushed[0] {
            assert_eq!(hex::encode(c), case["ciphertext_hex"], "{name}: the inline ciphertext decodes");
        }
        checked += 1;
    }
    assert!(checked >= 5, "the vector file has cases: {checked}");
}
