//! The device's own database: one SQLCipher file holding calendars, people and decrypted group logs, with the MLS state
//! in tables of its own (docs/parts/rust-core.md §4). Migrations, the cross-process lock and search arrive with the
//! Rust core work; for now the crate opens the file and proves every build carries SQLCipher.
#![forbid(unsafe_code)]

use std::fmt::Write as _;
use std::path::Path;

use rusqlite::Connection;

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error(transparent)]
    Sqlite(#[from] rusqlite::Error),
}

pub struct Store {
    conn: Connection,
}

impl Store {
    /// Opens or creates the database at `path` under a 32-byte raw key. A wrong key fails here, not on first use.
    pub fn open(path: &Path, key: &[u8; 32]) -> Result<Self, StoreError> {
        let conn = Connection::open(path)?;
        // A raw key in SQLCipher's x'..' form skips its passphrase derivation; the key is already random.
        conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";", hex(key)))?;
        // SQLCipher checks the key only when it first reads a page.
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(()))?;
        conn.pragma_update(None, "journal_mode", "wal")?;
        Ok(Self { conn })
    }

    /// The SQLCipher release compiled in. Plain SQLite answers this pragma with no rows.
    pub fn cipher_version(&self) -> Result<String, StoreError> {
        Ok(self.conn.query_row("PRAGMA cipher_version", [], |row| row.get(0))?)
    }

    pub fn connection(&self) -> &Connection {
        &self.conn
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut out, byte| {
        let _ = write!(out, "{byte:02x}");
        out
    })
}
