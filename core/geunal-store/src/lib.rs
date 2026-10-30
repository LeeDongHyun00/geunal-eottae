//! The device's own database: one SQLCipher file holding calendars, people and decrypted group logs, with the MLS state
//! in tables of its own (docs/parts/rust-core.md §4). The schema arrives through numbered migrations, writers take a
//! cross-process `flock` first (the iOS app and its notification extension share the file), and Korean search runs on
//! FTS5 trigram.
#![forbid(unsafe_code)]

pub mod archive;
mod local;
mod lock;
mod repo;

use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::{Connection, TransactionBehavior};

pub use local::*;
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
    #[error("moving the store aside: {0}")]
    SetAside(std::io::Error),
}

/// Numbered migrations, applied in order and recorded in `schema_migrations`. Never edit a shipped file; add the next
/// number instead. `PRAGMA user_version` belongs to mls-rs-provider-sqlite, which creates its tables in the same file
/// when it finds the pragma at 0, so the store keeps its own version in a table.
const MIGRATIONS: &[(i64, &str)] = &[
    (1, include_str!("../migrations/0001_init.sql")),
    (2, include_str!("../migrations/0002_m1.sql")),
    (3, include_str!("../migrations/0003_alpha_sessions.sql")),
];

/// The schema version this build writes.
pub const SCHEMA_VERSION: i64 = 3;

/// The database file inside the store directory.
pub const DB_FILE: &str = "geunal.db";

/// A `Connection` is `Send` but not `Sync`; the mutex makes the store shareable between the sync engine's futures and
/// the app's reads. Every query is short, so the lock is never held across network work. Clones share the one
/// connection: the calendar screens read through it whether or not an account is signed in, while the sync engine
/// holds its own clone.
#[derive(Clone)]
pub struct Store {
    inner: Arc<Inner>,
}

struct Inner {
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
        let store = Self { inner: Arc::new(Inner { conn: Mutex::new(conn), lock_path: path.with_extension("lock") }) };
        store.migrate()?;
        Ok(store)
    }

    /// The connection, serialised. A poisoned lock is recovered: the connection itself is still consistent.
    pub(crate) fn conn(&self) -> MutexGuard<'_, Connection> {
        self.inner.conn.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Runs `f` over the connection without a transaction: for reads that need no snapshot across statements.
    pub fn read<R, E: From<StoreError>>(&self, f: impl FnOnce(&Db<'_>) -> Result<R, E>) -> Result<R, E> {
        let conn = self.conn();
        f(&Db::new(&conn))
    }

    /// Runs `f` in one immediate transaction: it commits when `f` returns `Ok` and rolls back otherwise, so a rule
    /// checked inside `f` (the last calendar, a template that went away) holds for the writes that follow it.
    pub fn write<R, E: From<StoreError>>(&self, f: impl FnOnce(&Db<'_>) -> Result<R, E>) -> Result<R, E> {
        let mut conn = self.conn();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate).map_err(StoreError::from)?;
        let out = f(&Db::new(&tx))?;
        tx.commit().map_err(StoreError::from)?;
        Ok(out)
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

    /// The crypto library SQLCipher runs on and the version it reports at run time. The version comes from the library
    /// that is actually linked, not from the headers it was compiled against.
    pub fn cipher_provider(&self) -> Result<(String, String), StoreError> {
        let conn = self.conn();
        let name = conn.query_row("PRAGMA cipher_provider", [], |row| row.get(0))?;
        let version = conn.query_row("PRAGMA cipher_provider_version", [], |row| row.get(0))?;
        Ok((name, version))
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
        WriteLock::acquire(&self.inner.lock_path, timeout)
    }

    /// The write lock of the store at `path`, taken before it is opened: deciding which key opens the store must not
    /// run in the app and the notification extension at once.
    pub fn lock_unopened(path: &Path, timeout: std::time::Duration) -> Result<WriteLock, StoreError> {
        WriteLock::acquire(&path.with_extension("lock"), timeout)
    }

    /// Moves the database at `path` and its WAL files into `unreadable/<unix millis>/` beside it. For a store whose
    /// key is gone: nothing can open it any more, but it is kept rather than deleted in case the key turns up. The WAL
    /// files go first, so a new database never starts next to an old one's log.
    ///
    /// A marker beside the database goes down before anything moves, for [`Store::reset_pending`]: the move is then
    /// reported by whichever open succeeds next, even when the one that moved the files fails after it.
    pub fn set_aside(path: &Path) -> Result<(), StoreError> {
        let files: Vec<PathBuf> =
            ["-wal", "-shm", ""].into_iter().map(|suffix| suffixed(path, suffix)).filter(|f| f.exists()).collect();
        if files.is_empty() {
            return Ok(());
        }
        std::fs::write(path.with_extension("reset"), b"").map_err(StoreError::SetAside)?;
        let millis = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis();
        let dir = path.with_file_name("unreadable").join(millis.to_string());
        std::fs::create_dir_all(&dir).map_err(StoreError::SetAside)?;
        for file in files {
            let name = file.file_name().expect("a path built from the database file");
            std::fs::rename(&file, dir.join(name)).map_err(StoreError::SetAside)?;
        }
        Ok(())
    }

    /// Whether [`Store::set_aside`] moved a database that no open has reported yet.
    pub fn reset_pending(path: &Path) -> bool {
        path.with_extension("reset").exists()
    }

    /// Clears the marker of [`Store::set_aside`], once the move is reported.
    pub fn clear_reset(path: &Path) -> Result<(), StoreError> {
        match std::fs::remove_file(path.with_extension("reset")) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(StoreError::SetAside(e)),
            _ => Ok(()),
        }
    }
}

/// `geunal.db` with SQLite's suffix for its WAL (`-wal`) or shared memory (`-shm`).
fn suffixed(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.as_os_str().to_owned();
    name.push(suffix);
    PathBuf::from(name)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut out, byte| {
        let _ = write!(out, "{byte:02x}");
        out
    })
}
