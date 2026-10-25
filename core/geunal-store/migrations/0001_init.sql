-- core/geunal-store/migrations/0001_init.sql — the device's own store, the only place content exists in plaintext.
-- MLS group state lives in the same file in tables owned by mls-rs-provider-sqlite.
CREATE TABLE calendars (
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  color        TEXT NOT NULL,
  kind         TEXT NOT NULL CHECK (kind IN ('personal', 'routine', 'device_mirror', 'group')),
  blocks_free  INTEGER NOT NULL DEFAULT 1,   -- 0 = soft: I see it, but groups see the time as free
  group_id     TEXT                          -- set when kind = 'group'
);

CREATE TABLE events (
  id               TEXT PRIMARY KEY,
  calendar_id      TEXT NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  title            TEXT NOT NULL,
  starts_at        TEXT NOT NULL,            -- ISO 8601 with offset
  ends_at          TEXT NOT NULL,
  all_day          INTEGER NOT NULL DEFAULT 0,
  tz               TEXT NOT NULL DEFAULT 'Asia/Seoul',
  rrule            TEXT,                     -- RFC 5545, e.g. FREQ=WEEKLY;BYDAY=TU,TH
  exdates          TEXT,                     -- JSON array of ISO timestamps
  note             TEXT,
  device_event_id  TEXT,                     -- set for device_mirror rows
  group_ref        TEXT,                     -- set for group appointments: the appt id in the group log
  tentative        INTEGER NOT NULL DEFAULT 0, -- 1 while my own request has no accept yet
  updated_at       TEXT NOT NULL,
  CHECK (ends_at > starts_at)
);
CREATE INDEX events_by_start ON events (starts_at);

-- One occurrence of a recurring event moved, edited or cancelled.
CREATE TABLE event_overrides (
  event_id       TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  occurrence_at  TEXT NOT NULL,
  starts_at      TEXT,
  ends_at        TEXT,
  title          TEXT,
  cancelled      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (event_id, occurrence_at)
);

CREATE TABLE reminders (
  event_id        TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  offset_minutes  INTEGER NOT NULL,
  PRIMARY KEY (event_id, offset_minutes)
);

-- 아르바이트: "오픈 07-13", "마감 17-23" tapped onto days. Templates are input UX; the result is ordinary events.
CREATE TABLE shift_templates (
  id        TEXT PRIMARY KEY,
  name      TEXT NOT NULL,
  start_hm  TEXT NOT NULL,                   -- '17:00'
  end_hm    TEXT NOT NULL,                   -- '23:00'; an end before start means past midnight
  color     TEXT NOT NULL
);

-- People I care about: birthdays drive "OO 생일 약속" suggestions.
CREATE TABLE people (
  id             TEXT PRIMARY KEY,
  name           TEXT NOT NULL,
  birthday_md    TEXT,                       -- 'MM-DD'
  is_lunar       INTEGER NOT NULL DEFAULT 0, -- converted to this year's solar date before suggesting
  source         TEXT NOT NULL CHECK (source IN ('manual', 'contacts', 'group')),
  group_user_id  TEXT                        -- set when source = 'group' (a member who opted in)
);

-- On-device counters behind the suggestion ranking. Never uploaded.
CREATE TABLE suggestion_stats (
  title         TEXT NOT NULL,
  weekday       INTEGER NOT NULL,            -- 0 = Sunday
  start_hm      TEXT NOT NULL,
  uses          INTEGER NOT NULL DEFAULT 1,
  last_used_at  TEXT NOT NULL,
  PRIMARY KEY (title, weekday)
);

CREATE TABLE groups (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL CHECK (kind IN ('circle', 'dm')),
  name         TEXT,                         -- from the group_meta state blob
  meta         BLOB,                         -- decrypted group_meta: color, cover, date-setting defaults
  cursor       INTEGER NOT NULL DEFAULT 0,   -- last seq applied
  muted_until  TEXT,
  notify_chat  INTEGER NOT NULL DEFAULT 1,   -- mirrored to the server, which filters pushes by category
  notify_plan  INTEGER NOT NULL DEFAULT 1,
  event_color  TEXT,                         -- my color for this group's appointments; NULL means the group color
  pinned       INTEGER NOT NULL DEFAULT 0
);

-- What I share with each group: a rolling window and which calendars count. Never titles.
CREATE TABLE group_shares (
  group_id          TEXT PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
  window_days       INTEGER NOT NULL DEFAULT 56 CHECK (window_days BETWEEN 14 AND 91),  -- rolling from today
  calendar_ids      TEXT NOT NULL,           -- JSON array
  paused            INTEGER NOT NULL DEFAULT 0,
  share_birthday    INTEGER NOT NULL DEFAULT 0,
  last_uploaded_at  TEXT
);

-- Decrypted log entries in seq order: chat and date-setting entries share one timeline.
CREATE TABLE entries (
  group_id     TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  seq          INTEGER NOT NULL,
  author_id    TEXT NOT NULL,
  kind         TEXT NOT NULL,                -- Entry tag: text, media, request, response, poll_vote, ...
  body         BLOB NOT NULL,                -- CBOR envelope as received; unknown kinds are kept and shown as "업데이트 필요"
  received_at  TEXT NOT NULL,                -- server time, the clock for vote and expiry rules
  PRIMARY KEY (group_id, seq)
);

-- Korean keeps particles attached ("토요일에"), so search uses trigram; queries under three characters fall back to LIKE.
CREATE VIRTUAL TABLE chat_fts USING fts5(body, group_id UNINDEXED, seq UNINDEXED, tokenize = 'trigram');

-- Members' latest availability, decrypted, for the group calendar.
CREATE TABLE availability (
  group_id      TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id       TEXT NOT NULL,
  window_start  TEXT NOT NULL,
  days          INTEGER NOT NULL,
  bits          BLOB NOT NULL,
  epoch         INTEGER NOT NULL,
  PRIMARY KEY (group_id, user_id)
);

-- Exported state keys, kept 30 days and at most 50 per group, to read blobs their owners have not re-sealed yet.
CREATE TABLE state_keys (
  group_id    TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  epoch       INTEGER NOT NULL,
  key         BLOB NOT NULL CHECK (length(key) = 32),
  created_at  TEXT NOT NULL,
  PRIMARY KEY (group_id, epoch)
);

-- Writes waiting for the network, sent in order per group.
CREATE TABLE outbox (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  group_id    TEXT NOT NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('commit', 'app', 'state', 'media')),
  payload     BLOB NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT NOT NULL
);

CREATE TABLE media_cache (
  object_key  TEXT PRIMARY KEY,
  group_id    TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  path        TEXT NOT NULL,                 -- decrypted file inside the app container
  size        INTEGER NOT NULL
);

-- Files on my own events. The ciphertext sits in object storage so a restored device can fetch it again;
-- the keys exist only here and in my backup. Deleting an event first queues DELETE /media/{object} in the outbox.
CREATE TABLE event_attachments (
  id          TEXT PRIMARY KEY,
  event_id    TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  object_key  TEXT NOT NULL UNIQUE,
  key         BLOB NOT NULL CHECK (length(key) = 32),
  name        TEXT NOT NULL,
  mime        TEXT NOT NULL,
  size        INTEGER NOT NULL CHECK (size <= 10485760),  -- 10 MB, TimeTree's limit; at most 3 per event, enforced in code
  sha256      BLOB NOT NULL,
  path        TEXT                           -- decrypted copy; NULL until downloaded again after a restore
);
