-- The server never holds calendar or chat content: group payloads are MLS ciphertext or state blobs sealed with an MLS-exported key.
CREATE TABLE users (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kakao_sub         text UNIQUE,
  apple_sub         text UNIQUE,
  display_name      text NOT NULL,
  age_confirmed_at  timestamptz NOT NULL,        -- self-declared "14세 이상"; no birth date is stored
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE devices (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform       text NOT NULL CHECK (platform IN ('ios', 'android')),
  signature_key  bytea NOT NULL CHECK (octet_length(signature_key) = 32),   -- MLS Ed25519 public key
  push_token     text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  revoked_at     timestamptz
);
-- MVP: one active device per account; registering a new one revokes the old one.
CREATE UNIQUE INDEX one_active_device_per_user ON devices (user_id) WHERE revoked_at IS NULL;

-- Refresh tokens are stored as SHA-256 only and rotated on every use.
CREATE TABLE sessions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id     uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  refresh_hash  bytea NOT NULL UNIQUE,
  expires_at    timestamptz NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- MLS KeyPackages, consumed one at a time when someone adds this device to a group.
CREATE TABLE key_packages (
  id          bigserial PRIMARY KEY,
  device_id   uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  data        bytea NOT NULL CHECK (octet_length(data) <= 1024),
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON key_packages (device_id, id);

-- Account master key wrapped by the recovery-code key. Server holds salt + ciphertext only.
CREATE TABLE recovery (
  user_id      uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  kdf_salt     bytea NOT NULL CHECK (octet_length(kdf_salt) = 16),
  amk_wrapped  bytea NOT NULL,
  updated_at   timestamptz NOT NULL DEFAULT now()
);

-- AMK-encrypted archives (calendar + chat text) live in object storage; this is the index. Keep the newest three.
CREATE TABLE backups (
  id          bigserial PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  object_key  text NOT NULL UNIQUE,
  size_bytes  bigint NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TYPE group_kind AS ENUM ('circle', 'dm');
CREATE TABLE groups (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind        group_kind NOT NULL DEFAULT 'circle',
  epoch       bigint NOT NULL DEFAULT 0,      -- advanced only by an accepted commit
  next_seq    bigint NOT NULL DEFAULT 1,      -- the one order every device applies the log in
  group_info  bytea,                          -- latest MLS GroupInfo (public), for a member's own device to rejoin by external commit
  created_by  uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TYPE member_role   AS ENUM ('owner', 'member');
CREATE TYPE member_status AS ENUM ('pending', 'active', 'removed');
CREATE TABLE group_members (
  group_id     uuid REFERENCES groups(id) ON DELETE CASCADE,
  user_id      uuid REFERENCES users(id) ON DELETE CASCADE,
  role         member_role NOT NULL DEFAULT 'member',
  status       member_status NOT NULL DEFAULT 'pending',
  muted_until  timestamptz,                   -- 알림 끄기: the server skips alert pushes until then
  notify_chat  boolean NOT NULL DEFAULT true, -- per-category switches; messages carry cat = chat | plan
  notify_plan  boolean NOT NULL DEFAULT true,
  joined_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, user_id)
);
CREATE UNIQUE INDEX one_active_owner_per_group ON group_members (group_id) WHERE role = 'owner' AND status = 'active';

-- A 1:1 chat starts only between members who share a circle; one per pair.
CREATE TABLE dm_pairs (
  group_id  uuid PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
  user_lo   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  user_hi   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  CHECK (user_lo < user_hi),
  UNIQUE (user_lo, user_hi)
);

CREATE TABLE blocks (
  blocker_id  uuid REFERENCES users(id) ON DELETE CASCADE,
  blocked_id  uuid REFERENCES users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (blocker_id, blocked_id),
  CHECK (blocker_id <> blocked_id)
);

CREATE TABLE group_invites (
  code        text PRIMARY KEY CHECK (length(code) >= 10),   -- base32, at least 50 bits
  group_id    uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  created_by  uuid REFERENCES users(id) ON DELETE SET NULL,
  expires_at  timestamptz NOT NULL,
  max_uses    int NOT NULL DEFAULT 20,
  uses        int NOT NULL DEFAULT 0 CHECK (uses <= max_uses)
);

-- Mailbox: MLS commits and application messages in group order, kept 30 days and dropped a day-partition at a time.
CREATE TYPE log_kind AS ENUM ('commit', 'app');
CREATE TABLE group_log (
  group_id     uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  seq          bigint NOT NULL,
  epoch        bigint NOT NULL,
  kind         log_kind NOT NULL,
  author_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,   -- account deletion removes what they wrote
  ciphertext   bytea NOT NULL CHECK (octet_length(ciphertext) <= 65536),
  received_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, seq, received_at)
) PARTITION BY RANGE (received_at);
-- A daily job creates tomorrow's partition and drops the one older than 30 days:
-- CREATE TABLE group_log_YYYYMMDD PARTITION OF group_log FOR VALUES FROM ('YYYY-MM-DD') TO ('YYYY-MM-DD'+1);

-- Welcome messages are addressed to one new device and deleted once fetched.
CREATE TABLE welcomes (
  id          bigserial PRIMARY KEY,
  device_id   uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  group_id    uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  data        bytea NOT NULL CHECK (octet_length(data) <= 65536),
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- Latest state per (group, kind, owner), sealed with the epoch's exported key. Overwritten, never appended.
CREATE TYPE state_kind AS ENUM ('availability', 'group_meta', 'member_profile', 'newcomer_bundle');
CREATE TABLE group_state (
  group_id    uuid REFERENCES groups(id) ON DELETE CASCADE,
  kind        state_kind NOT NULL,
  owner_id    uuid REFERENCES users(id) ON DELETE CASCADE,
  epoch       bigint NOT NULL,
  ciphertext  bytea NOT NULL CHECK (octet_length(ciphertext) <= 16384),
  version     bigint NOT NULL DEFAULT 1,      -- bumped on every overwrite; group_meta writers compare it
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, kind, owner_id)
);
-- group_meta is shared: any member may write it, so a group keeps exactly one row whatever its owner_id.
CREATE UNIQUE INDEX one_group_meta ON group_state (group_id) WHERE kind = 'group_meta';

-- Encrypted files in object storage, one bucket per scope. A nightly job removes rows past expires_at and queues
-- their objects; bucket lifecycle rules (chat 31 days, appt and personal 366 days) catch anything the job missed.
CREATE TYPE media_scope AS ENUM ('chat', 'appt', 'personal', 'cover', 'avatar');
CREATE TABLE media_objects (
  object_key   text PRIMARY KEY,
  scope        media_scope NOT NULL,
  group_id     uuid REFERENCES groups(id) ON DELETE CASCADE,   -- NULL only for personal attachments
  uploader_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  size_bytes   bigint NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz,                                     -- NULL only for the current cover or avatar
  CHECK ((scope = 'personal') = (group_id IS NULL)),
  CHECK ((scope IN ('cover', 'avatar')) OR expires_at IS NOT NULL),
  -- 10 MB per file is TimeTree's limit; a paid tier with larger files would change this in its own migration.
  CHECK (size_bytes <= CASE scope WHEN 'cover' THEN 1024 * 1024 WHEN 'avatar' THEN 256 * 1024 ELSE 10 * 1024 * 1024 END)
);
-- One current cover per group and one current avatar per member; a replaced one gets expires_at = now() + 7 days.
CREATE UNIQUE INDEX one_current_cover ON media_objects (group_id) WHERE scope = 'cover' AND expires_at IS NULL;
CREATE UNIQUE INDEX one_current_avatar ON media_objects (group_id, uploader_id) WHERE scope = 'avatar' AND expires_at IS NULL;
CREATE INDEX media_expiry ON media_objects (expires_at) WHERE expires_at IS NOT NULL;

-- Paid plan, mirrored from the stores. Entitlement comes only from verified store data, never from the app.
CREATE TYPE store_kind AS ENUM ('app_store', 'play');
CREATE TABLE subscriptions (
  user_id          uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  store            store_kind NOT NULL,
  product_id       text NOT NULL,
  original_txn_id  text NOT NULL UNIQUE,      -- App Store originalTransactionId or Play purchase token
  expires_at       timestamptz NOT NULL,
  auto_renew       boolean NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now()
);

-- Blobs to delete from object storage, queued in the same transaction that deletes their rows (account deletion, pruning).
CREATE TABLE object_deletions (
  object_key   text PRIMARY KEY,
  enqueued_at  timestamptz NOT NULL DEFAULT now()
);

-- The only plaintext content the operator ever receives: what a reporter chose to decrypt and submit.
CREATE TABLE reports (
  id           bigserial PRIMARY KEY,
  reporter_id  uuid REFERENCES users(id) ON DELETE SET NULL,
  reported_id  uuid REFERENCES users(id) ON DELETE SET NULL,
  group_id     uuid REFERENCES groups(id) ON DELETE SET NULL,
  evidence     bytea NOT NULL,             -- sealed to the operator's review key at rest
  status       text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'actioned', 'dismissed')),
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TYPE funnel_kind AS ENUM ('request_sent', 'first_accept', 'held_day_passed', 'unaccepted_day_passed', 'cancelled', 'chat_active_day');
CREATE TABLE funnel_events (
  group_id  uuid REFERENCES groups(id) ON DELETE CASCADE,
  ref       text NOT NULL,
  kind      funnel_kind NOT NULL,
  at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, ref, kind)
);

-- Alpha builds only: opted-in testers' daily counts behind the M1 gate. A migration drops it before the public beta.
CREATE TABLE alpha_stats (
  user_id    uuid REFERENCES users(id) ON DELETE CASCADE,
  day        date NOT NULL,
  events     integer NOT NULL CHECK (events >= 0),
  suggested  integer NOT NULL CHECK (suggested >= 0 AND suggested <= events),
  PRIMARY KEY (user_id, day)
);

CREATE TABLE holidays (
  day            date NOT NULL,
  name           text NOT NULL,
  is_substitute  boolean NOT NULL DEFAULT false,
  PRIMARY KEY (day, name)                  -- 2025-05-05 is both 어린이날 and 부처님오신날
);
