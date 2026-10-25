//! Several clients in one process: add, remove, self-update, external join and commit conflicts (rust-core §9), the
//! state key, and the refusal of every cipher suite but 0x0003.

use std::path::PathBuf;

use geunal_mls::header::{WIRE_PRIVATE_MESSAGE, WIRE_PUBLIC_MESSAGE, parse};
use geunal_mls::state::{open, seal, state_aad};
use geunal_mls::{Error, Identity, MlsClient, MlsGroup, Received, SUITE};
use geunal_store::Store;

const KEY: [u8; 32] = [7; 32];
const GROUP_ID: [u8; 16] = [0x42; 16];

struct Device {
    client: MlsClient,
    _dir: tempfile::TempDir,
    db: PathBuf,
}

fn device(user: &str) -> Device {
    let dir = tempfile::tempdir().unwrap();
    let db = dir.path().join("geunal.db");
    // The store creates the file and its own schema first; mls-rs adds its tables to the same file.
    drop(Store::open(&db, &KEY).unwrap());
    let (secret, _) = MlsClient::generate_signing_key().unwrap();
    let client = MlsClient::open(&db, &KEY, Identity::new(user, format!("{user}-dev")), &secret).unwrap();
    Device { client, _dir: dir, db }
}

/// A creates the group and adds B and C with one commit; everyone ends in epoch 1.
fn trio() -> (Device, Device, Device, MlsGroup, MlsGroup, MlsGroup) {
    let (a, b, c) = (device("a"), device("b"), device("c"));
    let (mut ga, _) = a.client.create_group(&GROUP_ID).unwrap();
    let bundle = ga.add_members(&[b.client.key_package().unwrap(), c.client.key_package().unwrap()]).unwrap();
    ga.apply_pending_commit().unwrap();
    let gb = b.client.join_welcome(bundle.welcome.as_ref().unwrap()).unwrap();
    let gc = c.client.join_welcome(bundle.welcome.as_ref().unwrap()).unwrap();
    (a, b, c, ga, gb, gc)
}

fn app(received: Received) -> (String, Vec<u8>) {
    match received {
        Received::Application { sender, data } => (sender.user_id, data),
        other => panic!("expected an application message, got {other:?}"),
    }
}

#[test]
fn keeps_mls_group_state_in_the_store_file() {
    let a = device("a");
    let (mut group, info) = a.client.create_group(&GROUP_ID).unwrap();
    assert!(!info.is_empty());
    group.save().unwrap();
    drop(group);
    assert_eq!(a.client.load_group(&GROUP_ID).unwrap().unwrap().epoch(), 0);
    assert!(a.client.load_group(&[1; 16]).unwrap().is_none());
    let store = Store::open(&a.db, &KEY).unwrap();
    let mls_tables: i64 = store
        .connection()
        .query_row("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name LIKE 'mls_%'", [], |r| r.get(0))
        .unwrap();
    assert!(mls_tables > 0, "mls-rs wrote no tables into the store file");
}

#[test]
fn adds_devices_with_a_welcome_and_removes_one_so_it_reads_nothing_after() {
    let (_a, _b, _c, mut ga, mut gb, mut gc) = trio();
    assert_eq!((ga.epoch(), gb.epoch(), gc.epoch()), (1, 1, 1));
    let mut users: Vec<String> = ga.roster().unwrap().into_iter().map(|m| m.identity.user_id).collect();
    users.sort();
    assert_eq!(users, ["a", "b", "c"]);

    let hello = gb.encrypt(b"hello from b").unwrap();
    assert_eq!(app(ga.process(&hello).unwrap()), ("b".to_owned(), b"hello from b".to_vec()));
    assert_eq!(app(gc.process(&hello).unwrap()), ("b".to_owned(), b"hello from b".to_vec()));

    let c_index = gc.my_index();
    let removal = ga.remove_members(&[c_index]).unwrap();
    assert!(removal.welcome.is_none());
    ga.apply_pending_commit().unwrap();
    assert_eq!(gb.process(&removal.commit).unwrap(), Received::Commit { epoch: 2, removed_me: false });
    assert_eq!(gc.process(&removal.commit).unwrap(), Received::Commit { epoch: 2, removed_me: true });

    let after = ga.encrypt(b"c cannot read this").unwrap();
    assert!(gc.process(&after).is_err(), "a removed member reads nothing after the removal");
    assert_eq!(app(gb.process(&after).unwrap()).1, b"c cannot read this");
}

#[test]
fn drops_the_losing_commit_when_two_members_commit_in_the_same_epoch() {
    let (_a, _b, _c, mut ga, mut gb, mut gc) = trio();
    // Both build a commit on epoch 1; the server (protocol §6) accepts only the first one it sees.
    let winner = ga.self_update().unwrap();
    let _loser = gb.self_update().unwrap();
    assert!(gb.has_pending_commit());
    ga.apply_pending_commit().unwrap();
    // B hears that its commit was refused (409 epoch_conflict), forgets it and processes the winner.
    gb.clear_pending_commit();
    assert_eq!(gb.process(&winner.commit).unwrap(), Received::Commit { epoch: 2, removed_me: false });
    gc.process(&winner.commit).unwrap();
    assert_eq!((ga.epoch(), gb.epoch(), gc.epoch()), (2, 2, 2));
    let msg = gb.encrypt(b"still together").unwrap();
    assert_eq!(app(ga.process(&msg).unwrap()).1, b"still together");
}

#[test]
fn rejoins_by_external_commit_with_its_previous_leaf_removed() {
    let (_a, b, _c, mut ga, gb, mut gc) = trio();
    // A refreshes; B misses that epoch for 30 days and comes back through the GroupInfo the server kept (protocol §2).
    let refresh = ga.self_update().unwrap();
    ga.apply_pending_commit().unwrap();
    gc.process(&refresh.commit).unwrap();
    let (mut gb2, commit) = b.client.external_join(&refresh.group_info, Some(gb.my_index())).unwrap();
    assert_eq!(ga.process(&commit).unwrap(), Received::Commit { epoch: 3, removed_me: false });
    gc.process(&commit).unwrap();
    assert_eq!(gb2.epoch(), 3);
    let mut users: Vec<String> = ga.roster().unwrap().into_iter().map(|m| m.identity.user_id).collect();
    users.sort();
    assert_eq!(users, ["a", "b", "c"], "the old leaf is gone, the new one is in");
    let msg = gb2.encrypt(b"back").unwrap();
    assert_eq!(app(ga.process(&msg).unwrap()), ("b".to_owned(), b"back".to_vec()));
    assert_eq!(app(gc.process(&msg).unwrap()).1, b"back");
}

#[test]
fn never_decrypts_the_same_application_message_twice() {
    let (_a, _b, _c, mut ga, mut gb, _gc) = trio();
    let msg = ga.encrypt(b"once").unwrap();
    gb.process(&msg).unwrap();
    assert!(gb.process(&msg).is_err());
}

#[test]
fn derives_one_state_key_per_epoch_for_every_member_and_binds_blobs_to_their_slot() {
    let (_a, _b, _c, mut ga, mut gb, gc) = trio();
    let key = ga.state_key().unwrap();
    assert_eq!(key, gb.state_key().unwrap());
    assert_eq!(key, gc.state_key().unwrap());

    let aad = state_aad("g", 1, "availability", "a");
    let blob = seal(&key, &aad, b"bits");
    assert_eq!(open(&key, &aad, &blob).as_deref(), Some(b"bits".as_slice()));
    assert!(open(&key, &state_aad("g", 1, "availability", "b"), &blob).is_none(), "moved to another owner");
    assert!(open(&key, &state_aad("g", 2, "availability", "a"), &blob).is_none(), "moved to another epoch");

    let refresh = ga.self_update().unwrap();
    ga.apply_pending_commit().unwrap();
    gb.process(&refresh.commit).unwrap();
    assert_ne!(ga.state_key().unwrap(), key, "a new epoch has a new key");
    assert_eq!(ga.state_key().unwrap(), gb.state_key().unwrap());
}

#[test]
fn reads_group_id_and_epoch_from_commits_and_messages_like_mls_rs() {
    let (_a, _b, _c, mut ga, _gb, _gc) = trio();
    let msg = ga.encrypt(b"x").unwrap();
    let header = parse(&msg).unwrap();
    assert_eq!((header.version, header.wire_format), (1, WIRE_PRIVATE_MESSAGE));
    assert_eq!((header.group_id.as_slice(), header.epoch), (GROUP_ID.as_slice(), 1));
    let commit = ga.self_update().unwrap();
    let header = parse(&commit.commit).unwrap();
    assert_eq!((header.version, header.wire_format), (1, WIRE_PUBLIC_MESSAGE));
    assert_eq!((header.group_id.as_slice(), header.epoch), (GROUP_ID.as_slice(), 1));
    let parsed = mls_rs::MlsMessage::from_bytes(&commit.commit).unwrap();
    assert_eq!(parsed.epoch(), Some(1));
    assert_eq!(parsed.group_id(), Some(GROUP_ID.as_slice()));
}

#[test]
fn signs_with_the_device_key_and_reads_key_packages() {
    let a = device("a");
    let signature = a.client.sign(b"nonce").unwrap();
    assert_eq!(signature.len(), 64, "a plain Ed25519 signature the server can verify");
    let info = MlsClient::inspect_key_package(&a.client.key_package().unwrap()).unwrap();
    assert_eq!(info.identity, Identity::new("a", "a-dev"));
    assert_eq!(info.signature_key, a.client.signature_key());
    assert!(matches!(Identity::from_credential(b"no-colon"), Err(Error::BadCredential)));
    assert!(matches!(a.client.create_group(&[1; 15]), Err(Error::BadGroupId)));
}

/// A client on P-256 built straight from mls-rs, standing in for a tampered or foreign device.
mod p256 {
    use mls_rs::identity::SigningIdentity;
    use mls_rs::identity::basic::{BasicCredential, BasicIdentityProvider};
    use mls_rs::{CipherSuite, CipherSuiteProvider, Client, CryptoProvider, ExtensionList};
    use mls_rs_crypto_rustcrypto::RustCryptoProvider;

    pub const OTHER: CipherSuite = CipherSuite::P256_AES128;

    pub fn key_package_and_group_info() -> (Vec<u8>, Vec<u8>, Vec<u8>) {
        let crypto = RustCryptoProvider::default();
        let (secret, public) = crypto.cipher_suite_provider(OTHER).unwrap().signature_key_generate().unwrap();
        let credential = BasicCredential::new(b"x:x-dev".to_vec()).into_credential();
        let client = Client::builder()
            .identity_provider(BasicIdentityProvider)
            .crypto_provider(crypto)
            .signing_identity(SigningIdentity::new(credential, public), secret, OTHER)
            .build();
        let kp = client.generate_key_package_message(ExtensionList::default(), ExtensionList::default(), None).unwrap();
        let group =
            client.create_group_with_id(vec![9; 16], ExtensionList::default(), ExtensionList::default(), None).unwrap();
        let info = group.group_info_message_allowing_ext_commit(true).unwrap();
        (kp.to_bytes().unwrap(), info.to_bytes().unwrap(), group.group_id().to_vec())
    }
}

#[test]
fn refuses_key_packages_and_group_info_on_any_cipher_suite_but_0x0003() {
    let (kp, info, _) = p256::key_package_and_group_info();
    assert!(matches!(MlsClient::inspect_key_package(&kp), Err(Error::WrongCipherSuite(s)) if s == p256::OTHER));
    let a = device("a");
    let (mut ga, _) = a.client.create_group(&GROUP_ID).unwrap();
    assert!(ga.add_members(&[kp]).is_err());
    assert!(matches!(a.client.external_join(&info, None), Err(Error::WrongCipherSuite(s)) if s == p256::OTHER));
    assert_eq!(SUITE, mls_rs::CipherSuite::CURVE25519_CHACHA);
}
