-- core/geunal-store/migrations/0003_alpha_sessions.sql — the two alpha counts only the app knows (protocol §14): how
-- many times it came to the foreground in a day, and how many crashes the OS reported for that day.
ALTER TABLE alpha_stats ADD COLUMN sessions INTEGER NOT NULL DEFAULT 0;
ALTER TABLE alpha_stats ADD COLUMN crashes INTEGER NOT NULL DEFAULT 0;
