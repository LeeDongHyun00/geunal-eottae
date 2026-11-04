//! Chat search (M3). The user put chat on hold, so this stays apart from the group calendar code; the index itself is
//! kept, since devices already have the table (migration 0001) and the engine fills it as texts arrive.

use rusqlite::params;

use crate::{Store, StoreError};

type Result<T> = std::result::Result<T, StoreError>;

impl Store {
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
