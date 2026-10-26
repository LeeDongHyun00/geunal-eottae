# API 서버

계정, 그룹, MLS 전달 서비스, 저장, 푸시를 맡는 서버입니다. 내용은 읽지 못하고, 순서를 매기고 보관하고 전달합니다. 연결 유지는 [실시간 게이트웨이](gateway.md)가 하고, 형식과 규칙은 [공통 계약](protocol.md)을 따릅니다.

## 1. 맡는 일

- **계정과 기기:** 카카오·애플·게스트 로그인, 세션, 기기 등록(계정당 활성 1대), 키 패키지 보관
- **그룹:** 생성, 초대 코드, 멤버 상태, 1:1 생성 규칙, 차단, 알림 설정(전체 끄기, 채팅·약속별), 방장 승계, 커버·프로필 사진 자리
- **전달 서비스:**
  - 커밋 선점(epoch당 하나), 메시지 순번 부여, 오래된 epoch 거절
  - Welcome, GroupInfo, 외부 가입 허가
  - 30일 우편함
- **상태 블롭:** 덮어쓰기 저장(epoch가 뒤로 가지 않게)
- **미디어·백업:** 범위별(채팅·약속·개인 일정·커버·프로필 사진) 서명된 업로드·다운로드 URL, 보관 기간 정리, 파일 크기 한도(10MB)
- **구독:** 스토어 거래 검증, App Store 서버 알림 v2와 Play 실시간 개발자 알림으로 구독 상태 맞추기, 혜택 확인
- **알림:** 게이트웨이 신호(Redis), APNs·FCM 직접 발송
- **그 밖:** 신고 접수, 퍼널 마커, 공휴일 동기화, 계정 삭제

## 2. 기술

| 영역 | 선택 |
| --- | --- |
| 언어·런타임 | Kotlin, JDK 21, 가상 스레드(`spring.threads.virtual.enabled=true`) |
| 프레임워크 | Spring Boot 최신 안정판, Spring Web MVC, Spring Security(자체 JWT 리소스 서버) |
| DB 접근 | `JdbcClient` + SQL. PostgreSQL 기능(파티션, 조건부 갱신, upsert)을 그대로 씀 |
| 마이그레이션 | Flyway(SQL 파일) |
| Redis | Spring Data Redis(Lettuce): 게이트웨이 신호 PUBLISH, 레이트 리밋 |
| 토큰 | Nimbus JOSE: 액세스 JWT(ES256) 발급, JWKS 공개 |
| 푸시 | APNs(HTTP/2, 토큰 인증), FCM HTTP v1 |
| 오브젝트 스토리지 | S3 호환 SDK의 서명된 URL |
| 테스트 | JUnit 5, Testcontainers(PostgreSQL, Redis, SeaweedFS의 S3) |

## 3. 패키지 구조

```
server/api/src/main/kotlin/app/geunal/api/
  auth/        # Kakao and Apple token verification, guest, sessions, JWT issuing, JWKS endpoint
  devices/     # registration with signature proof, push tokens, key packages
  groups/      # groups, invites, members, owner succession, DMs, blocks, mute
  delivery/    # commits, messages, log fetch, welcomes, group info, external join, state blobs
  mls/         # MLS header reader (version, wire format, group id, epoch), checked against core vectors
  media/       # presigned URLs, media rows, object deletions
  backups/     # recovery blob, backup index, pruning
  push/        # recipients, APNs and FCM senders, invalid token cleanup
  realtime/    # Redis PUBLISH to d:{device_id} after commit
  reports/     # report intake
  ops/         # partitions, cleanup jobs, holidays sync, funnel markers
  common/      # error model (protocol §11), rate limits, logging rules
```

## 4. 인증과 기기

로그인 방법, 결과, 기기 교체, 게스트 복귀, 계정 삭제의 규칙은 [공통 계약](protocol.md) 13장입니다.

- **카카오·애플 공통:** 토큰의 `nonce` 클레임은 `hex(SHA-256("geunal login v1" ‖ nonce ‖ signature_key))`여야 합니다. 서버가 요청의 `nonce`와 `signature_key`로 계산해 비교합니다. 제공자 토큰을 이 기기 키에 묶으려는 것입니다.
- **카카오:** OIDC ID 토큰을 카카오 JWKS로 검증하고 `sub`을 씁니다. `aud`는 앱 키입니다. 동의 항목은 닉네임까지만 받습니다.
- **애플:** 아이덴티티 토큰을 애플 JWKS로 검증합니다. `aud`는 iOS 번들 ID와 안드로이드 웹 로그인용 서비스 ID입니다.
  - Apple 토큰은 저장하지 않습니다. 계정 삭제 요청에 앱이 새로 받은 `apple_authorization_code`를 넣고, 서버가 그 자리에서 교환하고 철회합니다.
- **게스트:** 소셜 계정 없이 계정을 만듭니다. 다른 기기에서는 복구 코드로만 돌아옵니다(`POST /auth/recover`, 복구 공개키로 서명 확인). 나중에 카카오·애플 계정에 연결할 수 있고, 연결하면 복구 코드만으로는 들어올 수 없습니다.
- **제공자 연결 끊김:** 카카오 연결 끊기 알림과 Apple의 동의 철회 알림을 받는 엔드포인트를 둡니다.
  - 보낸 쪽을 확인합니다. 카카오는 요청 헤더의 앱 어드민 키, Apple은 JWKS로 확인한 JWT 서명과 `iss`·`aud`입니다. 확인되지 않으면 아무것도 바꾸지 않습니다.
  - 받으면 연결 행을 지우지 않고 끊긴 시각을 적습니다. 그동안 계정은 복구 코드로 돌아올 수 있고, 같은 제공자 계정으로 다시 로그인하면 원래 계정으로 돌아옵니다.
- **기기 등록:** 로그인 요청에 기기의 Ed25519 공개키와, 서버가 준 일회용 nonce(`GET /auth/nonce`)에 대한 서명을 함께 보냅니다.
  - 계정에 다른 활성 기기가 있으면 등록하지 않고 교체 티켓(30분, 128비트 이상의 불투명 난수, SHA-256만 저장)을 줍니다.
  - `POST /auth/replace`는 티켓과 `"geunal replace v1" ‖ 티켓`에 대한 기기 키의 서명을 받아 이 기기를 등록하고, 이전 활성 기기를 해제하고 `revoke` 신호를 보냅니다.
  - 게스트 복귀는 복구 코드로 이미 증명했으므로 티켓 없이 바로 교체합니다.
  - 기기 교체는 멤버 상태를 바꾸지 않습니다. 이전 기기가 있던 그룹의 다른 멤버 기기에 `membership`을 보냅니다.
- **토큰:**
  - 액세스 JWT(ES256)는 15분, 클레임은 `sub`(user_id), `did`(device_id)입니다.
  - 리프레시 토큰은 30일이고, 쓸 때마다 교체합니다. DB에는 SHA-256만 저장합니다.
  - 이미 교체된 리프레시 토큰이 다시 오면 그 기기의 세션을 모두 끊습니다(재사용 감지). 토큰이 샜을 때 피해를 줄이는 표준 방법입니다.
  - JWKS는 `/.well-known/jwks.json`에 공개하고, 키를 바꿀 때 `kid`로 겹쳐 둡니다.

## 5. 전달 서비스 규칙

- **MLS 헤더:** `mls/`의 Kotlin 파서가 version, wire_format, 그룹 ID(가변 길이 벡터), epoch만 읽어 요청과 대조합니다.
  - 커밋 엔드포인트는 공개 메시지(wire_format 1)만, 메시지 엔드포인트는 비공개 메시지(2)만 받습니다.
  - 파서는 Rust 코어가 만든 벡터로 CI에서 검증합니다. 같은 방식의 20줄짜리 파서로 mls-rs 출력을 읽어 확인해 두었습니다.
- **커밋 수락:**
  - 아래 쿼리 하나로 epoch를 선점하고 순번을 매기고 GroupInfo를 바꿉니다.
  - 같은 트랜잭션에서 Welcome을 저장하고, 추가된 멤버를 `active`로 바꿉니다.
  - 행이 돌아오지 않으면 409 `epoch_conflict`입니다.
- **외부 커밋:** 작성자가 그 그룹의 활성 멤버이고 요청한 기기가 현재 기기일 때만 받습니다.
- **메시지:**
  - 현재 epoch일 때만 순번을 받습니다. 아니면 409 `stale_epoch`입니다.
  - 1:1에서 받는 사람이 보낸 사람을 차단했으면 403 `blocked`입니다.
- **따라잡기:** `GET /groups/{id}/log?after=&limit=`로 순번 순서로 최대 500개를 줍니다. `after`가 보관 기간보다 오래되면 410 `cursor_expired`입니다.
- **커밋 뒤 신호:** 트랜잭션이 커밋된 뒤(`afterCommit`) 다음을 합니다.
  - 받을 기기마다 Redis `d:{device_id}`에 이벤트를 PUBLISH합니다.
  - 푸시는 가상 스레드에서 비동기로 보냅니다.
- **푸시 대상:**
  - 요청의 `alert`(`all`, 사용자 목록, `none`)를 따릅니다.
  - 보낸 사람 자신과 `muted_until`이 지나지 않은 멤버는 뺍니다.
  - 요청의 `cat`이 `chat`이면 `notify_chat`, `plan`이면 `notify_plan`이 꺼진 멤버를 뺍니다.
  - 무효 토큰 응답을 받으면 토큰을 지웁니다.

```sql
-- name: accept_commit — only for the epoch the server is at; no row back means 409 epoch_conflict: sync, then rebuild the commit.
WITH moved AS (
  UPDATE groups SET epoch = epoch + 1, next_seq = next_seq + 1, group_info = :group_info
  WHERE id = :group_id AND epoch = :epoch
  RETURNING next_seq - 1 AS seq, epoch - 1 AS epoch
)
INSERT INTO group_log (group_id, seq, epoch, kind, author_id, ciphertext)
SELECT :group_id, seq, epoch, 'commit', :author_id, :ciphertext FROM moved
RETURNING seq, epoch;

-- name: append_message — only in the current epoch; no row back means 409 stale_epoch: process the pending commit, re-encrypt, resend.
WITH s AS (
  UPDATE groups SET next_seq = next_seq + 1
  WHERE id = :group_id AND epoch = :epoch
  RETURNING next_seq - 1 AS seq
)
INSERT INTO group_log (group_id, seq, epoch, kind, author_id, ciphertext)
SELECT :group_id, seq, :epoch, 'app', :author_id, :ciphertext FROM s
RETURNING seq;

-- name: put_state — overwrite, but never with an older epoch. Not for group_meta.
INSERT INTO group_state (group_id, kind, owner_id, epoch, ciphertext)
VALUES (:group_id, :kind::state_kind, :owner_id, :epoch, :ciphertext)
ON CONFLICT (group_id, kind, owner_id) DO UPDATE
  SET epoch = EXCLUDED.epoch, ciphertext = EXCLUDED.ciphertext, updated_at = now()
  WHERE group_state.epoch <= EXCLUDED.epoch;

-- name: put_group_meta — one shared slot any active member may overwrite. base_version is the version the writer
-- read (0 when there was none); no row back means 409 state_conflict: re-read, re-apply the change, retry.
INSERT INTO group_state (group_id, kind, owner_id, epoch, ciphertext)
VALUES (:group_id, 'group_meta', :writer_id, :epoch, :ciphertext)
ON CONFLICT (group_id) WHERE kind = 'group_meta' DO UPDATE
  SET owner_id = EXCLUDED.owner_id, epoch = EXCLUDED.epoch, ciphertext = EXCLUDED.ciphertext,
      version = group_state.version + 1, updated_at = now()
  WHERE group_state.version = :base_version AND group_state.epoch <= EXCLUDED.epoch
RETURNING version;
```

## 6. 스키마

PostgreSQL 16에 확장 없이 적용됩니다. 아래 파일 그대로 실행해 확인했습니다.
- 같은 epoch 커밋 둘 중 하나만 수락
- 오래된 epoch 메시지 거절
- 상태가 epoch를 되돌리지 않음
- 1:1 중복 거부, 계정당 활성 기기 1대
- 계정 삭제 시 작성 항목 삭제, 파티션 통째 삭제

```sql
-- server/api/src/main/resources/db/migration/V1__init.sql
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
```

이후 변경은 같은 디렉터리의 다음 마이그레이션에 있습니다.

| 파일 | 바뀐 것 |
| --- | --- |
| `V2__welcome_seq.sql` | `welcomes.seq`: 그 기기를 넣은 Add 커밋의 순번. 새 멤버가 로그를 그 자리부터 읽습니다 |

## 7. 엔드포인트

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | `/auth/nonce` | 기기 등록 서명용 일회용 값 |
| POST | `/auth/kakao` | 카카오 로그인 + 기기 등록. 다른 활성 기기가 있으면 교체 티켓 |
| POST | `/auth/apple` | Apple 로그인 + 기기 등록. 다른 활성 기기가 있으면 교체 티켓 |
| POST | `/auth/apple/callback` | 안드로이드 Apple 웹 로그인의 form_post를 받아 App Links 주소로 앱에 넘기기 |
| POST | `/auth/guest` | 게스트 계정 + 기기 등록 |
| POST | `/auth/recover` | 게스트 복귀: 복구 ID와 복구 서명으로 로그인 + 기기 교체 |
| POST | `/auth/replace` | 교체 티켓으로 이 기기 등록, 이전 기기 해제 |
| POST | `/auth/stub` | `GEUNAL_AUTH_STUB`이 켜진 로컬·CI에서만: 제공자 토큰 대신 `subject` 문자열로 로그인 + 기기 등록(카카오와 같은 규칙). 꺼져 있으면 404 |
| POST | `/auth/refresh` | 토큰 갱신(리프레시 교체) |
| POST | `/auth/link` | 게스트를 카카오·애플에 연결 |
| GET | `/.well-known/jwks.json` | 게이트웨이가 쓰는 공개키 |
| GET | `/me` | 계정, 현재 기기, 남은 키 패키지 수, 연결한 로그인(`providers`), 복구 자료가 있는지 |
| DELETE | `/me` | 계정 삭제. 다시 증명이 필요함(게스트는 복구 서명, 카카오는 새 ID 토큰, Apple은 새 `authorization_code`로 그 자리에서 토큰 철회) |
| PUT | `/me/push-token` | 현재 기기 푸시 토큰 |
| POST | `/me/key-packages` | 키 패키지 묶음 올리기 |
| GET | `/users/{id}/devices` | 활성 기기 ID와 서명 공개키(추가 전 확인용) |
| POST | `/devices/{id}/key-packages/claim` | 그 기기를 추가하려고 키 패키지 하나 가져가기 |
| PUT, GET | `/me/recovery` | 래핑된 AMK, 복구 ID, 복구 공개키. 이미 있으면 지금 코드의 증명이 필요함. `new_key`는 제공자 로그인 30분 안이나 새 제공자 증명으로만 되고, 백업을 모두 지움 |
| POST, GET | `/me/backups` | 업로드 URL 발급, 완료된 백업 목록(최근 3개) |
| POST | `/me/backups/{id}/complete` | 올리기 완료, 오래된 백업 정리 |
| GET | `/me/backups/latest` | 다운로드 URL |
| GET | `/me/welcomes` | 내 기기에 온 Welcome과 그 Add 커밋의 `seq`(가져가면 삭제) |
| GET | `/me/groups` | 내 그룹과 1:1, 현재 epoch, 최신 순번 |
| POST | `/groups` | 그룹 생성(첫 GroupInfo 포함) |
| POST | `/groups/{id}/invites` | 초대 코드 발급 |
| POST | `/invites/{code}/join` | 초대 코드로 참여 요청(`pending`) |
| GET | `/groups/{id}/members` | 멤버, 역할, 상태 |
| DELETE | `/groups/{id}/members/{userId}` | 탈퇴·강퇴 |
| PATCH | `/groups/{id}/members/me` | 알림 설정(`muted_until`, `notify_chat`, `notify_plan`) |
| PUT | `/groups/{id}/cover` | 커버 업로드 URL(멤버 누구나). 이전 커버는 7일 뒤 삭제 |
| PUT | `/groups/{id}/members/me/avatar` | 그룹 프로필 사진 업로드 URL. 이전 사진은 7일 뒤 삭제 |
| POST | `/dms` | 1:1 만들기(같은 그룹 멤버, 차단 아님, 한 쌍에 하나) |
| POST, DELETE | `/blocks/{userId}` | 차단, 해제 |
| POST | `/groups/{id}/commits` | 커밋(+ GroupInfo, Welcome, 추가 대상, 외부 여부). 응답에 `seq`와 `received_at` |
| POST | `/groups/{id}/messages` | 애플리케이션 메시지(epoch, 암호문, `alert`, `cat`). 응답에 `seq`와 `received_at` |
| GET | `/groups/{id}/log` | 순번 이후 항목 |
| GET | `/groups/{id}/group-info` | 외부 가입용 GroupInfo |
| PUT | `/groups/{id}/group-info` | 현재 epoch의 GroupInfo 올리기. 그룹 ID는 `POST /groups`가 만들므로 epoch 0의 GroupInfo는 이걸로 올림 |
| PUT | `/groups/{id}/state/{kind}` | 내 상태 블롭 덮어쓰기. `group_meta`는 멤버 누구나, `base_version`을 함께 보내고 어긋나면 409 `state_conflict` |
| GET | `/groups/{id}/state` | 그룹의 상태 블롭 전부 |
| POST | `/groups/{id}/media` | 채팅·약속 첨부 업로드 URL과 `object` 키(`scope`: `chat`, `appt`) |
| POST | `/me/attachments` | 개인 일정 첨부 업로드 URL(1년 보관) |
| POST | `/me/purchases` | 앱이 받은 스토어 거래(App Store 서명 거래, Play 구매 토큰)를 검증해 구독 저장 |
| GET | `/me/entitlements` | 현재 혜택과 만료일 |
| POST | `/webhooks/app-store` | App Store 서버 알림 v2(서명 검증 후 반영) |
| POST | `/webhooks/play` | Play 실시간 개발자 알림(Pub/Sub 푸시, 토큰 검증 후 반영) |
| GET | `/media/{object}` | 다운로드 URL(그룹 범위는 멤버만, 개인 첨부는 본인만) |
| DELETE | `/media/{object}` | 올린 사람만. 행을 지우고 `object_deletions`에 넣기 |
| POST | `/reports` | 신고(운영자 검토 키로 봉인한 증거) |
| POST | `/groups/{id}/funnel` | 퍼널 마커 |
| POST | `/me/alpha-stats` | 알파 빌드 전용. 동의한 테스터 기기가 하루 한 번 숫자(전체 일정 수, 추천으로 만든 일정 수)만 보냄. 공개 빌드에서는 404 |
| GET | `/holidays?from_year&to_year` | 공휴일(해 단위, 한 번에 두 해까지, 로그인 없이) |

## 8. 정기 작업

| 작업 | 주기 | 하는 일 |
| --- | --- | --- |
| 로그 파티션 | 매일 | 내일 파티션 만들기, 30일 지난 파티션 삭제 |
| 미디어 정리 | 매일 | `expires_at`이 지난 `media_objects`(채팅 30일, 약속·개인 1년, 바뀐 커버·사진 7일)를 지우고 `object_deletions`에 넣기 |
| 구독 대조 | 매일 | 만료가 가까운 구독을 스토어 API로 다시 확인(알림을 놓친 경우 대비) |
| 오브젝트 삭제 | 10분 | `object_deletions`를 비우며 스토리지에서 삭제 |
| 백업 정리 | 업로드 완료 때 | 사람마다 최근 3개만 남기기 |
| Welcome 정리 | 매일 | 30일 지난 Welcome 삭제 |
| 공휴일 동기화 | 매일 | 특일 정보 API로 `holidays` 갱신(같은 날 공휴일 둘 허용) |
| 방장 승계 | 탈퇴·삭제 때 | 가장 오래된 활성 멤버를 방장으로 |

## 9. 레이트 리밋

| 대상 | 한도(초기값) |
| --- | --- |
| 로그인·nonce(게스트 복귀와 교체 포함) | IP당 시간당 30회 |
| 초대 코드 참여 | IP당 시간당 10회 |
| 메시지 | 사용자당 그룹마다 분당 60개 |
| 커밋 | 그룹당 분당 30개 |
| 키 패키지 가져가기 | 사용자당 분당 30개 |
| 미디어 업로드 | 사용자당 하루 200MB(10MB 파일 20개) |
| 백업 | 사용자당 하루 10회 |
| 공휴일(로그인 없이) | IP당 시간당 60회 |

초과하면 429와 `Retry-After`를 줍니다.

## 10. 로그·관측 규칙

- **로그에 남기지 않는 것:** 요청·응답 본문, 암호문, 토큰
- **로그에 남기는 것:** 경로, 상태 코드, 지연, 사용자·그룹 ID, 크기
- **접속 로그:** 로그인과 토큰 갱신 때마다 시각, 사용자·기기 ID, 접속 IP를 일반 로그와 따로 남기고 3개월 뒤 지웁니다(통신비밀보호법 시행령 제41조, [인프라](infra.md) 9장). IP는 접속 로그에만 남깁니다.
- **지표:** 엔드포인트별 지연과 오류율, 409·410 비율, 푸시 성공률, 게이트웨이 신호 수, 파티션 크기

## 11. 테스트

- **엔드포인트:** 모든 엔드포인트의 계약 테스트. Testcontainers로 진짜 PostgreSQL·Redis·S3(SeaweedFS)에 붙입니다.
- **동시성:**
  - 같은 epoch 커밋 50개를 동시에 보내 정확히 하나만 수락되는지
  - 메시지 1,000개를 동시에 보내 순번에 빈틈이나 중복이 없는지
- **MLS 헤더:** Rust 코어 벡터로 파서를 검증합니다.
- **계정 삭제:** 계정 종류마다 증명이 없거나 틀리면 403 `reauth_required`로 아무것도 지우지 않는지, 맞으면 기기·세션·멤버십·상태·미디어·백업·복구 자료가 지워지고 로그 항목과 자리표시자 계정 행은 남는지, 오브젝트 키가 `object_deletions`에 들어가는지 확인합니다.
- **그룹 메타:** 두 멤버가 같은 버전에서 동시에 쓰면 하나만 저장되고 다른 하나는 409를 받는지, 오래된 epoch로는 덮어쓰지 못하는지 확인합니다.
- **구독:** 서명이 틀린 App Store 알림과 검증되지 않은 Play 토큰을 거절하는지, 환불·만료 알림에 혜택이 꺼지는지 확인합니다.
- **차단과 1:1:** 같은 그룹이 아니면 1:1을 만들 수 없고, 차단하면 메시지가 거절되는지 확인합니다.
- **파티션 작업:** 31일 전 파티션이 지워지고 내일 파티션이 생기는지 확인합니다.

## 12. 완료 조건

| 단계 | 완료 조건 |
| --- | --- |
| M0 | 인증 스텁, 기기, 키 패키지, 그룹, 커밋·메시지·로그·Welcome·GroupInfo, 상태 블롭, Redis 신호. 헤드리스 종단 시험 통과 |
| M1 | 카카오·애플·게스트 로그인, 기기 교체와 게스트 복귀, 복구, 백업 URL, 계정 삭제, 접속 로그, 공휴일, 알파 통계, 알파 서버 배포 |
| M2 | 초대, 멤버 상태, 방장 승계, 푸시(APNs·FCM)와 종류별 알림 설정, 그룹 메타 공용 자리(버전 비교), 커버·프로필 사진, 퍼널 |
| M3 | 1:1, 차단, 범위별 첨부와 크기 한도, 신고, 동시성 테스트 |
| M4 | 구독 검증과 스토어 알림, 운영 레이트 리밋 조정, 보존 기간 문서화, 장애 대응 절차 |
