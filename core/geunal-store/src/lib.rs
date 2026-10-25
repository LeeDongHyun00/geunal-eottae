//! The device's own database: one SQLCipher file holding calendars, people and decrypted group logs, with the MLS state
//! in tables of its own (docs/parts/rust-core.md §4). The schema arrives through numbered migrations, writers take a
//! cross-process `flock` first (the iOS app and its notification extension share the file), and Korean search runs on
//! FTS5 trigram.
#![forbid(unsafe_code)]

mod lock;
mod repo;

use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

use rusqlite::Connection;

pub use lock::WriteLock;
pub use repo::*;

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error(transparent)]
    Sqlite(#[from] rusqlite::Error),
    #[error("could not take the write lock within the timeout")]
    LockTimeout,
    #[error("lock file: {0}")]
    LockIo(#[from] std::io::Error),
    #[error("the database schema is version {0}, newer than this build understands")]
    SchemaTooNew(i64),
    #[error("stored data is malformed: {0}")]
    Malformed(String),
}

/// Numbered migrations, applied in order and recorded in `schema_migrations`. Never edit a shipped file; add the next
/// number instead. `PRAGMA user_version` belongs to mls-rs-provider-sqlite, which creates its tables in the same file
/// when it finds the pragma at 0, so the store keeps its own version in a table.
const MIGRATIONS: &[(i64, &str)] = &[(1, include_str!("../migrations/0001_init.sql"))];

/// The schema version this build writes.
pub const SCHEMA_VERSION: i64 = 1;

/// The database file inside the store directory.
pub const DB_FILE: &str = "geunal.db";

/// A `Connection` is `Send` but not `Sync`; the mutex makes the store shareable between the sync engine's futures and
/// the app's reads. Every query is short, so the lock is never held across network work.
pub struct Store {
    conn: Mutex<Connection>,
    lock_path: PathBuf,
}

impl Store {
    /// Opens or creates the database at `path` under a 32-byte raw key and brings its schema up to date. A wrong key
    /// fails here, not on first use.
    pub fn open(path: &Path, key: &[u8; 32]) -> Result<Self, StoreError> {
        let conn = Connection::open(path)?;
        // A raw key in SQLCipher's x'..' form skips its passphrase derivation; the key is already random.
        conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";", hex(key)))?;
        // SQLCipher checks the key only when it first reads a page.
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(()))?;
        conn.pragma_update(None, "journal_mode", "wal")?;
        conn.pragma_update(None, "foreign_keys", true)?;
        // Another process (the notification extension) may hold a write transaction for a moment.
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        let store = Self { conn: Mutex::new(conn), lock_path: path.with_extension("lock") };
        store.migrate()?;
        Ok(store)
    }

    /// The connection, serialised. A poisoned lock is recovered: the connection itself is still consistent.
    pub(crate) fn conn(&self) -> MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn migrate(&self) -> Result<(), StoreError> {
        self.conn().execute_batch(
            "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);",
        )?;
        let current = self.schema_version()?;
        if current > SCHEMA_VERSION {
            return Err(StoreError::SchemaTooNew(current));
        }
        for (version, sql) in MIGRATIONS.iter().filter(|(v, _)| *v > current) {
            // Each migration is one transaction so a crash mid-way leaves the previous version intact.
            self.conn().execute_batch(&format!(
                "BEGIN; {sql} INSERT INTO schema_migrations (version, applied_at) VALUES ({version}, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')); COMMIT;"
            ))?;
        }
        Ok(())
    }

    /// The SQLCipher release compiled in. Plain SQLite answers this pragma with no rows.
    pub fn cipher_version(&self) -> Result<String, StoreError> {
        Ok(self.conn().query_row("PRAGMA cipher_version", [], |row| row.get(0))?)
    }

    /// The store's schema version on disk, 0 before the first migration.
    pub fn schema_version(&self) -> Result<i64, StoreError> {
        Ok(self.conn().query_row("SELECT coalesce(max(version), 0) FROM schema_migrations", [], |row| row.get(0))?)
    }

    /// Direct access for tests and migrations; holds the lock while the guard lives.
    pub fn connection(&self) -> MutexGuard<'_, Connection> {
        self.conn()
    }

    /// Takes the cross-process write lock, waiting up to `timeout`. The app and the notification extension write the
    /// same file, and the extension gives up after five seconds with a placeholder text (rust-core §4).
    pub fn write_lock(&self, timeout: std::time::Duration) -> Result<WriteLock, StoreError> {
        WriteLock::acquire(&self.lock_path, timeout)
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut out, byte| {
        let _ = write!(out, "{byte:02x}");
        out
    })
}
