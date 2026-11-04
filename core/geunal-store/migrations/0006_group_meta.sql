-- core/geunal-store/migrations/0006_group_meta.sql — the newest group_meta blob this device read or wrote, opened
-- (protocol §5), with the server's version a writer must name as base_version.
CREATE TABLE group_meta (
  group_id  TEXT PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
  version   INTEGER NOT NULL,
  epoch     INTEGER NOT NULL,
  owner_id  TEXT NOT NULL,
  plaintext BLOB NOT NULL
);
