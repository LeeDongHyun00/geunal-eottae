//! mls-rs keeps its group state in the store's SQLCipher file (docs/parts/rust-core.md §4), so the two must open one
//! file with one key and one SQLite build.

use geunal_store::Store;
use mls_rs::identity::SigningIdentity;
use mls_rs::identity::basic::{BasicCredential, BasicIdentityProvider};
use mls_rs::{CipherSuite, CipherSuiteProvider, Client, CryptoProvider, ExtensionList};
use mls_rs_crypto_rustcrypto::RustCryptoProvider;
use mls_rs_provider_sqlite::SqLiteDataStorageEngine;
use mls_rs_provider_sqlite::connection_strategy::{
    CipheredConnectionStrategy, FileConnectionStrategy, SqlCipherConfig, SqlCipherKey,
};

const KEY: [u8; 32] = [7; 32];
// MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519 (protocol §2).
const SUITE: CipherSuite = CipherSuite::CURVE25519_CHACHA;

#[test]
fn keeps_mls_group_state_in_the_store_file() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("geunal.db");
    drop(Store::open(&path, &KEY).unwrap());

    let engine = SqLiteDataStorageEngine::new(CipheredConnectionStrategy::new(
        FileConnectionStrategy::new(&path),
        SqlCipherConfig::new(SqlCipherKey::RawKey(KEY)),
    ))
    .unwrap();
    let crypto = RustCryptoProvider::default();
    let (secret, public) = crypto.cipher_suite_provider(SUITE).unwrap().signature_key_generate().unwrap();
    // The Basic credential carries "{user_id}:{device_id}" (protocol §2).
    let credential = BasicCredential::new(b"user-1:device-1".to_vec()).into_credential();
    let client = Client::builder()
        .identity_provider(BasicIdentityProvider)
        .crypto_provider(crypto)
        .group_state_storage(engine.group_state_storage().unwrap())
        .key_package_repo(engine.key_package_storage().unwrap())
        .signing_identity(SigningIdentity::new(credential, public), secret, SUITE)
        .build();

    // The MLS group ID is the server's 16-byte group UUID (protocol §1).
    let group_id = vec![0x42; 16];
    let mut group = client
        .create_group_with_id(group_id.clone(), ExtensionList::default(), ExtensionList::default(), None)
        .unwrap();
    // The per-epoch state key (protocol §5).
    assert_eq!(group.export_secret(b"geunal state v1", b"", 32).unwrap().as_bytes().len(), 32);
    group.write_to_storage().unwrap();
    drop(group);

    assert_eq!(client.load_group(&group_id).unwrap().current_epoch(), 0);
    let store = Store::open(&path, &KEY).unwrap();
    let tables: i64 = store
        .connection()
        .query_row("SELECT count(*) FROM sqlite_master WHERE type = 'table'", [], |row| row.get(0))
        .unwrap();
    assert!(tables > 0, "mls-rs wrote no tables into the store file");
}
