//! The store depends on SQLCipher, not plain SQLite, on every target; these fail fast if a build loses it.

use std::path::Path;

use geunal_store::Store;

const KEY: [u8; 32] = [7; 32];

fn bytes_on_disk(db: &Path) -> Vec<u8> {
    let mut bytes = std::fs::read(db).unwrap();
    if let Ok(wal) = std::fs::read(db.with_extension("db-wal")) {
        bytes.extend(wal);
    }
    bytes
}

#[test]
fn opens_an_encrypted_database_and_reports_the_sqlcipher_version() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();

    assert!(!store.cipher_version().unwrap().is_empty());
}

#[test]
fn reports_the_crypto_library_it_links() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();

    let (name, version) = store.cipher_provider().unwrap();
    if cfg!(target_vendor = "apple") {
        assert_eq!(name, "commoncrypto");
    } else {
        assert_eq!(name, "openssl");
        assert!(version.starts_with("OpenSSL "), "{version}");
    }
}

#[test]
fn rejects_the_database_under_a_different_key() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("geunal.db");
    let store = Store::open(&path, &KEY).unwrap();
    store.connection().execute_batch("CREATE TABLE t (x INTEGER); INSERT INTO t VALUES (1);").unwrap();
    drop(store);

    assert!(Store::open(&path, &[8; 32]).is_err());
    assert!(Store::open(&path, &KEY).is_ok());
}

#[test]
fn leaves_no_plaintext_on_disk() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("geunal.db");
    let store = Store::open(&path, &KEY).unwrap();
    store
        .connection()
        .execute_batch("CREATE TABLE scratch (title TEXT); INSERT INTO scratch VALUES ('민수 생일 약속');")
        .unwrap();
    drop(store);

    let bytes = bytes_on_disk(&path);
    // A plain SQLite file starts with this header; SQLCipher encrypts the first page as well.
    assert!(!bytes.starts_with(b"SQLite format 3\0"));
    let title = "민수 생일 약속".as_bytes();
    assert!(!bytes.windows(title.len()).any(|window| window == title));
}

#[test]
fn finds_a_korean_word_inside_a_longer_word_with_the_trigram_tokenizer() {
    // Korean keeps particles attached ("토요일에"), so search needs FTS5 trigram in SQLCipher's SQLite (rust-core §4).
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(&dir.path().join("geunal.db"), &KEY).unwrap();
    let conn = store.connection();
    conn.execute_batch(
        "INSERT INTO chat_fts (body, group_id, seq) VALUES ('토요일에 볼까?', 'g', 1), ('일요일은 어때?', 'g', 2);",
    )
    .unwrap();

    let hits: i64 =
        conn.query_row("SELECT count(*) FROM chat_fts WHERE chat_fts MATCH '토요일'", [], |row| row.get(0)).unwrap();
    assert_eq!(hits, 1);
}
