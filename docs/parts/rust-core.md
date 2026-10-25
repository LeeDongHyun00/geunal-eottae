# Rust 코어

iOS와 안드로이드가 함께 쓰는 공유 코어입니다. 두 앱이 같은 결과를 내야 하는 것은 전부 여기에 있습니다. 앱은 화면과 OS 연동만 맡습니다.

## 1. 맡는 일

| 코어가 하는 일 | 앱이 하는 일 |
| --- | --- |
| 암호화, MLS, 키 유도 | 키체인·키스토어에 비밀 보관(코어가 콜백으로 요청) |
| 서버와의 동기화(HTTP, 실시간 채널), 재시도, 순서 | 네트워크 상태 알림, 백그라운드 작업 예약 |
| 로컬 저장소(SQLCipher), 검색 | 저장소 경로 제공(iOS는 App Group 컨테이너) |
| 캘린더 반복 전개, 30분 비트맵, 입력 추천 | 기기 캘린더·연락처 읽어서 넘기기 |
| 그룹 캘린더 합치기, 날짜 잡기 상태, 투표 집계, 시간·활동 추천 | 화면 그리기 |
| 채팅 타임라인, 미디어 암복호화 | 사진 선택, 파일 저장, 미리보기 |
| 푸시 복호화(`decrypt_push`) | 알림 확장(iOS)·푸시 핸들러(안드로이드)에서 호출 |

## 2. 크레이트 구성

```
core/
  Cargo.toml          # workspace
  geunal-crypto/      # AEAD, STREAM chunks, Argon2id, AMK, recovery words (BIP39)
  geunal-mls/         # mls-rs wrapper: client, key packages, commits, exporter state keys, external join
  geunal-store/       # SQLCipher via rusqlite, migrations, cross-process lock
  geunal-domain/      # entry, calendar (rrule), availability, appointments, suggestions, holidays, chat
  geunal-sync/        # HTTPS (reqwest + rustls), Phoenix channel client (tokio-tungstenite), outbox, cursors
  geunal-ffi/         # UniFFI facade: Core object, decrypt_push, listener and secret-store callbacks
  vectors/            # test vectors other parts read (protocol §12)
```

| 의존성 | 검증에 쓴 버전 | 쓰는 곳 |
| --- | --- | --- |
| mls-rs, mls-rs-crypto-rustcrypto | 0.56, 0.22 | MLS(RFC 9420), 암호 조합 0x0003 |
| mls-rs-provider-sqlite | 0.23 | MLS 상태 저장(기본 기능이 SQLCipher 번들) |
| chacha20poly1305, argon2 | 0.10, 0.5 | 상태 블롭·미디어·백업 AEAD, 복구 키 |
| ciborium | 0.2 | 엔트리·상태 블롭 CBOR |
| rusqlite | mls-rs-provider-sqlite와 같은 버전 | 로컬 저장소. libsqlite3-sys가 하나만 들어가야 함 |
| rrule | 0.14 | 반복 일정 전개 |
| reqwest(rustls), tokio-tungstenite, tokio | 최신 안정판, 0.30 | API, 게이트웨이 |
| uniffi | 0.31 | Swift·Kotlin 바인딩 |

## 3. 앱에 노출하는 API

UniFFI가 Swift와 Kotlin 바인딩을 만듭니다. 아래 이름은 실제로 생성해 확인한 것입니다.
- **오류:** Swift는 `CoreError`, Kotlin은 `CoreException`입니다.
- **비동기:** async 함수는 Swift에서 `async throws`, Kotlin에서 `suspend fun`이 됩니다.

| Rust | Swift | Kotlin | 용도 | 단계 |
| --- | --- | --- | --- | --- |
| `Core::open(store_dir, api_base, listener)` | `Core.open(storeDir:apiBase:listener:)` | `Core.open(storeDir, apiBase, listener)` | 저장소 열기, 동기화 시작 | M0 |
| `sync()` | `sync() async throws` | `suspend fun sync()` | 모든 그룹 따라잡기 | M0 |
| `save_event(input)` | `saveEvent(input:)` | `saveEvent(input)` | 일정 저장 | M1 |
| `suggestions(day)` | `suggestions(day:)` | `suggestions(day)` | 입력 추천 칩 5개 | M1 |
| `group_month(group_id, month)` | `groupMonth(groupId:month:)` | `groupMonth(groupId, month)` | 날짜 칸의 1시간 띠 | M2 |
| `group_day(group_id, day)` | `groupDay(groupId:day:)` | `groupDay(groupId, day)` | 30분 테이블 | M2 |
| `request_date(group_id, input)` | `requestDate(groupId:input:) async throws` | `suspend fun requestDate(...)` | 날짜 잡기 | M2 |
| `respond(group_id, appt, accept)` | `respond(groupId:appt:accept:) async throws` | `suspend fun respond(...)` | 수락·다음에 | M2 |
| `send_text(group_id, body)` | `sendText(groupId:body:) async throws` | `suspend fun sendText(...)` | 채팅 | M3 |
| `decrypt_push(store_dir, payload)` | `decryptPush(storeDir:payload:)` | `decryptPush(storeDir, payload)` | 알림 확장·핸들러용 | M3 |
| `CoreListener.on_change(change)` | `onChange(change:)` | `onChange(change)` | 앱이 구현. 캘린더·그룹·채팅 변경 알림 | M0 |

나머지 함수는 같은 규칙으로 단계마다 추가합니다.
- **캘린더:** 일정 조회·삭제, 교대 템플릿, 사람·생일, 기기 일정 넘기기, 일정 첨부
- **그룹:** 생성, 초대 코드, 그룹 설정(공통·나만), 그룹 프로필, 커버, 알림 종류별 끄기
- **약속:** 주도자 이전, 투표, 선택지 추가, 취소, 약속 첨부
- **채팅:** 미디어 보내기·받기, 검색, 신고, 차단
- **계정:** 로그인, 복구 코드, 백업, 탈퇴, 구독 상태(서버가 확인한 혜택만 켬)

**비밀 보관 콜백:** 앱이 `SecretStore` 콜백(`get(name)`, `put(name, value)`, `delete(name)`)을 구현합니다. 코어는 기기 서명 개인키, AMK, 데이터베이스 키를 이 콜백으로만 읽고 씁니다. 비밀은 SQLite에 저장하지 않습니다.

## 4. 로컬 저장소

- **파일:** 하나의 SQLCipher 데이터베이스입니다. iOS는 App Group 컨테이너, 안드로이드는 앱 전용 디렉터리에 둡니다. WAL 모드를 씁니다.
- **키:** 32바이트 무작위 키를 `SecretStore`에 둡니다. iOS 파일 보호 등급은 첫 잠금 해제 후 접근 가능(`completeUntilFirstUserAuthentication`)으로 해서, 잠긴 상태에서도 알림 확장이 읽을 수 있게 합니다.
- **MLS 상태:** mls-rs-provider-sqlite가 같은 파일의 별도 테이블로 관리합니다.
- **프로세스 간 잠금:** iOS의 앱과 알림 확장이 같은 파일을 씁니다. 쓰기 전에 잠금 파일에 `flock`을 겁니다.
  - 알림 확장은 5초 안에 잠금을 못 얻으면 자리표시 문구로 끝냅니다.
  - MLS는 메시지를 풀면 쓴 키를 지우므로 같은 메시지를 두 번 풀 수 없습니다. 그래서 알림 확장이 푼 메시지는 평문으로 저장하고, 앱은 커서 뒤부터만 처리합니다.
- **한국어 검색:** 조사가 붙어 있어("토요일에") 기본 토크나이저로는 "토요일"을 못 찾습니다. FTS5 trigram을 쓰고, 세 글자 미만 검색어는 `LIKE`로 찾습니다(번들 SQLite 3.50.2에서 확인).

```sql
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
```

## 5. 동기화 엔진

- **보내기:** 쓰기는 모두 `outbox`를 거칩니다.
  - 그룹마다 순서대로 보내고, 실패하면 지수 백오프로 재시도합니다.
  - 409·410은 [공통 계약](protocol.md) 11장대로 처리합니다. 커밋 충돌이면 대기 중 커밋을 버리고 다시 만들고, 오래된 epoch면 다시 암호화합니다.
- **받기:**
  - 앱이 포그라운드일 때 게이트웨이 채널에 붙어 있다가, `entry`를 받으면 순번 빈틈을 확인하고 처리합니다.
  - 포그라운드로 돌아올 때와 푸시를 받았을 때는 모든 그룹을 커서부터 따라잡습니다.
- **epoch가 바뀐 뒤:**
  1. 새 상태 키를 유도해 `state_keys`에 저장합니다.
  2. 내 가용 시간과 멤버 프로필을 다시 봉인해 올립니다. epoch를 바꾼 커밋을 내가 했으면 그룹 메타도 다시 봉인합니다(`changed_by`는 그대로).
  3. 내가 멤버를 추가한 커밋이었다면 새 멤버용 번들을 올립니다.
  4. 30일이 지났거나 그룹당 50개를 넘는 상태 키는 지웁니다.
- **가용 시간 올리기:** 캘린더가 바뀌면 2분 디바운스 뒤, 그리고 공유 설정이 바뀌거나 epoch가 바뀌면 그룹마다 비트맵을 다시 만들어 올립니다.
  - 소프트 캘린더(`blocks_free = 0`)는 빼고 셉니다.
  - 시간이 정해진 가안과 약속은 바쁨으로 셉니다.
  - 시간이 미정인 약속(종일)은 바쁨으로 세지 않습니다.
- **`decrypt_push`:**
  1. 잠금을 걸고 저장소를 엽니다.
  2. 푸시 순번이 커서 + 1이면 바로 처리하고, 빈틈이 20개 이하면 API에서 가져와 처리합니다. 그보다 크면 자리표시 문구로 끝냅니다.
  3. 결과를 저장하고 알림 문구를 돌려줍니다.
  4. Argon2id 같은 무거운 경로는 타지 않습니다.

## 6. 도메인 로직

규칙은 [개요](../kickoff.md) 5장, 형식은 [공통 계약](protocol.md) 4·5장을 따릅니다.

- **캘린더:** `rrule`로 반복을 전개하고, `exdates`와 `event_overrides`로 예외를 적용합니다. 30분 슬롯(한국 시간) 비트맵을 만들고, 18:10–19:20 일정은 18:00–19:30으로 올림합니다.
- **입력 추천:** `suggestScore`, 겹치는 칩 제외, 같은 요일 세 번이면 반복 제안.
- **그룹 캘린더:** 아래 코드 그대로입니다.
- **날짜 잡기 상태:**
  - 로그를 순번대로 적용합니다.
  - 주도자 전용 엔트리를 검사하고, 응답과 표는 사람마다 마지막 것만 유효합니다.
  - 투표 닫힘과 날짜 만료는 `received_at`으로 판정합니다.
- **투표 집계, 시간 추천:** 아래 코드 그대로입니다.
- **활동 추천:** 카테고리 사전과 `activityScore`. 사전은 코어에 내장하고 앱 업데이트로 바꿉니다.
- **공휴일:** API에서 받아 저장하고, 연차 다리 제안을 계산합니다.
- **채팅:** 엔트리 타임라인, 날짜 잡기 카드, `chat_fts` 색인, 미디어 STREAM 암복호화.

```rust
// core/geunal-domain/src/availability.rs — the group calendar is members' schedules merged. Nothing here decides what "free" means.
// A bitmap holds one bit per 30-minute slot in Asia/Seoul, MSB first; 1 means the member has no event in that slot.

pub fn is_free(bitmap: &[u8], slot: usize) -> bool {
    bitmap.get(slot >> 3).is_some_and(|b| (b >> (7 - (slot & 7))) & 1 == 1)
}

/// How many members have no event in each slot: the 30-minute day table. 0 stays blank, 1 is the lightest shade.
pub fn free_counts(members: &[&[u8]], slots: usize) -> Vec<u8> {
    (0..slots).map(|s| members.iter().filter(|m| is_free(m, s)).count() as u8).collect()
}

/// Members with no event for every slot of [from, to): who is free throughout a dragged range.
pub fn free_throughout(members: &[&[u8]], from: usize, to: usize) -> Vec<usize> {
    members.iter().enumerate().filter(|(_, m)| (from..to).all(|s| is_free(m, s))).map(|(i, _)| i).collect()
}

/// Members free for the whole hour: the 1-hour strip in a month cell. Half an hour free is not an hour free.
pub fn hour_counts(members: &[&[u8]], day: usize) -> [u8; 24] {
    let mut out = [0u8; 24];
    for (h, c) in out.iter_mut().enumerate() {
        *c = free_throughout(members, day * 48 + h * 2, day * 48 + h * 2 + 2).len() as u8;
    }
    out
}

#[derive(Debug, PartialEq)]
pub struct TimeOption { pub start: usize, pub end: usize, pub free: usize } // slot offsets within the day

/// The recommender's window, 09:00-21:00 by default; a group setting, not a rule about when people are free.
pub const DEFAULT_WINDOW: (usize, usize) = (18, 42);

/// Three non-overlapping hour-aligned ranges inside the window: most participants free throughout first, earliest first on ties.
pub fn time_options(members: &[&[u8]], day: usize, len_slots: usize, window: (usize, usize)) -> Vec<TimeOption> {
    let (from, to) = window;
    let base = day * 48;
    let mut ranked: Vec<TimeOption> = (from..=to.saturating_sub(len_slots)).step_by(2)
        .map(|s| TimeOption { start: s, end: s + len_slots, free: free_throughout(members, base + s, base + s + len_slots).len() })
        .collect();
    ranked.sort_by(|a, b| b.free.cmp(&a.free).then(a.start.cmp(&b.start)));
    let mut chosen: Vec<TimeOption> = Vec::new();
    for o in ranked {
        if chosen.len() == 3 { break; }
        if chosen.iter().all(|c| o.end <= c.start || o.start >= c.end) { chosen.push(o); }
    }
    chosen
}
```

A가 13–15시, B가 14–16시, C가 하루 종일 비어 있을 때의 결과:
- **30분 테이블:** 13시 2명, 14시 3명, 16시 1명
- **13–16시 드래그:** C만 내내 비어 있음
- **1시간 띠:** 13시 2명, 14시 3명, 15시 2명, 16시 1명. 13:30부터 비는 사람은 13시 칸에 들어가지 않음
- **2시간 시간 추천:** 13–15시(2명), 09–11시(1명), 11–13시(1명). 14–16시도 2명이지만 13–15시와 겹쳐서 빠지고, 동점은 이른 쪽이 먼저임. 길이가 시간대보다 길면 추천이 비고, 선택지는 추가한 것과 "아무거나"만 남음

```rust
// core/geunal-domain/src/appointments.rs — vote tally; every device runs the same function over the same log.
use crate::entry::Choice;

/// `options` are the three recommendations followed by member-added options in log order; `votes` hold the latest choice
/// per participant. "Any" adds to nothing, no counted vote means recommendation #1, and a tie goes to the option listed first.
pub fn tally(options: &[String], votes: &[Choice]) -> Option<String> {
    let counts: Vec<usize> = options.iter()
        .map(|o| votes.iter().filter(|v| matches!(v, Choice::Opt(x) if x == o)).count())
        .collect();
    let best = *counts.iter().max()?;
    if best == 0 { return options.first().cloned(); }
    counts.iter().position(|&c| c == best).map(|i| options[i].clone())
}
```

```rust
// core/geunal-mls/src/state.rs — state blobs (availability, group meta, newcomer bundle) are sealed with a per-epoch key
// from the MLS exporter, so any member of the epoch can read them any number of times and the server can overwrite them.
use chacha20poly1305::{aead::{Aead, KeyInit, Payload}, XChaCha20Poly1305, XNonce};
use mls_rs::{client_builder::MlsConfig, error::MlsError, Group};
use rand_core::{OsRng, RngCore};

pub fn state_key<C: MlsConfig>(group: &Group<C>) -> Result<[u8; 32], MlsError> {
    let secret = group.export_secret(b"geunal state v1", b"", 32)?;
    Ok(secret.as_bytes().try_into().expect("exporter returns the requested 32 bytes"))
}

/// AAD pins a blob to its group, epoch, kind and owner so the server cannot move it to another slot.
pub fn state_aad(group_id: &str, epoch: u64, kind: &str, owner: &str) -> String {
    format!("state|{group_id}|{epoch}|{kind}|{owner}")
}

pub fn seal(key: &[u8; 32], aad: &str, plaintext: &[u8]) -> Vec<u8> {
    let mut nonce = [0u8; 24];
    OsRng.fill_bytes(&mut nonce);
    let ct = XChaCha20Poly1305::new(key.into())
        .encrypt(XNonce::from_slice(&nonce), Payload { msg: plaintext, aad: aad.as_bytes() })
        .expect("encryption with a valid key cannot fail");
    [nonce.as_slice(), &ct].concat()
}

pub fn open(key: &[u8; 32], aad: &str, blob: &[u8]) -> Option<Vec<u8>> {
    if blob.len() < 24 + 16 { return None; }
    XChaCha20Poly1305::new(key.into())
        .decrypt(XNonce::from_slice(&blob[..24]), Payload { msg: &blob[24..], aad: aad.as_bytes() })
        .ok()
}
```

## 7. 빌드와 배포

| 대상 | 타깃 | 산출물 | 빌드 위치 |
| --- | --- | --- | --- |
| iOS | `aarch64-apple-ios`, `aarch64-apple-ios-sim` | XCFramework + 생성된 Swift + modulemap → Swift 패키지 `GeunalCore`(바이너리 타깃) | GitHub Actions macOS |
| 안드로이드 | `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` | `cargo-ndk`로 `jniLibs` + 생성된 Kotlin → 안드로이드 라이브러리 모듈 `geunal-core`(JNA 의존) | 리눅스 |
| 호스트 | `x86_64-unknown-linux-gnu` | 테스트, 헤드리스 종단 시험 클라이언트, 테스트 벡터 | 리눅스 |

- **SQLCipher 빌드:** 안드로이드는 OpenSSL을 함께 빌드하는 rusqlite 기능을 쓰고, iOS는 CommonCrypto를 씁니다. M0에서 두 플랫폼 빌드를 먼저 통과시킵니다.
- **크기 예산:** 릴리스·심볼 제거 기준 ABI당 8MB 이하. 크기를 CI에서 기록하고 늘어나면 알립니다.
- **버전:** 코어 버전은 앱 빌드 번호와 함께 올립니다. 앱과 코어를 따로 배포하지 않습니다.

## 8. 성능·메모리 예산

| 경로 | 예산 | 이유 |
| --- | --- | --- |
| `decrypt_push` | 최대 메모리 15MB, 300ms 이내 | iOS 알림 확장 한도 24MB·30초 |
| `group_month`(20명 × 6주) | 20ms 이내(중급 기기) | 월 화면 스크롤 |
| `group_day`(20명) | 5ms 이내 | 날 테이블 드래그 |
| 첫 실행 `open` + `sync`(그룹 5개, 100항목) | 2초 이내 | 앱 시작 |
| Argon2id(64MiB) | 앱 본체에서만 | 확장 메모리 한도 초과 |

참고: MLS 10명 그룹의 추가·메시지·강퇴를 클라이언트 11개로 한 프로세스에서 돌렸을 때 최대 메모리는 10.7MB(리눅스)였습니다.

## 9. 테스트

- **단위 테스트:** 아래 목록부터 씁니다.
- **속성 테스트(proptest):** 같은 로그는 언제나 같은 상태를 내는지, 집계가 표 순서에 영향을 받지 않는지.
- **MLS 통합:** 한 프로세스 안의 여러 클라이언트로 추가, 제거, 자기 갱신, 외부 가입, 커밋 충돌을 확인합니다.
- **동기화 통합:** 테스트 안의 가짜 서버로 409·410·빈틈 처리를 확인하고, M0 게이트에서는 로컬 compose의 진짜 API·게이트웨이와 붙입니다.
- **퍼징(cargo-fuzz):** 엔트리 디코드, 푸시 페이로드 파싱, MLS 메시지 입력.

**첫 테스트 목록**

캘린더와 추천
- `expands a weekly RRULE with an EXDATE across a month boundary in Asia/Seoul`
- `applies a shift template to three days as three ordinary events`
- `treats an end time before the start time as a shift past midnight`
- `rounds an 18:10-19:20 event to the 18:00-19:30 slots`
- `leaves calendars flagged blocks_free = 0 out of the group bitmap`
- `counts timed appointments as busy and untimed ones as free`
- `ranks weekday work above a weekend outing on a Tuesday`
- `suggests a birthday meetup within seven days of a registered birthday`
- `converts a lunar birthday to this year's solar date before suggesting`
- `drops a suggestion that overlaps an existing event`
- `offers to make a title recurring after three same-weekday uses`
- `marks substitute holidays and accepts two holidays on one date`
- `suggests one leave day that bridges a holiday into a 4-day weekend`

그룹 캘린더
- `counts free members per 30-minute slot without any threshold`
- `counts a member for an hour cell only when both half-hours are free`
- `lists members free throughout a dragged range`
- `treats a member whose availability blob cannot be opened as absent`

날짜 잡기와 투표
- `adds the appointment to the requester's calendar as tentative on request`
- `adds the appointment to a member's calendar on accept and removes it on later`
- `ignores request updates, polls and cancels not written by the current leader`
- `moves the leader role on lead_transfer`
- `removes an unaccepted request once its day has passed in Asia/Seoul`
- `closes a poll when every current participant has voted`
- `closes a poll at 00:00 of the appointment day by server receive time`
- `picks the first recommendation when every vote is "any"`
- `resolves a tie by list order: recommendations first, then added options in log order`
- `resets votes when the leader reopens a poll`
- `ignores options added by non-leaders when the request copied leader_options`
- `ranks a disliked activity below a neutral one through the least-misery term`
- `derives the same state from the same log on two independent runs`

MLS와 동기화
- `adds a device with a welcome and rejects a commit for an epoch the server has passed`
- `re-seals own availability after every epoch change`
- `rejoins by external commit after cursor_expired and keeps local history`
- `re-encrypts and resends a message refused as stale_epoch`
- `fetches the gap when a pushed seq skips ahead`
- `never decrypts the same application message twice across the app and the extension`

그룹 설정
- `re-applies only my changed fields after a state_conflict on group_meta`
- `keeps changed_by when re-sealing group_meta after an epoch change`
- `keeps a member's group nickname and avatar inside that group only`

채팅과 첨부
- `finds "토요일" inside "토요일에" and falls back to LIKE for two-letter queries`
- `rejects media whose ciphertext hash does not match the entry`
- `hides group messages from a blocked member on this device only`
- `ignores a media_remove written by someone other than the uploader`
- `keeps only the first three live attachments of an appointment in log order`
- `refuses files over 10 MB and types outside the nine allowed formats before upload`
- `restores a personal event attachment from the keys in the backup`

## 10. 완료 조건

| 단계 | 완료 조건 |
| --- | --- |
| M0 | 위 목록 중 캘린더 반복·그룹 캘린더·투표·MLS 항목 통과. iOS·안드로이드 타깃 빌드와 SQLCipher 동작. 헤드리스 종단 시험(클라이언트 3개, 로컬 API·게이트웨이) 통과. 테스트 벡터 게시. mls-rs와 OpenMLS 비교 메모를 `docs/adr/`에 기록 |
| M1 | 캘린더·추천·백업·복원 항목 통과. 크기 예산 충족 |
| M2 | 날짜 잡기·투표·그룹 설정·외부 가입·상태 재봉인 항목 통과. 성능 예산 충족 |
| M3 | 채팅·첨부·`decrypt_push` 항목 통과. 실기기에서 알림 확장 메모리 예산 확인 |
| M4 | 외부 리뷰 지적 반영. 퍼징 24시간 무중단 |
