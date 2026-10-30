-- M1 sign-in (protocol §10, §13): Kakao, Apple and guest accounts, device replacement by ticket, a guest's return by
-- recovery code, and refresh-token reuse detection. No deployment holds M1 data yet; the local stack's stub accounts
-- are carried over.

-- A Kakao or Apple account behind one of ours. The row outlives a cut link (unlinked_at) so that signing in with the same
-- provider account finds the original account again; deleting the account deletes its rows. `stub` is the identity
-- POST /auth/stub asserts on local and CI stacks; it counts as no provider wherever the account kind matters.
CREATE TYPE provider_kind AS ENUM ('kakao', 'apple', 'stub');
CREATE TABLE provider_links (
  provider     provider_kind NOT NULL,
  sub          text NOT NULL,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  linked_at    timestamptz NOT NULL DEFAULT now(),
  unlinked_at  timestamptz,                        -- the provider said the user cut the link; signing in again clears it
  PRIMARY KEY (provider, sub),
  UNIQUE (user_id, provider)                       -- an account links one Kakao and one Apple account at most
);

INSERT INTO provider_links (provider, sub, user_id, linked_at)
SELECT 'stub', substr(kakao_sub, 6), id, created_at FROM users WHERE kakao_sub LIKE 'stub:%';
INSERT INTO provider_links (provider, sub, user_id, linked_at)
SELECT 'kakao', kakao_sub, id, created_at FROM users WHERE kakao_sub IS NOT NULL AND kakao_sub NOT LIKE 'stub:%';
INSERT INTO provider_links (provider, sub, user_id, linked_at)
SELECT 'apple', apple_sub, id, created_at FROM users WHERE apple_sub IS NOT NULL;

-- A guest has no provider at all, so the subjects leave the account row.
ALTER TABLE users DROP COLUMN kakao_sub, DROP COLUMN apple_sub;

-- provider_auth_at: when a Kakao, Apple or stub sign-in (or the replacement its ticket finished) last gave this device a
-- session. "새로 시작" without the recovery code is allowed for 30 minutes after it; refreshing does not move it.
-- replaced_via: how the sign-in that revoked this device proved itself, for the old device's 403 device_revoked.
ALTER TABLE devices
  ADD COLUMN provider_auth_at timestamptz,
  ADD COLUMN replaced_via text CHECK (replaced_via IN ('provider', 'recovery'));

-- A rotated refresh token stays as spent until it would have expired: presenting it again ends every session of the
-- device (reuse detection).
ALTER TABLE sessions ADD COLUMN spent_at timestamptz;
CREATE INDEX ON sessions (device_id);
CREATE INDEX ON sessions (expires_at);

-- A sign-in that found another active device: nothing is registered until POST /auth/replace brings the ticket back,
-- signed by the key the sign-in proved. Only the ticket's SHA-256 is stored, so the table cannot be replayed.
CREATE TABLE replace_tickets (
  ticket_hash    bytea PRIMARY KEY CHECK (octet_length(ticket_hash) = 32),
  user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform       text NOT NULL CHECK (platform IN ('ios', 'android')),
  signature_key  bytea NOT NULL CHECK (octet_length(signature_key) = 32),
  expires_at     timestamptz NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON replace_tickets (expires_at);

-- Recovery material (protocol §10): everything the server holds derives from the code by one Argon2id with a fixed
-- salt, so the per-account salt goes. recovery_id finds a guest's account on POST /auth/recover; the public key checks
-- the recovery signatures. M0 never wrote this table, so the new NOT NULL columns need no default.
ALTER TABLE recovery
  DROP COLUMN kdf_salt,
  ADD COLUMN recovery_id bytea NOT NULL UNIQUE CHECK (octet_length(recovery_id) = 16),
  ADD COLUMN recovery_public_key bytea NOT NULL CHECK (octet_length(recovery_public_key) = 32),
  ADD CONSTRAINT amk_wrapped_length CHECK (octet_length(amk_wrapped) = 72);
