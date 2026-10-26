//! Row types and queries over the schema in `migrations/`. Every function takes `&Store` and returns plain rows; the
//! rules that interpret them live in geunal-domain and geunal-sync.

use rusqlite::{OptionalExtension, params};

use crate::{Store, StoreError};

type Result<T> = std::result::Result<T, StoreError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GroupKind {
    Circle,
    Dm,
}

impl GroupKind {
    fn as_str(self) -> &'static str {
        match self {
            GroupKind::Circle => "circle",
            GroupKind::Dm => "dm",
        }
    }

    fn parse(s: &str) -> Result<Self> {
        match s {
            "circle" => Ok(GroupKind::Circle),
            "dm" => Ok(GroupKind::Dm),
            other => Err(StoreError::Malformed(format!("group kind {other:?}"))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupRow {
    pub id: String,
    pub kind: GroupKind,
    pub name: Option<String>,
    /// Last seq applied (protocol §6).
    pub cursor: i64,
}

/// A decrypted log entry as received; `body` is the CBOR envelope so unknown kinds survive an upgrade (protocol §4).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryRow {
    pub group_id: String,
    /// The server's seq. Entries from a newcomer bundle have no seq and take negative numbers in bundle order, so one
    /// `ORDER BY seq` still yields the order the state function needs.
    pub seq: i64,
    pub author_id: String,
    pub kind: String,
    pub body: Vec<u8>,
    /// Server time, RFC 3339.
    pub received_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AvailabilityRow {
    pub group_id: String,
    pub user_id: String,
    pub window_start: String,
    pub days: i64,
    pub bits: Vec<u8>,
    pub epoch: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutboxKind {
    Commit,
    App,
    State,
    Media,
}

impl OutboxKind {
    fn as_str(self) -> &'static str {
        match self {
            OutboxKind::Commit => "commit",
            OutboxKind::App => "app",
            OutboxKind::State => "state",
            OutboxKind::Media => "media",
        }
    }

    fn parse(s: &str) -> Result<Self> {
        match s {
            "commit" => Ok(OutboxKind::Commit),
            "app" => Ok(OutboxKind::App),
            "state" => Ok(OutboxKind::State),
            "media" => Ok(OutboxKind::Media),
            other => Err(StoreError::Malformed(format!("outbox kind {other:?}"))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutboxRow {
    pub id: i64,
    pub group_id: String,
    pub kind: OutboxKind,
    pub payload: Vec<u8>,
    pub attempts: i64,
    pub created_at: String,
}

impl Store {
    // ----- groups -----

    /// Creates the group row or updates its kind and name, keeping the cursor.
    pub fn upsert_group(&self, id: &str, kind: GroupKind, name: Option<&str>) -> Result<()> {
        self.conn().execute(
            "INSERT INTO groups (id, kind, name) VALUES (?1, ?2, ?3)
             ON CONFLICT (id) DO UPDATE SET kind = excluded.kind, name = coalesce(excluded.name, groups.name)",
            params![id, kind.as_str(), name],
        )?;
        Ok(())
    }

    pub fn group(&self, id: &str) -> Result<Option<GroupRow>> {
        self.conn()
            .query_row("SELECT id, kind, name, cursor FROM groups WHERE id = ?1", [id], group_row)
            .optional()?
            .transpose()
    }

    pub fn groups(&self) -> Result<Vec<GroupRow>> {
        let conn = self.conn();
        let mut stmt = conn.prepare("SELECT id, kind, name, cursor FROM groups ORDER BY id")?;
        let rows = stmt.query_map([], group_row)?;
        rows.map(|r| r?).collect()
    }

    /// Removes the group and, through the foreign keys, its entries, availability, state keys and shares. The outbox
    /// has no foreign key, so its rows go separately.
    pub fn remove_group(&self, id: &str) -> Result<()> {
        self.conn().execute("DELETE FROM outbox WHERE group_id = ?1", [id])?;
        self.conn().execute("DELETE FROM chat_fts WHERE group_id = ?1", [id])?;
        self.conn().execute("DELETE FROM groups WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn cursor(&self, group_id: &str) -> Result<i64> {
        Ok(self
            .conn()
            .query_row("SELECT cursor FROM groups WHERE id = ?1", [group_id], |row| row.get(0))
            .optional()?
            .unwrap_or(0))
    }

    pub fn set_cursor(&self, group_id: &str, seq: i64) -> Result<()> {
        self.conn().execute("UPDATE groups SET cursor = ?2 WHERE id = ?1", params![group_id, seq])?;
        Ok(())
    }

    // ----- entries -----

    /// Stores a decrypted entry. The same (group, seq) twice is ignored: MLS cannot decrypt a message twice, so the
    /// first copy (possibly written by the notification extension) is the only one (rust-core §4).
    pub fn insert_entry(&self, row: &EntryRow) -> Result<bool> {
        let inserted = self.conn().execute(
            "INSERT OR IGNORE INTO entries (group_id, seq, author_id, kind, body, received_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![row.group_id, row.seq, row.author_id, row.kind, row.body, row.received_at],
        )?;
        Ok(inserted == 1)
    }

    /// Replaces the provisional `received_at` of an entry this device sent with the server's, once the log echoes it.
    pub fn set_entry_received_at(&self, group_id: &str, seq: i64, received_at: &str) -> Result<()> {
        self.conn().execute(
            "UPDATE entries SET received_at = ?3 WHERE group_id = ?1 AND seq = ?2",
            params![group_id, seq, received_at],
        )?;
        Ok(())
    }

    pub fn has_entry(&self, group_id: &str, seq: i64) -> Result<bool> {
        Ok(self
            .conn()
            .query_row("SELECT 1 FROM entries WHERE group_id = ?1 AND seq = ?2", params![group_id, seq], |_| Ok(()))
            .optional()?
            .is_some())
    }

    /// All entries of a group in log order: bundle entries (negative seq) first, then by server seq.
    pub fn entries(&self, group_id: &str) -> Result<Vec<EntryRow>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT group_id, seq, author_id, kind, body, received_at FROM entries WHERE group_id = ?1 ORDER BY seq",
        )?;
        let rows = stmt.query_map([group_id], entry_row)?;
        rows.map(|r| r.map_err(StoreError::from)).collect()
    }

    /// The seq for the next newcomer-bundle entry: one below the lowest so far, starting at -1. Bundle entries are
    /// inserted in bundle order, so lower numbers are earlier only when the caller counts downwards from the end.
    pub fn lowest_entry_seq(&self, group_id: &str) -> Result<i64> {
        Ok(self
            .conn()
            .query_row("SELECT min(seq) FROM entries WHERE group_id = ?1", [group_id], |row| {
                row.get::<_, Option<i64>>(0)
            })?
            .unwrap_or(0)
            .min(0))
    }

    // ----- availability -----

    pub fn put_availability(&self, row: &AvailabilityRow) -> Result<()> {
        self.conn().execute(
            "INSERT INTO availability (group_id, user_id, window_start, days, bits, epoch) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT (group_id, user_id) DO UPDATE SET window_start = excluded.window_start, days = excluded.days,
               bits = excluded.bits, epoch = excluded.epoch",
            params![row.group_id, row.user_id, row.window_start, row.days, row.bits, row.epoch],
        )?;
        Ok(())
    }

    pub fn remove_availability(&self, group_id: &str, user_id: &str) -> Result<()> {
        self.conn()
            .execute("DELETE FROM availability WHERE group_id = ?1 AND user_id = ?2", params![group_id, user_id])?;
        Ok(())
    }

    pub fn availability(&self, group_id: &str) -> Result<Vec<AvailabilityRow>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT group_id, user_id, window_start, days, bits, epoch FROM availability WHERE group_id = ?1 ORDER BY user_id",
        )?;
        let rows = stmt.query_map([group_id], |row| {
            Ok(AvailabilityRow {
                group_id: row.get(0)?,
                user_id: row.get(1)?,
                window_start: row.get(2)?,
                days: row.get(3)?,
                bits: row.get(4)?,
                epoch: row.get(5)?,
            })
        })?;
        rows.map(|r| r.map_err(StoreError::from)).collect()
    }

    // ----- state keys -----

    pub fn put_state_key(&self, group_id: &str, epoch: i64, key: &[u8; 32], created_at: &str) -> Result<()> {
        self.conn().execute(
            "INSERT OR REPLACE INTO state_keys (group_id, epoch, key, created_at) VALUES (?1, ?2, ?3, ?4)",
            params![group_id, epoch, key.as_slice(), created_at],
        )?;
        Ok(())
    }

    pub fn state_key(&self, group_id: &str, epoch: i64) -> Result<Option<[u8; 32]>> {
        let key: Option<Vec<u8>> = self
            .conn()
            .query_row(
                "SELECT key FROM state_keys WHERE group_id = ?1 AND epoch = ?2",
                params![group_id, epoch],
                |row| row.get(0),
            )
            .optional()?;
        key.map(|k| k.try_into().map_err(|_| StoreError::Malformed("state key length".into()))).transpose()
    }

    /// Drops keys older than 30 days (by `created_at`, RFC 3339 compared as text) and all but the newest 50 epochs
    /// (protocol §5).
    pub fn prune_state_keys(&self, group_id: &str, older_than: &str) -> Result<()> {
        self.conn()
            .execute("DELETE FROM state_keys WHERE group_id = ?1 AND created_at < ?2", params![group_id, older_than])?;
        self.conn().execute(
            "DELETE FROM state_keys WHERE group_id = ?1 AND epoch NOT IN
               (SELECT epoch FROM state_keys WHERE group_id = ?1 ORDER BY epoch DESC LIMIT 50)",
            [group_id],
        )?;
        Ok(())
    }

    pub fn state_key_count(&self, group_id: &str) -> Result<i64> {
        Ok(self
            .conn()
            .query_row("SELECT count(*) FROM state_keys WHERE group_id = ?1", [group_id], |row| row.get(0))?)
    }

    // ----- outbox -----

    pub fn enqueue(&self, group_id: &str, kind: OutboxKind, payload: &[u8], created_at: &str) -> Result<i64> {
        let conn = self.conn();
        conn.execute(
            "INSERT INTO outbox (group_id, kind, payload, created_at) VALUES (?1, ?2, ?3, ?4)",
            params![group_id, kind.as_str(), payload, created_at],
        )?;
        Ok(conn.last_insert_rowid())
    }

    /// The oldest waiting write of a group: writes go out in order per group (rust-core §5).
    pub fn outbox_head(&self, group_id: &str) -> Result<Option<OutboxRow>> {
        self.conn()
            .query_row(
                "SELECT id, group_id, kind, payload, attempts, created_at FROM outbox WHERE group_id = ?1 ORDER BY id LIMIT 1",
                [group_id],
                outbox_row,
            )
            .optional()?
            .transpose()
    }

    pub fn outbox(&self, group_id: &str) -> Result<Vec<OutboxRow>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT id, group_id, kind, payload, attempts, created_at FROM outbox WHERE group_id = ?1 ORDER BY id",
        )?;
        let rows = stmt.query_map([group_id], outbox_row)?;
        rows.map(|r| r?).collect()
    }

    /// Groups with something waiting, oldest first.
    pub fn outbox_groups(&self) -> Result<Vec<String>> {
        let conn = self.conn();
        let mut stmt = conn.prepare("SELECT group_id FROM outbox GROUP BY group_id ORDER BY min(id)")?;
        let rows = stmt.query_map([], |row| row.get(0))?;
        rows.map(|r| r.map_err(StoreError::from)).collect()
    }

    pub fn bump_attempts(&self, id: i64) -> Result<()> {
        self.conn().execute("UPDATE outbox SET attempts = attempts + 1 WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn dequeue(&self, id: i64) -> Result<()> {
        self.conn().execute("DELETE FROM outbox WHERE id = ?1", [id])?;
        Ok(())
    }

    /// Drops every waiting commit of a group: after an epoch conflict the intent is rebuilt from scratch (protocol §2).
    pub fn drop_outbox_commits(&self, group_id: &str) -> Result<()> {
        self.conn().execute("DELETE FROM outbox WHERE group_id = ?1 AND kind = 'commit'", [group_id])?;
        Ok(())
    }

    // ----- chat search -----

    /// Indexes a chat text for search. Korean keeps particles attached, so the index is trigram (rust-core §4).
    pub fn index_text(&self, group_id: &str, seq: i64, body: &str) -> Result<()> {
        self.conn()
            .execute("INSERT INTO chat_fts (body, group_id, seq) VALUES (?1, ?2, ?3)", params![body, group_id, seq])?;
        Ok(())
    }

    /// (group_id, seq) of chat texts containing `query`. Trigram needs three characters; shorter queries fall back to
    /// LIKE.
    pub fn search_text(&self, query: &str) -> Result<Vec<(String, i64)>> {
        let query = query.trim();
        if query.is_empty() {
            return Ok(Vec::new());
        }
        let (sql, arg) = if query.chars().count() >= 3 {
            (
                "SELECT group_id, seq FROM chat_fts WHERE chat_fts MATCH ?1 ORDER BY seq",
                format!("\"{}\"", query.replace('"', "\"\"")),
            )
        } else {
            ("SELECT group_id, seq FROM chat_fts WHERE body LIKE ?1 ORDER BY seq", format!("%{}%", like_escape(query)))
        };
        let conn = self.conn();
        let mut stmt = conn.prepare(sql)?;
        let rows = stmt.query_map([arg], |row| Ok((row.get(0)?, row.get(1)?)))?;
        rows.map(|r| r.map_err(StoreError::from)).collect()
    }
}

fn like_escape(s: &str) -> String {
    s.replace(['%', '_'], "")
}

fn group_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Result<GroupRow>> {
    let kind: String = row.get(1)?;
    let (id, name, cursor) = (row.get(0)?, row.get(2)?, row.get(3)?);
    Ok(GroupKind::parse(&kind).map(|kind| GroupRow { id, kind, name, cursor }))
}

fn entry_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<EntryRow> {
    Ok(EntryRow {
        group_id: row.get(0)?,
        seq: row.get(1)?,
        author_id: row.get(2)?,
        kind: row.get(3)?,
        body: row.get(4)?,
        received_at: row.get(5)?,
    })
}

fn outbox_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Result<OutboxRow>> {
    let kind: String = row.get(2)?;
    let (id, group_id, payload, attempts, created_at) =
        (row.get(0)?, row.get(1)?, row.get(3)?, row.get(4)?, row.get(5)?);
    Ok(OutboxKind::parse(&kind).map(|kind| OutboxRow { id, group_id, kind, payload, attempts, created_at }))
}
