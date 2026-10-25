//! core/vectors/mls_headers.json and state_blobs.json (protocol §12). The API's Kotlin header parser reads
//! mls_headers.json; state_blobs.json pins the AAD string and the sealed format. Regenerate with
//! `GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-mls --locked --test vectors`; the plain run checks the files.

use std::path::PathBuf;

use geunal_mls::header::{self, WIRE_PRIVATE_MESSAGE, WIRE_PUBLIC_MESSAGE};
use geunal_mls::state::{open, state_aad};
use geunal_mls::{Identity, MlsClient};
use geunal_store::Store;
use serde_json::{Value, json};

fn vectors(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../vectors").join(name)
}

fn writing() -> bool {
    std::env::var_os("GEUNAL_WRITE_VECTORS").is_some()
}

fn read(name: &str) -> Value {
    let text = std::fs::read_to_string(vectors(name))
        .unwrap_or_else(|_| panic!("core/vectors/{name} exists; regenerate with GEUNAL_WRITE_VECTORS=1"));
    serde_json::from_str(&text).unwrap()
}

fn write(name: &str, value: &Value) {
    std::fs::write(vectors(name), serde_json::to_string_pretty(value).unwrap() + "\n").unwrap();
}

/// Real mls-rs output: a commit per epoch and an application message, from a two-device group.
fn mls_header_document() -> Value {
    let group_id: [u8; 16] =
        [0x6f, 0x1d, 0x2c, 0x3b, 0x4a, 0x59, 0x40, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01];
    let dir = tempfile::tempdir().unwrap();
    let device = |user: &str, n: u8| {
        let db = dir.path().join(format!("{user}.db"));
        drop(Store::open(&db, &[n; 32]).unwrap());
        let (secret, _) = MlsClient::generate_signing_key().unwrap();
        MlsClient::open(&db, &[n; 32], Identity::new(user, format!("{user}-dev")), &secret).unwrap()
    };
    let (a, b) = (device("a", 1), device("b", 2));
    let (mut ga, info0) = a.client_group(&group_id);
    let kp = b.key_package().unwrap();
    let add = ga.add_members(std::slice::from_ref(&kp)).unwrap();
    ga.apply_pending_commit().unwrap();
    let mut gb = b.join_welcome(add.welcome.as_ref().unwrap()).unwrap();
    let app1 = gb.encrypt(b"\xa2av\x01eentry\xa2akdtext...").unwrap();
    ga.process(&app1).unwrap();
    let update = ga.self_update().unwrap();
    ga.apply_pending_commit().unwrap();
    gb.process(&update.commit).unwrap();
    let app2 = ga.encrypt(b"second epoch").unwrap();
    let case = |name: &str, bytes: &[u8], expect: Option<(u16, u64)>| {
        let mut c = json!({ "name": name, "message_hex": hex::encode(bytes), "version": 1 });
        match expect {
            Some((wire, epoch)) => {
                c["wire_format"] = json!(wire);
                c["group_id_hex"] = json!(hex::encode(group_id));
                c["epoch"] = json!(epoch);
                c["has_header"] = json!(true);
            }
            None => {
                c["wire_format"] = json!(mls_rs::MlsMessage::from_bytes(bytes).unwrap().wire_format() as u16);
                c["has_header"] = json!(false);
            }
        }
        c
    };
    json!({
        "version": 1,
        "generated_by": "cd core && GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-mls --locked --test vectors",
        "note": "MLSMessage = version u16 ‖ wire_format u16 ‖ (PublicMessage|PrivateMessage: group_id<V> ‖ epoch u64 ‖ ...). Only wire formats 1 and 2 carry the header; the API accepts commits as 1 and messages as 2 (api-server.md §5).",
        "cases": [
            case("commit_add_epoch0", &add.commit, Some((WIRE_PUBLIC_MESSAGE, 0))),
            case("app_message_epoch1", &app1, Some((WIRE_PRIVATE_MESSAGE, 1))),
            case("commit_self_update_epoch1", &update.commit, Some((WIRE_PUBLIC_MESSAGE, 1))),
            case("app_message_epoch2", &app2, Some((WIRE_PRIVATE_MESSAGE, 2))),
            case("welcome_no_header", add.welcome.as_ref().unwrap(), None),
            case("group_info_no_header", &info0, None),
            case("key_package_no_header", &kp, None),
        ],
    })
}

trait ClientGroup {
    fn client_group(&self, group_id: &[u8; 16]) -> (geunal_mls::MlsGroup, Vec<u8>);
}

impl ClientGroup for MlsClient {
    fn client_group(&self, group_id: &[u8; 16]) -> (geunal_mls::MlsGroup, Vec<u8>) {
        self.create_group(group_id).unwrap()
    }
}

#[test]
fn mls_headers_json_parses_with_the_core_parser_and_mls_rs() {
    if writing() {
        write("mls_headers.json", &mls_header_document());
    }
    let file = read("mls_headers.json");
    assert_eq!(file["version"], 1);
    let cases = file["cases"].as_array().unwrap();
    assert!(cases.len() >= 7);
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let bytes = hex::decode(case["message_hex"].as_str().unwrap()).unwrap();
        let message = mls_rs::MlsMessage::from_bytes(&bytes).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(message.wire_format() as u16, case["wire_format"].as_u64().unwrap() as u16, "{name}");
        if case["has_header"].as_bool().unwrap() {
            let parsed = header::parse(&bytes).unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!(parsed.version, 1, "{name}");
            assert_eq!(parsed.wire_format, case["wire_format"].as_u64().unwrap() as u16, "{name}");
            assert_eq!(hex::encode(&parsed.group_id), case["group_id_hex"], "{name}");
            assert_eq!(parsed.epoch, case["epoch"].as_u64().unwrap(), "{name}");
            // mls-rs reads the same values, so the Kotlin parser and the server agree with the sender.
            assert_eq!(message.group_id(), Some(parsed.group_id.as_slice()), "{name}");
            assert_eq!(message.epoch(), Some(parsed.epoch), "{name}");
        } else {
            assert!(header::parse(&bytes).is_err(), "{name}: no header to read");
        }
    }
}

fn state_blob_document() -> Value {
    let key: [u8; 32] = (0u8..32).map(|i| i.wrapping_mul(7).wrapping_add(3)).collect::<Vec<_>>().try_into().unwrap();
    let nonce: [u8; 24] = (0u8..24).collect::<Vec<_>>().try_into().unwrap();
    let aads = [
        (
            "availability",
            ("6f1d2c3b-4a59-4000-8000-000000000001", 7u64, "availability", "2b7e1516-28ae-4000-8000-0000000000aa"),
        ),
        (
            "group_meta",
            ("6f1d2c3b-4a59-4000-8000-000000000001", 7, "group_meta", "2b7e1516-28ae-4000-8000-0000000000aa"),
        ),
        (
            "newcomer_bundle",
            ("6f1d2c3b-4a59-4000-8000-000000000001", 8, "newcomer_bundle", "2b7e1516-28ae-4000-8000-0000000000bb"),
        ),
    ];
    let availability = geunal_domain::availability::Availability::all_free("2026-11-01".into(), 56);
    let blobs: Vec<Value> = aads
        .iter()
        .map(|(name, (g, e, k, o))| {
            let aad = state_aad(g, *e, k, o);
            let plaintext: Vec<u8> = match *name {
                "availability" => availability.encode(),
                "group_meta" => {
                    let mut out = Vec::new();
                    ciborium::into_writer(&json!({"name": "동네 친구", "color": "coral", "plan": {"mode": "vote", "window": [18, 42], "options_by": "anyone"}, "changed_by": o, "changed_at": "2026-11-01T09:00:00Z"}), &mut out).unwrap();
                    out
                }
                _ => b"[]".to_vec(),
            };
            let blob = geunal_crypto::seal_with_nonce(&key, &nonce, aad.as_bytes(), &plaintext);
            json!({
                "name": name, "group_id": g, "epoch": e, "kind": k, "owner_id": o, "aad": aad,
                "key_hex": hex::encode(key), "nonce_hex": hex::encode(nonce),
                "plaintext_hex": hex::encode(&plaintext), "blob_hex": hex::encode(&blob),
            })
        })
        .collect();
    json!({
        "version": 1,
        "generated_by": "cd core && GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-mls --locked --test vectors",
        "format": "nonce (24) ‖ XChaCha20-Poly1305 ciphertext ‖ tag (16); key = MLS-Exporter(\"geunal state v1\", \"\", 32); AAD = state|{group_id}|{epoch}|{kind}|{owner_id} (protocol §5)",
        "exporter_label": "geunal state v1",
        "blobs": blobs,
    })
}

#[test]
fn state_blobs_json_opens_with_the_pinned_aad_and_format() {
    if writing() {
        write("state_blobs.json", &state_blob_document());
    }
    let file = read("state_blobs.json");
    assert_eq!(file["version"], 1);
    assert_eq!(file["exporter_label"].as_str().unwrap().as_bytes(), geunal_mls::state::EXPORTER_LABEL);
    for blob in file["blobs"].as_array().unwrap() {
        let name = blob["name"].as_str().unwrap();
        let aad = state_aad(
            blob["group_id"].as_str().unwrap(),
            blob["epoch"].as_u64().unwrap(),
            blob["kind"].as_str().unwrap(),
            blob["owner_id"].as_str().unwrap(),
        );
        assert_eq!(aad, blob["aad"], "{name}: the AAD string is pinned");
        let key: [u8; 32] = hex::decode(blob["key_hex"].as_str().unwrap()).unwrap().try_into().unwrap();
        let nonce: [u8; 24] = hex::decode(blob["nonce_hex"].as_str().unwrap()).unwrap().try_into().unwrap();
        let plaintext = hex::decode(blob["plaintext_hex"].as_str().unwrap()).unwrap();
        let sealed = hex::decode(blob["blob_hex"].as_str().unwrap()).unwrap();
        assert_eq!(open(&key, &aad, &sealed).as_deref(), Some(plaintext.as_slice()), "{name}");
        assert_eq!(geunal_crypto::seal_with_nonce(&key, &nonce, aad.as_bytes(), &plaintext), sealed, "{name}");
        assert!(
            open(&key, &state_aad("other", 0, "availability", "x"), &sealed).is_none(),
            "{name}: bound to its slot"
        );
    }
}
