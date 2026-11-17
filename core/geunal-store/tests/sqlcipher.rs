//! The store depends on SQLCipher, not plain SQLite, on every target; these fail fast if a build loses it.

use std::path::Path;

use geunal_store::{GroupKind, Journal, Store};

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

/// What an iPhone does (`Journal::for_this_platform`): a store made with a write-ahead log by an earlier build is
/// opened with a rollback journal, keeps its rows, and leaves no log or shared-memory file behind, since the lock
/// on that file is what iOS ended the suspended app for.
#[test]
fn moves_a_store_from_the_write_ahead_log_to_a_rollback_journal_and_back() {
    let dir = tempfile::tempdir().unwrap();
    let db = dir.path().join("geunal.db");
    let mode = |store: &Store| -> String {
        store.connection().query_row("PRAGMA journal_mode", [], |row| row.get(0)).unwrap()
    };
    {
        let store = Store::open_with(&db, &KEY, Journal::Wal).unwrap();
        assert_eq!(mode(&store), "wal");
        store.upsert_group("g", GroupKind::Circle, Some("동네 친구")).unwrap();
        assert!(db.with_extension("db-shm").exists());
    }
    {
        let store = Store::open_with(&db, &KEY, Journal::Rollback).unwrap();
        assert_eq!(mode(&store), "truncate");
        assert_eq!(store.group("g").unwrap().unwrap().name.as_deref(), Some("동네 친구"));
        store.upsert_group("h", GroupKind::Circle, None).unwrap();
        assert!(!db.with_extension("db-shm").exists(), "no shared-memory file to hold a lock on");
        assert!(!db.with_extension("db-wal").exists());
    }
    let store = Store::open_with(&db, &KEY, Journal::Wal).unwrap();
    assert_eq!(mode(&store), "wal");
    assert_eq!(store.groups().unwrap().len(), 2);
}
