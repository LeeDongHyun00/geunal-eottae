-- M1 recovery material, backups and account deletion (protocol §10, §13).

-- A backup row exists from POST /me/backups; completed_at is set once the object is checked, and only completed rows
-- are listed. An upload never completed is dropped after a day.
ALTER TABLE backups
  ADD COLUMN completed_at timestamptz,
  ADD CONSTRAINT backup_size CHECK (size_bytes BETWEEN 1 AND 67108864);
CREATE INDEX ON backups (user_id, completed_at);
CREATE INDEX ON backups (created_at) WHERE completed_at IS NULL;

-- Objects live in several buckets (backups now, the media buckets later), so a queued deletion names its bucket by the
-- short name infra.md §1 gives it. M0 never queued anything.
ALTER TABLE object_deletions ADD COLUMN bucket text NOT NULL;

-- A deleted account stays as a placeholder row so the log entries it wrote keep an author until their partitions are
-- dropped; its identifying fields (nickname, age confirmation) are cleared and deleted_at says when.
ALTER TABLE users
  ADD COLUMN deleted_at timestamptz,
  ALTER COLUMN display_name DROP NOT NULL,
  ALTER COLUMN age_confirmed_at DROP NOT NULL,
  ADD CONSTRAINT live_account_fields CHECK (deleted_at IS NOT NULL OR (display_name IS NOT NULL AND age_confirmed_at IS NOT NULL));

-- After a deletion the server unlinks Kakao and revokes the Apple tokens it exchanged the deletion's code for. A call
-- that fails stays here and is retried; the row, and with it the Apple token, goes as soon as a call succeeds.
CREATE TABLE provider_revocations (
  id               bigserial PRIMARY KEY,
  provider         provider_kind NOT NULL CHECK (provider IN ('kakao', 'apple')),
  target           text NOT NULL,                -- Kakao: the user's sub; Apple: the refresh (or access) token
  client_id        text,                         -- Apple: the client the token was issued to
  attempts         int NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON provider_revocations (next_attempt_at);

-- The refresh tokens of a deleted account's sessions, by SHA-256 and expiry only: until each would have expired it
-- answers 401 account_deleted, so a device that lost the 204 finishes the deletion (protocol §13). Nothing here leads
-- back to the account.
CREATE TABLE deleted_sessions (
  refresh_hash  bytea PRIMARY KEY,
  expires_at    timestamptz NOT NULL
);
