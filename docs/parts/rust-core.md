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
  geunal-domain/      # entry, calendar and repeat rules, availability, appointments, suggestions, holidays, lunar, chat
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
| reqwest(rustls), tokio-tungstenite, tokio | 최신 안정판, 0.30 | API, 게이트웨이 |
| uniffi | 0.31 | Swift·Kotlin 바인딩 |

## 3. 앱에 노출하는 API

UniFFI가 Swift와 Kotlin 바인딩을 만듭니다. 아래 이름은 실제로 생성해 확인한 것입니다.
- **오류:** Swift는 `CoreError`, Kotlin은 `CoreException`입니다.
- **비동기:** async 함수는 Swift에서 `async throws`, Kotlin에서 `suspend fun`이 됩니다.

| Rust | Swift | Kotlin | 용도 | 단계 |
| --- | --- | --- | --- | --- |
| `Core::open(store_dir, api_base, listener, secrets)` | `Core.open(storeDir:apiBase:listener:secrets:)` | `Core.open(storeDir, apiBase, listener, secrets)` | 저장소 열기. 데이터베이스 키를 `secrets`에서 꺼내야 파일을 열 수 있어서 `SecretStore`를 여기서 받습니다. 저장된 세션이 있으면 복원 | M0 |
| `sign_in_stub(subject, display_name, platform)` | `signInStub(subject:displayName:platform:) async throws` | `suspend fun signInStub(subject, displayName, platform)` | 인증 스텁(`POST /auth/stub`, 로컬·CI만)으로 로그인과 기기 등록. 세션과 기기 서명 키는 `SecretStore`에 둠. M1부터 `AuthResult`를 돌려주고, 다른 기기가 쓰는 계정이면 `ReplaceRequired`(protocol.md 13장) | M0 |
| `sync()` | `sync() async throws` | `suspend fun sync()` | 모든 그룹 따라잡기, 대기 중 쓰기 보내기 | M0 |
| `cipher_version()` | `cipherVersion()` | `cipherVersion()` | M0 화면용 SQLCipher 버전. 화면이 없어지면 지움 | M0 |
| `create_group(name)`, `add_members(group_id, user_ids)`, `remove_member(group_id, user_id)` | `createGroup(name:) async throws` 등 | `suspend fun createGroup(name)` 등 | 그룹 만들기, 사용자 ID로 멤버 추가(초대 코드는 M2), 탈퇴·강퇴. 헤드리스 종단 시험이 쓰는 M0 표면 | M0 |
| `send_text`, `request_date`, `respond`, `open_poll`, `vote`, `close_poll`, `cancel` | 같은 이름, `async throws` | 같은 이름, `suspend fun` | 엔트리 보내기(protocol.md 4장). M2·M3 화면이 그대로 씀 | M0 |
| `group_state(group_id)`, `messages(group_id)`, `members(group_id)`, `groups()`, `group_availability(group_id)` | 같은 이름 | 같은 이름 | 저장된 로그에서 계산한 약속 상태, 채팅, 로스터, 그룹, 가용 시간 | M0 |
| `connect_realtime(gateway_base)`, `next_realtime_event(timeout_ms)` | `connectRealtime(gatewayBase:) async throws` 등 | `suspend fun connectRealtime(gatewayBase)` 등 | 게이트웨이 채널에 붙고 이벤트를 받아 처리 | M0 |
| `calendars()`, `save_calendar(input)`, `set_calendar_visible(calendar_id, visible)`, `delete_calendar(calendar_id)` | `calendars()`, `saveCalendar(input:)` 등 | `calendars()`, `saveCalendar(input)` 등 | 캘린더 관리(m1.md 15·16번 화면). 마지막 내 캘린더는 지울 수 없음 | M1 |
| `add_event(input, from_suggestion)`, `event(event_id, occurrence)`, `update_event(event_id, occurrence, scope, input)`, `delete_event(event_id, occurrence, scope)` | `addEvent(input:fromSuggestion:)` 등 | `addEvent(input, fromSuggestion)` 등 | 일정 넣기·열기·고치기·지우기. 반복 일정은 `scope`로 "이 일정만·이후 모두". 되돌리기는 `delete_event`. `add_event`가 같은 요일 3번째 제목이면 반복 제안(`repeat_hint`)을 한 번 돌려줌 | M1 |
| `calendar_month(month)`, `calendar_week(start_day)`, `calendar_agenda(from_day, days)`, `next_up(count)` | `calendarMonth(month:)` 등 | `calendarMonth(month)` 등 | 월·주·목록 뷰와 다음 할 일 막대. 공휴일, 생일, 연차 다리 제안이 함께 옴 | M1 |
| `widget_snapshot()` | `widgetSnapshot()` | `widgetSnapshot()` | 위젯이 읽는 JSON(아래). 앱이 공유 컨테이너에 씀 | M1 |
| `suggestions(day)` | `suggestions(day:)` | `suggestions(day)` | 입력 추천 칩 5개. 칩을 누르면 `add_event(칩의 event, true)` | M1 |
| `shift_templates()`, `save_shift_template(input)`, `delete_shift_template(template_id)`, `shift_days(month)`, `set_shift_days(days)` | `shiftTemplates()` 등 | `shiftTemplates()` 등 | 교대 템플릿과 찍기. 찍기 화면은 초안을 들고 있다가 "완료"에 `set_shift_days`로 한 번에 적용 | M1 |
| `mirror_device_calendars(calendars)` | `mirrorDeviceCalendars(calendars:)` | `mirrorDeviceCalendars(calendars)` | 고른 기기 캘린더와 그 일정으로 미러를 바꿈. 빈 목록이면 끔 | M1 |
| `notification_settings()`, `set_notification_settings(settings)`, `scheduled_alerts(limit)` | `notificationSettings()` 등 | `notificationSettings()` 등 | 알림 설정과, 앱이 예약할 가까운 알림(iOS는 64개) | M1 |
| `export_ics()` | `exportIcs()` | `exportIcs()` | 내 일정의 iCalendar 내보내기. 기기 캘린더는 뺌 | M1 |
| `people()`, `upcoming_birthdays(days)`, `save_person(input)`, `delete_person(person_id)`, `import_contacts(contacts)` | `people()` 등 | `people()` 등 | 사람·생일(11·17번 화면) | M1 |
| `birthday_in_year(birthday, year)`, `lunar_to_solar(year, month, day, leap_month)` | `birthdayInYear(birthday:year:)` 등 | `birthdayInYear(birthday, year)` 등 | 음력 생일의 그해 양력 날짜(6장의 윤달·30일 규칙 적용), 음력 날짜 그대로의 변환. 표 범위 밖은 `nil`/`null` | M1 |
| `sign_in_nonce()`, `sign_in_kakao(nonce, id_token, account)`, `sign_in_apple(nonce, id_token, account)`, `sign_in_guest(account)` | `signInNonce() async throws` 등 | `suspend fun signInNonce()` 등 | 로그인과 기기 등록(protocol.md 13장). 앱은 두 제공자 SDK에 `provider_nonce`를 넘깁니다(이 기기 키에 묶은 값). 카카오·Apple은 `AuthResult`, 게스트는 늘 새 계정 | M1 |
| `local_data_of_other_account()`, `restore_from_code(words, local_data, progress)`, `start_fresh()` | `restoreFromCode(words:localData:progress:) async throws` 등 | `suspend fun restoreFromCode(words, localData, progress)` 등 | 복구 코드로 복원하고 이 기기를 활성 기기로(교체 티켓, 로그인한 기기, 게스트 복귀 모두), 또는 "백업 없이 새로 시작". 다른 계정의 일정이 남아 있으면 앱이 먼저 묻고 가져오기·지우기를 넘깁니다(protocol.md 10장 "복원") | M1 |
| `link_kakao(nonce, id_token)`, `link_apple(nonce, id_token)`, `account()`, `me_summary()`, `delete_account(proof)`, `revocation()` | `linkKakao(nonce:idToken:) async throws` 등 | `suspend fun linkKakao(nonce, idToken)` 등 | 게스트 연결, 계정 정보, "나" 화면 요약, 계정 삭제(다시 증명: 게스트는 코어가 서명, 카카오는 새 ID 토큰, Apple은 새 인가 코드. 일정과 사람은 남김), 다른 기기 로그인으로 해제된 때·플랫폼·방법 | M1 |
| `recovery_words()`, `recovery_status()`, `mark_recovery_saved()`, `check_recovery_word(text)`, `recovery_completions(prefix)`, `split_recovery_code(text)` | `recoveryWords()` 등 | `recoveryWords()` 등 | 복구 코드 12단어(서버에 복구 자료가 없는 로그인 때 만들어 올리고, 여기서는 읽기만), 저장 표시, 입력 칸의 단어 확인·자동 완성, 붙여넣기 | M1 |
| `backup_status()`, `backup_now(progress)`, `auto_backup()`, `backups()`, `set_auto_backup(enabled)` | `backupStatus()`, `backupNow(progress:) async throws` 등 | `backupStatus()`, `suspend fun backupNow(progress)` 등 | 암호화 백업. `auto_backup`은 앱의 하루 한 번 와이파이 작업이 부르고, 바뀐 게 없으면 건너뜀 | M1 |
| `alpha_stats_enabled()`, `set_alpha_stats(enabled)`, `record_app_foreground()`, `record_crashes(day, count)` | `alphaStatsEnabled()` 등 | `alphaStatsEnabled()` 등 | 알파 통계 동의와, 앱만 아는 값(앱이 앞으로 나온 횟수, OS가 알려 준 크래시 수). 켜져 있으면 `sync()`가 하루 한 번 보냄(protocol.md 14장) | M1 |
| `ProgressListener.on_progress(done, total)` | `onProgress(done:total:)` | `onProgress(done, total)` | 앱이 구현. 백업·복원의 진행(바이트) | M1 |
| `group_month(group_id, month)` | `groupMonth(groupId:month:)` | `groupMonth(groupId, month)` | 날짜 칸의 1시간 띠 | M2 |
| `group_day(group_id, day)` | `groupDay(groupId:day:)` | `groupDay(groupId, day)` | 30분 테이블 | M2 |
| `request_date(group_id, input)` | `requestDate(groupId:input:) async throws` | `suspend fun requestDate(...)` | 날짜 잡기 | M2 |
| `respond(group_id, appt, accept)` | `respond(groupId:appt:accept:) async throws` | `suspend fun respond(...)` | 수락·다음에 | M2 |
| `send_text(group_id, body)` | `sendText(groupId:body:) async throws` | `suspend fun sendText(...)` | 채팅 | M3 |
| `decrypt_push(store_dir, payload)` | `decryptPush(storeDir:payload:)` | `decryptPush(storeDir, payload)` | 알림 확장·핸들러용 | M3 |
| `CoreListener.on_change(change)` | `onChange(change:)` | `onChange(change)` | 앱이 구현. 캘린더·그룹·채팅 변경 알림. M1에 계정 변경(`Account`: 로그인, 다른 기기 로그인으로 해제, 삭제)이 더해짐 | M0 |

나머지 함수는 같은 규칙으로 단계마다 추가합니다.
- **캘린더:** 일정 첨부
- **그룹:** 생성, 초대 코드, 그룹 설정(공통·나만), 그룹 프로필, 커버, 알림 종류별 끄기
- **약속:** 주도자 이전, 투표, 선택지 추가, 취소, 약속 첨부
- **채팅:** 미디어 보내기·받기, 검색, 신고, 차단
- **계정:** 구독 상태(서버가 확인한 혜택만 켬)

**비밀 보관 콜백:** 앱이 `SecretStore` 콜백(`get(name)`, `put(name, value)`, `delete(name)`)을 구현합니다. 코어는 기기 서명 개인키, AMK, 데이터베이스 키를 이 콜백으로만 읽고 씁니다. 비밀은 SQLite에 저장하지 않습니다.

### 날짜와 시각

코어와 앱이 주고받는 캘린더의 날짜와 시각은 모두 한국 시간(Asia/Seoul)입니다. 모든 규칙이 한국 시간으로 돌기 때문에([개요](../kickoff.md) 5장), 앱은 시간대를 바꾸지 않고 받은 값을 그대로 그립니다.

| 값 | 형식 | 예 |
| --- | --- | --- |
| 날 | `"YYYY-MM-DD"` 문자열 | `"2026-10-30"` |
| 달 | `"YYYY-MM"` 문자열 | `"2026-10"` |
| 시각 | `DayTime { day, minute }`. `minute`은 자정부터의 분(0–1439) | 금요일 17:00은 `{ "2026-10-30", 1020 }` |
| 시점 | `SystemTime`(Swift `Date`, Kotlin `java.time.Instant`) | 알림이 울리는 때, 백업한 때 |

- 시간이 있는 일정의 끝은 그 시각을 포함하지 않습니다. 자정에 끝나면 끝은 다음 날 0분입니다.
- 종일 일정은 시작 날부터 끝 날까지(끝 날 포함)이고, 분은 보지 않습니다.
- 알림 시각은 시작 기준 몇 분 전인지(`AlertRule::Before { minutes }`)로 적습니다. 종일 일정과 생일은 그날 00:00이 시작이라 "전날 18:00"은 360, "당일 09:00"은 -540입니다. 일정은 캘린더를, 캘린더는 앱 전체 설정을 따를 수 있습니다(`Inherit`).

### 테스트의 가짜 코어

UniFFI는 Rust 쪽 객체 없이 만드는 생성자(Swift `Core(noHandle: Core.NoHandle())`, Kotlin `Core(NoHandle)`)를 가짜용으로 만듭니다. 앱 테스트의 가짜는 이것으로 `Core`를 상속하고, 그 테스트가 부르는 메서드만 재정의합니다. 그러면 코어에 함수가 늘어도 가짜를 고칠 필요가 없습니다. 재정의하지 않은 메서드를 부르면 테스트가 멈춥니다.

### 위젯 스냅샷

`widget_snapshot()`이 돌려주는 JSON입니다. 앱은 일정이 바뀔 때와 자정에 이것을 공유 컨테이너(iOS는 App Group, 안드로이드는 Glance가 읽는 앱 파일)에 쓰고, 위젯은 코어를 열지 않고 이 파일만 읽습니다.

```json
{
  "v": 1,
  "day": "2026-10-25",
  "today": [
    { "title": "가족 · 저녁", "color": "blossom", "all_day": false, "start": "18:00", "end": "20:00" }
  ],
  "tomorrow": [
    { "title": "전공 수업", "color": "sea", "all_day": false, "start": "10:00", "end": "12:00" }
  ],
  "next_appointment": null
}
```

- `v`는 형식 버전입니다. 위젯은 모르는 버전이면 "그날어때를 열어 주세요"로 둡니다.
- `today`, `tomorrow`는 표시 중인 캘린더의 일정을 종일 먼저, 그다음 시작 순으로 5개까지 담습니다. 전날 시작해 그날까지 이어지는 일정도 들어갑니다.
- `color`는 [`tokens.json`](../design/tokens.json) `color.group`의 키이고, 기기 캘린더 일정은 `null`(기기 캘린더 색)입니다. `start`·`end`는 `"HH:MM"`이고 종일 일정은 둘 다 `null`입니다.
- `next_appointment`는 M1에서 늘 `null`이고, M2에서 그룹 약속의 모양을 정합니다.
- 자정이 지났는데 앱이 아직 다시 쓰지 않았으면 `day`가 어제입니다. 위젯은 그때 `tomorrow`를 오늘로 보여 줍니다.

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

M1은 `core/geunal-store/migrations/0002_m1.sql`로 다음을 더합니다. 모두 기기에만 있고 서버에는 암호화 백업으로만 갑니다. 알파 통계만 예외로, 동의한 테스터의 기기가 날마다 숫자를 보냅니다([공통 계약](protocol.md) 14장).

- **캘린더:** 숨김(`visible`), 기본 알림, 순서, 미러한 기기 캘린더의 원본 ID
- **일정:** 알림, 찍은 교대 템플릿, 만든 때와 만든 방법(`typed`, `suggestion`, `shift`, `device`, `restore`). 만든 때는 되돌리기가 센 것을 돌려놓을 때 씁니다.
- **교대 템플릿:** 캘린더, 알림, 색 단계(1–5), 순서
- **사람:** 태어난 해, 윤달, 알림, 추천에 쓰기, 연락처 ID, 공개한 그룹(M2)
- **추천 기록:** 같은 요일에 마지막으로 쓴 끝 시각·캘린더·종일 여부, 반복 제안을 물었는지
- **새 테이블:** 설정(`settings`), 서버가 보낸 해의 공휴일(`holidays`, `holiday_years`), 알파 통계(`alpha_stats`), 이 기기가 만든 백업의 기록(`backup_records`)

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

- **캘린더:** 편집기가 쓴 RFC 5545 반복 규칙을 한국 시간으로 전개하고(`recurrence.rs`), `exdates`와 `event_overrides`로 예외를 적용합니다. 저장된 규칙은 모두 편집기가 쓴 것이라 그 규칙만 읽고, `Asia/Seoul`이 아닌 시간대의 규칙은 거부합니다. 범용 RRULE 크레이트는 시간대 데이터베이스와 정규식을 함께 들여와 안드로이드 라이브러리를 ABI마다 약 2MB 키웠습니다(7장). 30분 슬롯(한국 시간) 비트맵을 만들고, 18:10–19:20 일정은 18:00–19:30으로 올림합니다.
- **입력 추천:** `suggestScore`, 겹치는 칩 제외, 같은 요일 세 번이면 반복 제안. 아래 "입력 추천"에 후보와 점수를 적습니다.
- **그룹 캘린더:** 아래 코드 그대로입니다.
- **날짜 잡기 상태:**
  - 로그를 순번대로 적용합니다.
  - 주도자 전용 엔트리를 검사하고, 응답과 표는 사람마다 마지막 것만 유효합니다.
  - 투표 닫힘과 날짜 만료는 `received_at`으로 판정합니다.
- **투표 집계, 시간 추천:** 아래 코드 그대로입니다.
- **활동 추천:** 카테고리 사전과 `activityScore`. 사전은 코어에 내장하고 앱 업데이트로 바꿉니다.
- **공휴일:** 내장 표에서 시작해 API에서 받은 해로 바꾸고, 연차 다리 제안을 계산합니다. 아래 "공휴일과 연차 다리"에 적습니다.
- **사람·생일:** 음력 생일을 해마다 양력으로 바꿉니다. 아래 "사람·생일과 음력"에 규칙을 적습니다.
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

### 사람·생일과 음력

- 생일은 입력한 달력 그대로 저장합니다. 음력 생일은 해마다 그해의 양력 날짜로 바꿔 월·주·목록 뷰, 다음 할 일 막대, 알림, 추천에 넣습니다.
- 음력 표는 1900–2049년입니다.
  - 한국천문연구원 자료로 만든 두 패키지(manseryeok 2.0.0, korean-lunar-calendar 0.4.0)가 이 범위의 모든 해에 같은 값을 갖습니다.
  - `core/scripts/gen-lunar-table.py`가 두 패키지를 고정한 버전과 무결성 해시로 받아 비교하고, 알려진 설날·추석이 맞는지 확인한 뒤 표를 씁니다. 두 MIT 고지는 표 옆 `NOTICE.md`에 있습니다.
  - 표 밖의 해는 바꾸지 않고 날짜를 비웁니다(`nil`/`null`). 추측한 날짜는 틀려도 아무도 알아채지 못하기 때문입니다.
- **윤달 생일:** 그해에 그 윤달이 있으면 윤달에, 없으면 같은 숫자의 평달에 둡니다.
- **음력 30일 생일:** 그해 그 달이 29일까지면 29일에 둡니다.
- **양력 2월 29일 생일:** 윤년이 아니면 2월 28일에 둡니다.
- 음력 생일은 한 양력 해에 두 번 올 수 있습니다(음력 12월 생일이 1월 초와 12월 말에). 뷰에는 둘 다 나옵니다.
- **알림:** 기본은 당일 09:00(`-540`)입니다. 알림 설정의 "생일 알림"은 알림을 `Inherit`로 둔 사람에게만 적용되고, 사람마다 정한 알림은 그대로 울립니다.
- 그룹에서 공개된 생일(M2)은 이름과 생일을 바꾸거나 지울 수 없고, 알림만 바꿉니다.
- **연락처 가져오기:** 연락처 ID로 전에 가져온 사람을 찾아 이름과 생일만 고칩니다. 알림과 추천 스위치는 사용자가 정한 대로 둡니다. 생일이나 이름이 없는 연락처는 건너뜁니다.
- 태어난 해는 선택이고 1900년부터 올해까지 받습니다. 나이는 어디에도 보여 주지 않습니다.

### 공휴일과 연차 다리

- 코어에 2026–2027년 표가 있습니다(`core/geunal-domain/src/holidays.rs`, 출처는 파일 머리). 서버가 한 해를 보내면 그해 전체를 바꿉니다([공통 계약](protocol.md) 14장). 그래서 나중에 정한 임시공휴일도 보입니다.
- 하루에 공휴일이 둘일 수 있습니다(2025-05-05의 어린이날·부처님오신날).
- **연차 다리:** 앞뒤가 모두 쉬는 날(주말·공휴일)인 평일 하루를 쉬면 4일 이상 이어 쉬게 될 때, 그 평일을 제안합니다.
- 그날에 제목에 "연차"나 "휴가"가 들어간 종일 일정이 있으면 이미 쉬는 것으로 보고 제안하지 않습니다. 제안 칩이 채워 넣는 일정이 "연차"이기 때문입니다. 다른 종일 일정("약속")은 쉬는 날로 보지 않습니다.
- 알림 설정의 "연휴·연차 제안"이 꺼지면 뷰의 제안과 알림이 모두 없습니다. 켜져 있으면 쉴 날의 21일 전 09:00에 알립니다. 휴가를 미리 신청할 수 있게 하려는 것입니다.

### 입력 추천

[개요](../kickoff.md) 5.2절의 점수로 후보를 매겨 5개를 보여 줍니다. 쉬는 날은 주말과 공휴일입니다.

| 출처 | 제목 | 시간 | contextFit | personalFreq | upcoming | recency |
| --- | --- | --- | --- | --- | --- | --- |
| 기본 사전 | 아래 표 | 사전의 시간 | 평일·쉬는 날 적합도 | 같은 요일 횟수 ÷ 그 요일 최다 횟수 | 0 | 30일 안에 썼으면 1 |
| 내 기록 | 같은 요일에 쓴 제목 | 마지막에 쓴 시간·캘린더 | 1 | 같은 요일 횟수 ÷ 그 요일 최다 횟수 | 0 | 30일 안에 썼으면 1 |
| 사람·생일 | "OO 생일 약속" | 19–21시 | 0.8 | 0 | 7일 안의 생일 | 0 |
| 연휴 | "연휴 여행"(종일) | | 연휴 안 1, 연휴 앞 0.8 | 0 | 연휴 안 1, 7일 안의 연휴 | 0 |
| 대체공휴일 | "대체공휴일 나들이"(종일) | 그날이 대체공휴일일 때 | 1 | 0 | 1 | 0 |
| 교대 템플릿 | 템플릿 이름 | 템플릿 시간 | 0.8 | 0 | 0 | 0 |

- upcoming은 1 − 남은 날 ÷ 8입니다. 당일 1, 7일 뒤 0.125, 그 뒤는 0입니다.
- 기본 사전:

| 제목 | 시간 | 평일 | 쉬는 날 |
| --- | --- | --- | --- |
| 회사 업무 | 09–18 | 1.0 | 0 |
| 회식 | 19–22 | 0.8 | 0.2 |
| 수업 | 10–12 | 0.9 | 0.1 |
| 운동 | 19–20 | 0.7 | 0.6 |
| 약속 | 종일 | 0.4 | 0.8 |
| 데이트 | 18–21 | 0.4 | 0.9 |
| 가족 모임 | 12–14 | 0.2 | 0.9 |
| 병원 | 10–11 | 0.6 | 0.2 |

- 그날 이미 있는 제목은 다시 내지 않습니다. 시간 있는 일정과 겹치는 칩은 빼고, 종일 칩은 겹치지 않는 것으로 봅니다. 밤샘 칩(22–02시)은 자정까지만 견주고, 저장하면 다음 날 02시에 끝납니다.
- 같은 제목은 점수가 가장 높은 하나만 남깁니다. 점수가 같으면 제목 순입니다.
- 캘린더가 정해지지 않은 칩은 첫 번째 내 캘린더('개인')에 들어갑니다.
- 가중치와 사전은 알파의 추천 입력 비율을 보고 손으로 조정합니다.
- 같은 제목을 같은 요일에 세 번 넣으면 반복 일정으로 바꿀지 한 번 묻습니다. 넣은 지 5초 안에 지우면 토스트의 되돌리기로 보고, 그 입력이 센 추천 기록과 알파 통계를 돌려놓습니다.

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
