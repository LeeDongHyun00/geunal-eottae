//! core/vectors/recovery.json and login_nonce.json pin the recovery code and the sign-in nonce binding (protocol §10,
//! §12, §13): the API server checks recovery signatures and provider nonces against them, and a later core build
//! must derive the same values from the same code. `GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-crypto --locked --test
//! vectors` regenerates both files; the plain test run only checks them.

use std::path::PathBuf;

use ed25519_dalek::SigningKey;
use geunal_crypto::kdf;
use geunal_crypto::login::provider_nonce;
use geunal_crypto::recovery::{self, CodeError, Entropy, RecoveryKeys};
use serde_json::{Value, json};

const GENERATED_BY: &str = "cd core && GEUNAL_WRITE_VECTORS=1 cargo test -p geunal-crypto --locked --test vectors";
const LIST_SOURCE: &str =
    "https://github.com/bitcoin/bips/blob/100ba872c808946155b1eeb8c4568058f2eea0a7/bip-0039/korean.txt";
/// The shape of a server nonce: base64url of 32 random bytes, here 0..32.
const NONCE: &str = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

fn path(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../vectors").join(name)
}

fn bytes<const N: usize>(text: &str) -> [u8; N] {
    hex::decode(text).unwrap().try_into().unwrap()
}

/// Entropy, AMK and wrap nonce of each code; everything else in the file follows from these.
fn codes() -> Vec<(Entropy, [u8; 32], [u8; 24])> {
    vec![
        (
            bytes("00112233445566778899aabbccddeeff"),
            bytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
            bytes("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7"),
        ),
        ([0u8; 16], [0x11; 32], [0x22; 24]),
        (
            bytes("f0e1d2c3b4a5968778695a4b3c2d1e0f"),
            bytes("ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"),
            bytes("0f0e0d0c0b0a090807060504030201000f0e0d0c0b0a0908"),
        ),
    ]
}

fn code(entropy: &Entropy, amk: &[u8; 32], wrap_nonce: &[u8; 24]) -> Value {
    let root = kdf::recovery_root(entropy).unwrap();
    let keys = RecoveryKeys::from_root(&root);
    json!({
        "entropy": hex::encode(entropy),
        "words": recovery::words(entropy),
        "root": hex::encode(root),
        "wrap_key": hex::encode(keys.wrap_key()),
        "recovery_id": hex::encode(keys.id()),
        "recovery_public_key": hex::encode(keys.public_key()),
        "amk": hex::encode(amk),
        "wrap_nonce": hex::encode(wrap_nonce),
        "wrapped_amk": hex::encode(keys.wrap_amk_with_nonce(wrap_nonce, amk)),
        "nonce": NONCE,
        "rotate_signature": hex::encode(keys.sign_rotation(NONCE)),
        "recover_signature": hex::encode(keys.sign_recovery(NONCE)),
        "delete_signature": hex::encode(keys.sign_deletion(NONCE)),
    })
}

/// Codes that must be refused, with the reason the core gives.
fn invalid_codes() -> Vec<Value> {
    let good: Vec<String> = recovery::words(&codes()[0].0).iter().map(|w| w.to_string()).collect();
    // Another listed word in the last place: it keeps the 7 entropy bits but changes the checksum.
    let last = recovery::list().iter().position(|w| *w == good[11]).unwrap();
    let mut checksum = good.clone();
    checksum[11] = recovery::list()[last ^ 1].to_string();
    let mut unknown = good.clone();
    unknown[3] = "가나다라".into();
    vec![
        json!({ "words": checksum, "error": "checksum" }),
        json!({ "words": good[..11], "error": "word_count" }),
        json!({ "words": unknown, "error": "unknown_word", "index": 3 }),
    ]
}

fn recovery_document() -> Value {
    let params = kdf::params();
    json!({
        "version": 1,
        "generated_by": GENERATED_BY,
        "word_list": { "source": LIST_SOURCE, "sha256": recovery::LIST_SHA256, "count": recovery::LIST_LEN },
        "argon2id": {
            "salt": String::from_utf8(kdf::SALT.to_vec()).unwrap(),
            "memory_kib": params.m_cost(),
            "iterations": params.t_cost(),
            "parallelism": params.p_cost(),
            "output_len": params.output_len(),
        },
        "hkdf_info": {
            "wrap_key": "geunal amk wrap v1",
            "recovery_id": "geunal recovery id v1",
            "signing_seed": "geunal recovery sign v1",
        },
        "amk_aad": String::from_utf8(kdf::AMK_AAD.to_vec()).unwrap(),
        "signed": {
            "rotate": "geunal recovery rotate v1 ‖ nonce",
            "recover": "geunal recover v1 ‖ nonce",
            "delete": "geunal delete v1 ‖ nonce",
        },
        "codes": codes().iter().map(|(e, amk, n)| code(e, amk, n)).collect::<Vec<_>>(),
        "invalid_codes": invalid_codes(),
    })
}

fn login_document() -> Value {
    let cases: Vec<Value> = [[1u8; 32], [2u8; 32], [3u8; 32]]
        .iter()
        .zip([NONCE, "nonce-1", "w8tH0GHl3q4nY2r5wq0dqvXbN3wVfR5iJ9yQ7KcVQ2M"])
        .map(|(seed, nonce)| {
            let key = SigningKey::from_bytes(seed).verifying_key().to_bytes();
            json!({
                "nonce": nonce,
                "signature_key": hex::encode(key),
                "provider_nonce": provider_nonce(nonce, &key),
            })
        })
        .collect();
    json!({
        "version": 1,
        "generated_by": GENERATED_BY,
        "formula": "lowercase hex of SHA-256(\"geunal login v1\" ‖ nonce (ASCII) ‖ signature_key (32 bytes))",
        "cases": cases,
    })
}

fn check(name: &str, document: Value) {
    let path = path(name);
    if std::env::var_os("GEUNAL_WRITE_VECTORS").is_some() {
        std::fs::write(&path, serde_json::to_string_pretty(&document).unwrap() + "\n").unwrap();
        return;
    }
    let text = std::fs::read_to_string(&path)
        .unwrap_or_else(|_| panic!("core/vectors/{name} exists; regenerate with GEUNAL_WRITE_VECTORS=1"));
    let file: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(file, document, "core/vectors/{name} differs from what this build derives; regenerate it");
}

#[test]
fn recovery_json_matches_this_build() {
    let document = recovery_document();
    // The file's refusals are what the core says about them.
    for case in document["invalid_codes"].as_array().unwrap() {
        let words: Vec<String> = serde_json::from_value(case["words"].clone()).unwrap();
        let expected = match case["error"].as_str().unwrap() {
            "checksum" => CodeError::Checksum,
            "word_count" => CodeError::WordCount(words.len()),
            _ => CodeError::UnknownWord(case["index"].as_u64().unwrap() as usize),
        };
        assert_eq!(recovery::entropy(&words), Err(expected));
    }
    // And each listed code reads back as its entropy and unwraps its AMK.
    for case in document["codes"].as_array().unwrap() {
        let words: Vec<String> = serde_json::from_value(case["words"].clone()).unwrap();
        let entropy = recovery::entropy(&words).unwrap();
        assert_eq!(hex::encode(entropy), case["entropy"]);
        let keys = RecoveryKeys::from_root(&bytes(case["root"].as_str().unwrap()));
        let wrapped = hex::decode(case["wrapped_amk"].as_str().unwrap()).unwrap();
        assert_eq!(hex::encode(keys.unwrap_amk(&wrapped).unwrap()), case["amk"]);
    }
    check("recovery.json", document);
}

#[test]
fn login_nonce_json_matches_this_build() {
    check("login_nonce.json", login_document());
}
