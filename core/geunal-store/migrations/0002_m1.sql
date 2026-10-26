-- core/geunal-store/migrations/0002_m1.sql — what the M1 calendar keeps beyond the M0 schema (docs/design/m1.md):
-- calendar visibility and default alerts, per-event alerts, shift templates tied to a calendar, lunar birthdays,
-- settings, public holidays from the server, the alpha counts and this device's notes about its own backups.

-- Hidden calendars stay out of my views but still count for groups. Alerts are NULL (follow the next level), 'off',
-- or minutes before the start (negative: after it, for all-day items such as "당일 09:00").
ALTER TABLE calendars ADD COLUMN visible INTEGER NOT NULL DEFAULT 1;
ALTER TABLE calendars ADD COLUMN default_alert TEXT;
ALTER TABLE calendars ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
ALTER TABLE calendars ADD COLUMN device_source_id TEXT;    -- the device calendar a 'device_mirror' row stands for

ALTER TABLE events ADD COLUMN alert TEXT;
ALTER TABLE events ADD COLUMN shift_template_id TEXT;      -- the template that stamped it; cleared when the template goes
ALTER TABLE events ADD COLUMN created_at TEXT;             -- an undo right after the add takes back what the add counted
ALTER TABLE events ADD COLUMN origin TEXT;                 -- 'typed', 'suggestion', 'shift', 'device' or 'restore'
CREATE INDEX events_by_calendar ON events (calendar_id);
CREATE INDEX events_by_shift ON events (shift_template_id) WHERE shift_template_id IS NOT NULL;

ALTER TABLE shift_templates ADD COLUMN calendar_id TEXT;
ALTER TABLE shift_templates ADD COLUMN alert TEXT;
ALTER TABLE shift_templates ADD COLUMN tint INTEGER NOT NULL DEFAULT 3;   -- 1–5 of the calendar color
ALTER TABLE shift_templates ADD COLUMN position INTEGER NOT NULL DEFAULT 0;

ALTER TABLE people ADD COLUMN birth_year INTEGER;
ALTER TABLE people ADD COLUMN leap_month INTEGER NOT NULL DEFAULT 0;      -- a lunar birthday in a leap month (윤달)
ALTER TABLE people ADD COLUMN alert TEXT;
ALTER TABLE people ADD COLUMN suggest INTEGER NOT NULL DEFAULT 1;         -- "OO 생일 약속" chips
ALTER TABLE people ADD COLUMN contact_id TEXT;                            -- importing a contact again updates it
ALTER TABLE people ADD COLUMN group_id TEXT;                              -- with group_user_id: where it was made public

-- The last time and calendar a title was used on a weekday, so a chip can put it back as it was.
ALTER TABLE suggestion_stats ADD COLUMN end_hm TEXT;
ALTER TABLE suggestion_stats ADD COLUMN calendar_id TEXT;
ALTER TABLE suggestion_stats ADD COLUMN all_day INTEGER NOT NULL DEFAULT 0;
ALTER TABLE suggestion_stats ADD COLUMN hint_offered INTEGER NOT NULL DEFAULT 0;  -- "매주 반복으로 바꿀까요?" asked once

-- Small values that go with the device and into the backup: alert settings, switches, the recovery-code flag.
CREATE TABLE settings (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);

-- Public holidays of the years the server sent in full (protocol §14). A year missing here falls back to the table
-- built into the app.
CREATE TABLE holidays (
  day         TEXT NOT NULL,
  name        TEXT NOT NULL,
  substitute  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, name)
);
CREATE TABLE holiday_years (
  year        INTEGER PRIMARY KEY,
  updated_at  TEXT NOT NULL
);

-- Alpha builds only: events made on this device per Korean day, and how many of them came from a suggestion chip.
CREATE TABLE alpha_stats (
  day        TEXT PRIMARY KEY,
  events     INTEGER NOT NULL DEFAULT 0,
  suggested  INTEGER NOT NULL DEFAULT 0,
  sent_at    TEXT
);

-- What this device remembers about the backups it made; the server keeps only id, size and time (protocol §10).
CREATE TABLE backup_records (
  id          INTEGER PRIMARY KEY,
  created_at  TEXT NOT NULL,
  size        INTEGER NOT NULL,
  automatic   INTEGER NOT NULL,
  events      INTEGER NOT NULL,
  people      INTEGER NOT NULL
);
