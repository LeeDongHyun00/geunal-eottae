# 공통 계약

모든 파트가 지키는 규칙입니다. 이 문서를 바꾸면 관련 파트의 테스트 벡터도 같은 PR에서 바꿉니다. 제품 규칙은 [개요](../kickoff.md) 5장에 있습니다.

## 1. 식별자

| 이름 | 형식 | 만드는 곳 | 비고 |
| --- | --- | --- | --- |
| `user_id` | UUID | API | 계정 |
| `device_id` | UUID | API | MVP는 계정당 활성 기기 1대 |
| `group_id` | UUID | API | 그룹(`circle`)과 1:1(`dm`) 모두. MLS 그룹 ID도 이 16바이트를 그대로 씀 |
| `seq` | 64비트 정수 | API | 그룹 안 순번. 커밋과 메시지가 한 줄로 이어짐 |
| `epoch` | 64비트 정수 | MLS | 커밋이 수락될 때마다 1씩 증가 |
| `appt`, 메시지 `id` | UUIDv7 | 기기 | 약속과 메시지의 ID. 시간순 정렬 가능 |

## 2. MLS 사용법

- **암호 조합:** `MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519`(0x0003).
- **자격 증명:** Basic credential. 내용은 `"{user_id}:{device_id}"` ASCII 문자열입니다.
- **추가 전 확인:** 다른 기기를 그룹에 넣는 기기는, 키 패키지의 자격 증명과 서명 공개키가 서버의 기기 목록(`GET /users/{id}/devices`)과 일치하는지 확인합니다. 서버가 가짜 기기를 끼워 넣는 공격은 이 확인으로 막지 못하며, [개요](../kickoff.md) 6장의 한계에 적혀 있습니다.
- **메시지 형식:** 커밋은 공개 메시지(PublicMessage), 채팅·날짜 잡기는 비공개 메시지(PrivateMessage)로 보냅니다. 멤버십은 서버가 이미 아는 정보라서 커밋을 숨길 이유가 없고, 공개해 두면 2단계에서 서버가 커밋을 검증할 수 있습니다.
- **그룹 생성:** 만든 사람의 기기가 `group_id` 16바이트를 MLS 그룹 ID로 그룹을 만듭니다.
- **멤버 추가:**
  1. 초대 코드로 들어온 사람은 `pending`이 됩니다.
  2. 온라인인 활성 멤버 기기 하나가 그 사람 기기의 키 패키지를 하나 가져와 Add 커밋과 Welcome을 만들어 함께 올립니다.
  3. 서버가 커밋을 수락하면 그 멤버는 `active`가 됩니다.
  4. 커밋한 기기는 이어서 새 멤버용 번들(5장)을 올립니다.
- **멤버 제거:** 탈퇴·강퇴가 일어나면 서버가 멤버 상태를 `removed`로 바꾸고 `membership` 이벤트를 보냅니다. 이를 먼저 처리한 활성 멤버 기기가 Remove 커밋을 올리고, 중복 커밋은 서버의 선점 규칙(6장)이 막습니다.
- **기기 교체:** 같은 사람이 기기를 바꾸면(13장) 멤버 상태는 그대로 두고, 서버가 이전 기기에 `revoke`, 그룹의 다른 멤버 기기에 `membership`을 보냅니다. 새 기기는 외부 가입으로 이전 leaf를 자기 leaf로 바꿉니다. 그보다 먼저 `membership`을 처리한 멤버 기기는, 멤버 목록의 활성 기기와 다른 그 사람의 leaf를 Remove 커밋으로 지웁니다. 어느 쪽이 먼저든 선점 규칙이 커밋 하나만 받습니다.
- **자기 갱신:** 각 기기는 마지막으로 자기 leaf를 갱신한 지 7일이 지나면 빈 커밋으로 갱신합니다. 기기가 털렸다가 회복된 뒤에도 이후 메시지가 다시 안전해집니다.
- **외부 가입(같은 사람의 새 기기):**
  - 커밋한 기기는 새 epoch의 GroupInfo를 커밋과 함께 올립니다.
  - 기기를 바꾼 사람이나 30일 넘게 따라잡지 못한 기기는, 이 GroupInfo로 외부 커밋을 만들어 스스로 다시 들어옵니다. 이전 leaf 제거와 새 leaf 추가가 커밋 하나에 들어갑니다.
  - 서버는 작성자가 그 그룹의 활성 멤버이고 현재 기기일 때만 외부 커밋을 받습니다.
- **충돌:** 커밋이 409를 받으면 동기화한 뒤 대기 중 커밋을 버리고, 아직 필요하면 새 epoch에서 다시 만듭니다.
- **MLS 상태는 백업하지 않습니다.** 복원한 상태로 메시지를 보내면 같은 키와 논스를 다시 쓸 위험이 있습니다. 새 기기는 외부 가입으로 새 leaf를 얻습니다.

## 3. 서버에 저장되는 것의 종류

| 종류 | 무엇 | 암호화 | 서버 보관 |
| --- | --- | --- | --- |
| 로그(우편함) | 커밋, 애플리케이션 메시지(채팅·날짜 잡기·투표) | MLS | 그룹 순번대로 30일 |
| Welcome | 새 기기 하나에 보내는 초대 | MLS | 가져가면 삭제 |
| GroupInfo | 외부 가입용 공개 그룹 정보 | 서명만 | 그룹마다 최신 1개 |
| 상태 블롭(state blob) | 가용 시간, 그룹 메타, 멤버 프로필, 새 멤버용 번들 | 상태 키 + XChaCha20-Poly1305 | (그룹, 종류, 주인)마다 최신 1개 |
| 미디어 | 채팅·약속·개인 일정의 사진·파일, 그룹 커버, 프로필 사진 | 파일마다 무작위 키, 청크 AEAD | 범위마다 다름(9장) |
| 백업 | 캘린더 + 채팅 텍스트 아카이브 | AMK, 청크 AEAD | 최근 3개 |
| 복구 자료 | 래핑된 AMK, 복구 ID, 복구 공개키(10장) | 복구 코드에서 Argon2id로 유도한 키 | 계정마다 1개 |

## 4. 엔트리

애플리케이션 메시지 하나에는 엔트리가 정확히 하나 들어갑니다. 엔트리는 CBOR로 인코딩한 `Envelope { v: 1, entry }`입니다.

```rust
// core/geunal-domain/src/entry.rs — every MLS application message in a group carries exactly one Entry, CBOR-encoded.
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Mode { Vote, Dictator }

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Stage { Time, Activity }

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Choice { Opt(String), Any }

// Times are minutes after 00:00 Asia/Seoul on `day`; an appointment never crosses midnight in the MVP.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(tag = "k", rename_all = "snake_case")]
pub enum Entry {
    Text { id: String, body: String, reply_to: Option<String> },
    // `appt` set: an attachment of that appointment, not a chat message. `name` is the file name shown for files.
    Media { id: String, object: String, key: Vec<u8>, size: u64, mime: String, sha256: Vec<u8>, thumb: Option<Vec<u8>>, name: Option<String>, appt: Option<String> },
    MediaRemove { id: String },
    // `leader_options` copies the group setting at request time so every device applies the same rule to this appointment.
    Request { appt: String, day: String, mode: Mode, start: Option<u16>, end: Option<u16>, activity: Option<String>, note: Option<String>, #[serde(default)] leader_options: bool },
    RequestUpdate { appt: String, mode: Option<Mode>, start: Option<u16>, end: Option<u16>, activity: Option<String>, note: Option<String> },
    LeadTransfer { appt: String, to: String },
    Response { appt: String, accept: bool },
    Poll { appt: String, stage: Stage, options: Vec<String> },
    PollOption { appt: String, stage: Stage, option: String },
    PollVote { appt: String, stage: Stage, choice: Choice },
    PollClose { appt: String, stage: Stage },
    Cancel { appt: String },
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
pub struct Envelope { pub v: u8, pub entry: Entry }

pub fn encode(entry: &Entry) -> Vec<u8> {
    let mut out = Vec::new();
    ciborium::into_writer(&Envelope { v: 1, entry: entry.clone() }, &mut out).expect("in-memory write");
    out
}

pub fn decode(bytes: &[u8]) -> Result<Entry, ciborium::de::Error<std::io::Error>> {
    ciborium::from_reader::<Envelope, _>(bytes).map(|e| e.entry)
}
```

규칙:
- **작성자:** MLS가 확인한 발신자 leaf의 자격 증명에서 `user_id`를 읽습니다. 엔트리 안에는 넣지 않으므로 위조할 수 없습니다.
- **주도자 전용:** `RequestUpdate`, `LeadTransfer`, `Poll`, `PollClose`, `Cancel`은 현재 주도자가 쓴 것만 유효합니다. 상태 함수가 검사하고, 아니면 무시합니다.
- **첨부 빼기:** `MediaRemove`는 그 `Media`를 쓴 사람이 쓴 것만, 그리고 `appt`가 있는 첨부에만 유효합니다. 채팅 메시지 삭제는 아직 없습니다.
- **약속 첨부 개수:** 약속마다 빠지지 않은 첨부 중 로그 순서로 앞의 3개만 유효합니다. 기기도 3개가 차 있으면 올리지 않습니다.
- **그룹 설정은 신청에 담습니다:** 상태 블롭은 로그 밖에 있어서 기기마다 받은 시점이 다를 수 있습니다. 그래서 상태 함수는 `group_meta`를 읽지 않습니다. 선택지 추가 권한은 신청할 때 `Request.leader_options`로 복사하고, 참이면 `PollOption`은 주도자가 쓴 것만 유효합니다. 기본 결정 방식과 시간 추천 범위는 신청하는 기기의 화면 기본값일 뿐이고, 결과는 `Request.mode`와 `Poll.options`에 이미 들어 있습니다.
- **시각:** 모든 시각 판정(투표 마감, 약속 날 00:00)은 서버가 엔트리를 받은 시각(`received_at`)으로 합니다. 기기 시계는 화면 표시에만 씁니다.
- **모르는 종류:** 새 버전의 엔트리는 원문 그대로 저장하고 "업데이트가 필요한 메시지"로 보여 줍니다.
- **크기:** "토요일 7시 어때?" 엔트리는 UUIDv7 `id`를 넣으면 CBOR로 97바이트입니다(`core/vectors/entries.json`의 `text` 항목). 엔트리 하나는 64KB를 넘지 않고, 큰 내용은 미디어로 보냅니다.

## 5. 상태 블롭

가용 시간처럼 최신값만 의미 있는 데이터는 로그가 아니라 상태 블롭으로 덮어씁니다. MLS 메시지는 한 번만 복호화할 수 있지만, 상태 블롭은 그 epoch의 멤버라면 누구나 몇 번이든 읽을 수 있습니다.

- **키:** `MLS-Exporter("geunal state v1", "", 32)`. epoch마다 바뀝니다.
- **AAD:** `state|{group_id}|{epoch}|{kind}|{owner_id}`. 서버가 블롭을 다른 자리로 옮기지 못하게 합니다.
- **형식:** 무작위 논스(24바이트) ‖ 암호문 ‖ 태그(16바이트).

| 종류(`kind`) | 주인 | 평문(CBOR) | 언제 씀 |
| --- | --- | --- | --- |
| `availability` | 멤버 | `{window_start, days, bits}`: 30분마다 1비트, 1은 일정 없음, MSB부터 | 캘린더가 바뀔 때, epoch가 바뀔 때 |
| `group_meta` | 그룹당 하나. 마지막으로 봉인한 멤버 | `{name, color, cover?, plan, changed_by, changed_at}`. `cover`는 `{object, key, sha256}`, `plan`은 날짜 잡기 기본값 `{mode: Mode, window: [from, to], options_by}`(`window`는 30분 칸 번호, `options_by`는 `anyone` 또는 `leader`) | 멤버 누구나 공통 설정을 바꿀 때. epoch를 바꾼 커밋을 한 기기가 다시 봉인 |
| `member_profile` | 멤버 | `{nickname?, avatar?, birthday_md?, lunar?, tags?}`. `avatar`는 `{object, key, sha256}` | 그룹 프로필이나 공개 설정을 바꿀 때, epoch가 바뀔 때 |
| `newcomer_bundle` | 추가 커밋을 한 기기의 주인 | 날이 지나지 않은 약속의 엔트리 목록 `[(seq?, author_id, received_at, Entry)]`. `seq`는 보낸 기기가 아는 서버 순번이고, 받는 기기는 그 자리에 항목을 끼워 자기 로그와 순서를 맞춤. 없으면 로그 앞에 둠 | 멤버를 추가한 직후 |

**그룹 메타는 공용 자리입니다.** 멤버 누구나 바꿀 수 있어서 서버는 그룹마다 한 줄만 두고 버전을 셉니다.
- 쓰는 기기는 읽었던 버전(`base_version`)을 함께 보냅니다. 그사이 다른 멤버가 바꿨으면 409 `state_conflict`를 받고, 최신 블롭을 읽어 자기가 바꾼 항목만 다시 얹은 뒤 재시도합니다.
- AAD의 `owner_id`는 그 블롭을 봉인한 멤버입니다. 화면에 보이는 "마지막으로 바꾼 사람"은 평문의 `changed_by`이고, epoch 때문에 다시 봉인한 기기는 이 값을 그대로 둡니다.

epoch 규칙:
- 쓰는 쪽은 epoch가 바뀌면 자기 블롭을 새 키로 다시 봉인합니다.
- 읽는 쪽은 최근 30일(그룹당 최대 50개) epoch의 상태 키를 보관해서, 아직 다시 봉인되지 않은 블롭도 읽습니다. 키가 없는 블롭의 주인은 그룹 캘린더에서 불참으로 보입니다.

**새 멤버용 번들의 한계:** 번들은 그룹 키로만 봉인되므로, 번들 안 엔트리의 작성자 표시는 번들을 쓴 멤버를 믿어야 합니다. 친구 그룹이라는 전제에서 받아들이고, 2단계에서 원래 서명을 함께 넘기는 방식으로 바꿉니다.

**크기:** 8주 가용 시간 블롭은 376바이트입니다.

## 6. 순서와 동기화

```mermaid
sequenceDiagram
  participant A as 보내는 기기 (코어)
  participant API as API 서버
  participant DB as PostgreSQL
  participant R as Redis
  participant GW as 게이트웨이
  participant B as 받는 기기 (접속 중)
  participant C as 받는 기기 (접속 안 함)
  A->>API: POST /groups/{g}/messages (epoch, MLS 암호문, alert)
  API->>DB: epoch 확인 후 seq 부여, 저장
  API-->>A: 201 {seq}
  API->>R: PUBLISH d:{device} {entry, g, seq, c?}
  R->>GW: 구독 중인 기기 채널
  GW->>B: entry 이벤트
  B->>B: seq 빈틈 확인, 복호화, 저장
  API->>C: APNs/FCM (암호문 또는 신호)
  C->>API: 앱을 열면 GET /groups/{g}/log?after={cursor}
```

- **순번:** 서버가 그룹마다 순번(`seq`)을 매기고, 기기는 순번 순서대로 처리합니다. 순서는 MLS 처리 → 엔트리 디코드 → 로컬 저장 → 상태 함수 재계산입니다.
- **커서:** 기기는 그룹마다 마지막으로 처리한 순번을 갖고, `GET /groups/{id}/log?after={cursor}`로 따라잡습니다.
- **커밋 선점:** 서버는 현재 epoch에 대한 커밋 하나만 받습니다. 같은 epoch의 두 번째 커밋은 409 `epoch_conflict`입니다.
- **오래된 메시지 거절:** 서버는 현재 epoch가 아닌 메시지를 409 `stale_epoch`로 거절합니다. 보낸 기기는 대기 중인 커밋을 처리하고 새 epoch로 다시 암호화해 보냅니다. 그래서 받는 쪽은 지난 epoch의 비밀을 보관할 필요가 없습니다.
- **빈틈:** 게이트웨이나 푸시로 받은 순번이 커서 + 1보다 크면, 사이의 항목을 API에서 가져온 뒤 처리합니다.
- **30일 초과:** 커서가 우편함 보관 기간보다 오래되면 API가 410 `cursor_expired`를 줍니다. 기기는 그 그룹의 MLS 상태를 버리고 외부 가입(2장)으로 다시 들어옵니다. 로컬에 저장된 평문 기록은 그대로 남습니다.

## 7. 실시간 채널

게이트웨이는 Phoenix Channels(V2 JSON 직렬화)를 씁니다.

- **연결:** `wss://rt.<도메인>/socket/websocket?vsn=2.0.0&token=<access JWT>`
- **토픽:** `device:{device_id}` 하나. 자기 기기 토픽만 들어갈 수 있습니다.
- **기기 → 서버:** 하트비트 말고는 없습니다. 쓰기는 모두 API로 합니다.
- **하트비트:** 30초. 끊기면 지수 백오프(1초부터 최대 60초)로 다시 붙고, 붙으면 커서로 따라잡습니다.

| 서버 → 기기 이벤트 | 페이로드 | 기기가 할 일 |
| --- | --- | --- |
| `entry` | `{g, s, kind, at, c?}`: 그룹, 순번, `commit`/`app`, 서버가 받은 시각(`received_at`, RFC 3339 UTC), 암호문(4KB 이하일 때만) | `c`가 있으면 `at`을 `received_at`으로 삼아 바로 처리, 없거나 빈틈이 있으면 API에서 가져오기 |
| `state` | `{g, kind, owner, epoch}` | 해당 상태 블롭 가져오기 |
| `membership` | `{g}` | 멤버 목록 다시 가져오기, 필요하면 Remove 커밋 |
| `welcome` | `{}` | `GET /me/welcomes` |
| `revoke` | `{}` | 연결 종료, 로그아웃 |

API가 게이트웨이에 신호를 보낼 때는 Redis 채널 `d:{device_id}`에 같은 JSON을 `{"t": "entry", ...}` 형태로 PUBLISH합니다. 게이트웨이는 자기에게 붙은 기기의 채널만 구독합니다.

## 8. 푸시

- **보내는 대상:** 메시지를 올릴 때 기기가 `alert` 값을 정합니다.

  | `alert` | 쓰는 엔트리 |
  | --- | --- |
  | `"all"` | 채팅, 날짜 잡기 신청, 투표 열기·닫기, 취소 |
  | `["user_id", ...]` | 수락·다음에(주도자에게), 시간·활동 변경과 약속 첨부(참가자에게) |
  | `"none"` | 투표, 선택지 추가, 첨부 빼기 |

- **종류(`cat`):** `alert`가 `none`이 아니면 기기가 `chat`(텍스트, 사진·파일) 또는 `plan`(날짜 잡기 신청·응답·변경·취소, 투표 열기·닫기, 약속 첨부)을 함께 보냅니다. 종류별 알림 끄기를 위해 서버가 아는 유일한 내용 정보입니다.
- **빼는 대상:** 서버는 보낸 사람 자신, 전체 알림을 끈(`muted_until`) 멤버, 그 종류를 끈(`notify_chat`, `notify_plan`) 멤버를 뺍니다.
- **페이로드:** `{"g": group_id, "s": seq, "at": received_at, "c": base64 암호문}`. `at`은 실시간 `entry` 이벤트와 같은 서버 수신 시각이고, `c`는 암호문이 3KB 이하일 때만 넣습니다.
- **iOS:** `aps.alert`에 "새 메시지" 같은 자리표시 문구와 `mutable-content: 1`을 넣습니다. 알림 확장이 복호화해서 문구를 바꿉니다.
- **안드로이드:** 우선순위 높은 데이터 전용 메시지로 보내고, 앱의 핸들러가 알림을 만듭니다.
- **`c`가 없을 때:** 알림 확장이나 핸들러가 API에서 그 항목을 가져와 복호화합니다(30초 안).

## 9. 미디어

1. 보내는 기기가 파일마다 무작위 32바이트 키를 만들고, 64KiB 청크로 나눠 STREAM 구성(RustCrypto `aead::stream`, XChaCha20-Poly1305, BE32)으로 암호화합니다.
2. 범위에 맞는 엔드포인트에서 업로드 URL과 `object` 키를 받아 암호문을 올립니다. 서버는 파일 형식을 모르고 크기와 범위만 압니다.
3. 키는 아래 표의 자리에 둡니다. `Media` 엔트리에는 `object`, 키, 크기, MIME, 암호문 SHA-256, 썸네일(8KB 이하), 파일 이름을 넣습니다.
4. 받는 기기는 `GET /media/{object}`로 다운로드 URL을 받아 내려받고, SHA-256을 확인한 뒤 복호화합니다.
5. 지우기는 올린 사람만 합니다(`DELETE /media/{object}`). 보관 기간이 지나면 서버가 지우고, 버킷 수명 규칙이 한 번 더 지웁니다.

| 범위(`scope`) | 올리기 | 키가 있는 곳 | 크기 | 서버 보관 |
| --- | --- | --- | --- | --- |
| `chat` | `POST /groups/{id}/media` | `Media` 엔트리 | 10MB 이하 | 30일 |
| `appt` | `POST /groups/{id}/media` | `Media` 엔트리(`appt` 있음) | 10MB 이하 | 1년 |
| `personal` | `POST /me/attachments` | 내 로컬 저장소와 백업 | 10MB 이하 | 1년 |
| `cover` | `PUT /groups/{id}/cover`(멤버 누구나) | `group_meta` | 1MB 이하 | 새 커버를 올리면 이전 것은 7일 뒤 |
| `avatar` | `PUT /groups/{id}/members/me/avatar` | `member_profile` | 256KB 이하 | 새 사진을 올리면 이전 것은 7일 뒤 |

- **TimeTree와 같은 제한:** 파일당 10MB, 일정·약속 하나에 3개, 형식은 PDF·JPEG·PNG·HEIC·GIF·TXT·DOCX·XLSX·PPTX. 서버는 형식을 모르므로 크기만 검사하고, 형식과 개수는 기기가 검사합니다(약속 첨부 개수는 4장 규칙으로도 막음).
- 커버와 프로필 사진은 자리마다 하나만 남깁니다. 이전 것을 7일 남기는 이유는, 새 블롭이 올라오기 전까지 다른 기기가 이전 블롭을 읽기 때문입니다.

## 10. 복구 코드와 백업

복구 코드는 백업을 여는 유일한 열쇠이고, 게스트에게는 다른 기기로 돌아오는 유일한 방법입니다(13장).

### 복구 코드

- **엔트로피:** 기기가 16바이트 난수 `E`를 만듭니다. 복구 코드는 `E`를 BIP39 방식으로 적은 12단어입니다. `E` 뒤에 SHA-256(`E`)의 앞 4비트를 붙인 132비트를 앞에서부터 11비트씩 끊고, 각 값(0–2047)을 단어 목록의 번호로 씁니다.
- **단어 목록:** BIP39 한국어 목록(bitcoin/bips의 `bip-0039/korean.txt`, 2048개, MIT)을 순서 그대로 씁니다. 원본 파일의 SHA-256은 `9e95f86c167de88f450f0aaf89e87f6624a57f973c67b516e338e8e8b8897f60`입니다.
  - 원본은 NFKD(자모를 나눈 형태)로 적혀 있습니다. 화면에 보일 때와 입력과 비교할 때는 NFC(완성형 음절)로 바꿉니다.
  - 모든 단어가 앞 두 음절로 구별되고, 다른 단어의 앞부분이 되는 단어는 없습니다. 그래서 자동 완성은 두 음절을 넣으면 하나로 정해집니다.
- **입력 확인:** 목록에 없는 단어는 그 칸에서 바로 알립니다. 12단어가 모두 목록에 있어도 체크섬 4비트가 맞지 않으면 틀린 코드입니다. 체크섬은 16번에 1번꼴로 우연히 맞으므로, 최종 판정은 AMK가 풀리는지로 합니다.
- **암호에 넣는 값:** 단어 문자열이 아니라 `E` 16바이트입니다. 같은 단어가 정규화 차이로 다른 키가 되는 일을 막으려는 것입니다. 이 코드는 BIP39 지갑 시드가 아니며, BIP39의 PBKDF2 시드 유도는 쓰지 않습니다.

| 값 | 만드는 법 | 서버에 가는 것 |
| --- | --- | --- |
| AMK | 32바이트 난수 | 없음 |
| `K` | Argon2id(비밀번호 `E`, salt `geunal recovery v1`(ASCII 18바이트), 메모리 64MiB, 3회, 병렬 1, 32바이트) | 없음 |
| 래핑 키 | HKDF-SHA256-Expand(PRK `K`, info `geunal amk wrap v1`, 32바이트) | 없음 |
| 래핑된 AMK | XChaCha20-Poly1305(래핑 키, AAD `geunal amk v1`, 평문 AMK). `nonce(24) ‖ 암호문(32) ‖ 태그(16)`, 72바이트 | 그대로 |
| 복구 ID | HKDF-SHA256-Expand(`K`, info `geunal recovery id v1`, 16바이트) | 그대로 |
| 복구 서명 키 | Ed25519. 시드는 HKDF-SHA256-Expand(`K`, info `geunal recovery sign v1`, 32바이트) | 공개키만 |

- **Argon2id는 한 번:** 서버에 가는 값이 모두 `K`에서 나옵니다. 그래서 서버 DB를 가진 사람도 `E` 후보마다 64MiB Argon2id를 돌려야 맞는지 시험할 수 있습니다. 12단어 중 일부가 드러난 경우를 생각한 것입니다. 남은 단어가 4개면 후보는 약 2^40개입니다.
- **salt를 고정하는 이유:** 계정마다 다른 salt는 사전 계산을 막으려는 것인데, 128비트 `E`는 사전 계산이 불가능합니다. 게스트 복귀는 계정을 모르는 채로 `K`를 구해야 하므로 계정별 salt를 쓸 수도 없습니다.
- **보관:** `E`와 AMK는 기기의 `SecretStore`에만 둡니다. "종이에 적었어요"를 눌렀는지도 기기에만 기록합니다.
- **올리는 때:** 로그인 결과의 `recovery`가 거짓이면(새 계정이거나 아직 복구 자료가 없는 계정) 기기가 `E`와 AMK를 만들고 `PUT /me/recovery`로 복구 자료를 올립니다. 성공할 때까지 다시 시도하고, 그 전에는 백업하지 않습니다. 복원한 기기는 입력한 코드의 `E`와 푼 AMK를 그대로 씁니다.
- **덮어쓰기에는 지금 코드의 증명:** 서버에 복구 자료가 이미 있으면, `PUT /me/recovery`에 `nonce`(`GET /auth/nonce`)와 증명을 함께 보냅니다. 증명은 지금 복구 서명 키로 `"geunal recovery rotate v1" ‖ nonce`에 한 서명입니다.
  - 세션만 새어 나간 사람이 복구 자료를 자기 것으로 바꿔 계정을 빼앗지 못하게 하려는 것입니다.
  - 첫 업로드를 재시도하다 이미 저장돼 있던 경우도 같은 증명으로 덮어씁니다.
  - 증명이 없거나 틀리면 403 `recovery_proof_required`입니다. 복구 ID가 다른 계정의 것과 같으면 409 `recovery_conflict`입니다.
- **새 키로 시작:** 증명 없이 덮어쓸 수 있는 경우는 하나입니다. 카카오·Apple 계정에서 복구 코드를 잃어 "백업 없이 새로 시작"하는 기기입니다.
  - 조건은 `new_key: true`, 그리고 그 기기가 제공자 로그인으로 등록된 지 30분 안이어야 합니다(교체 직후).
  - 기기는 새 `E`와 AMK를 만들고, 서버는 같은 트랜잭션에서 이 계정의 백업을 모두 지웁니다. 이전 AMK로 잠긴 백업은 아무도 열 수 없기 때문입니다.
  - 게스트에게는 이 예외가 없습니다. 코드를 잃은 게스트는 어차피 다른 기기로 돌아올 수 없습니다.

### 백업

- **내용:** 캘린더 테이블, 사람·생일, 교대 템플릿, 설정, 채팅 텍스트 기록을 CBOR 아카이브로 묶습니다.
  - 사진·파일 자체, 기기 캘린더에서 가져온 일정, MLS 상태, 상태 블롭은 넣지 않습니다.
  - 개인 일정 첨부의 키와 `object` 키는 넣어서, 복원한 기기가 서버 보관본을 다시 받습니다.
  - 아카이브의 필드와 버전은 [Rust 코어](rust-core.md)가 정합니다. 새 앱은 이전 버전의 아카이브를 읽을 수 있어야 합니다.
  - 아카이브 안(암호문 안)에 기기가 만든 시각을 넣고, 복원 화면의 백업 시각은 그 값을 보여 줍니다. 서버나 스토리지에 쓸 수 있는 사람이 옛 백업을 최신으로 내줘도 사용자가 알아챌 수 있게 하려는 것입니다.
- **암호화:** 9장의 STREAM 구성입니다. 키는 HKDF-SHA256-Expand(PRK AMK, info `geunal backup key v1`, 32바이트), AAD는 `geunal backup v1`입니다. AMK를 바로 쓰지 않는 것은 나중에 AMK를 다른 곳에도 쓸 때 키가 섞이지 않게 하려는 것입니다. 올리는 객체는 `nonce 접두사(19) ‖ 청크들` 그대로입니다.
- **올리기:**
  1. `POST /me/backups`에 크기를 보내 백업 ID와 업로드 URL(15분)을 받습니다. 64MiB까지입니다. 업로드 URL은 선언한 크기로만 올릴 수 있게 서명합니다(서명에 `Content-Length`를 넣음).
  2. 그 URL에 암호문을 `PUT`합니다.
  3. `POST /me/backups/{id}/complete`로 완료를 알립니다. 서버는 객체가 있고 크기가 같은지 확인한 뒤, 사람마다 완료된 백업을 최근 3개만 남깁니다.
  - 완료하지 않은 업로드는 목록에 나오지 않고, 서버가 하루 뒤 지웁니다.
  - 복구 자료가 없는 계정은 409 `recovery_missing`입니다.
- **자동 백업:** 앱이 와이파이에서 하루 한 번, 마지막 백업 뒤 바뀐 것이 있을 때만 올립니다.
- **목록:** `GET /me/backups`는 백업마다 ID, 크기, 시각만 줍니다. "자동·직접"과 담긴 일정 수는 기기가 자기 기록으로 보여 줍니다. 서버가 내용의 양을 알 이유가 없기 때문입니다.

### 복원

1. 로그인합니다(13장). 다른 기기가 쓰고 있는 계정이면 교체 티켓을 받습니다. 게스트는 복구 코드에서 `K`를 구해 복구 서명으로 들어옵니다.
2. `GET /me/recovery`로 래핑된 AMK를 받습니다.
3. 복구 코드에서 `E`를, Argon2id로 `K`를, HKDF로 래핑 키를 얻어 AMK를 풉니다. 풀리지 않으면 코드가 틀린 것입니다.
4. `GET /me/backups/latest`로 다운로드 URL을 받아 내려받고, 복호화해 로컬 저장소를 채웁니다. 백업이 없으면(404) 이 단계를 건너뜁니다.
   - 코어는 로컬 저장소에 주인 계정의 `user_id`를 적어 둡니다.
   - 로그아웃됐던 기기가 다시 들어오는 경우처럼 같은 계정의 저장소에 이미 일정이 있으면 합칩니다. 같은 ID는 백업 쪽을 쓰고, 백업에 없는 로컬 항목은 남깁니다.
   - 다른 계정의 저장소면 합치지 않습니다. 남은 일정을 새 계정으로 가져올지 지울지 앱이 묻습니다. 함께 쓰거나 넘겨받은 기기에서 다른 사람의 일정이 섞이지 않게 하려는 것입니다.
5. 교체 티켓이 있으면 `POST /auth/replace`로 이 기기를 활성 기기로 만듭니다. 이전 기기는 로그아웃됩니다.
6. 그룹마다 외부 가입으로 다시 들어갑니다(2장).

## 11. API 오류 규약

모든 오류 본문은 `{"error": "<code>", "message": "..."}` 형식입니다.

| HTTP | `error` | 기기가 할 일 |
| --- | --- | --- |
| 400 | `invalid_request` | 요청 형식이 틀렸음(필드 누락, MLS 헤더와 요청 불일치). 고치기 전에는 재시도하지 않음 |
| 401 | `token_expired` | 리프레시 토큰으로 갱신 후 재시도. 교체 티켓이면 로그인부터 다시 |
| 401 | `provider_token_invalid` | 카카오·Apple 토큰이 검증되지 않음(서명, `aud`, 만료, nonce). 제공자 로그인부터 다시 |
| 401 | `recovery_invalid` | 게스트 복귀 실패. 복구 코드를 다시 확인하게 함 |
| 403 | `not_member`, `not_owner`, `blocked`, `device_revoked` | 화면에 알리고 중단. `device_revoked`면 로그아웃. 기기 교체로 해제됐으면 본문에 `replaced_at`, `replaced_by`, `replaced_via`가 더 있음(13장) |
| 403 | `replace_required` | 교체 티켓으로는 할 수 없는 요청. 기기 교체를 마친 뒤 다시 |
| 403 | `recovery_proof_required` | 복구 자료를 덮어쓰려면 지금 코드의 증명이 필요함(10장). 증명을 붙여 다시 |
| 403 | `reauth_required` | 계정 삭제에 다시 증명이 필요함(13장). 제공자 로그인이나 복구 서명을 붙여 다시 |
| 404 | `not_found` | 중단 |
| 409 | `epoch_conflict` | 동기화 후 커밋을 다시 만들어 재시도 |
| 409 | `stale_epoch` | 대기 커밋 처리 후 다시 암호화해 재시도 |
| 409 | `state_conflict` | 그룹 메타를 다시 읽고 내 변경을 얹어 재시도 |
| 409 | `account_exists`, `already_linked` | 연결하려는 카카오·Apple 계정이 이미 다른 계정에 쓰이거나, 이미 연결한 계정. 화면에 알리고 중단 |
| 409 | `recovery_missing` | 복구 자료를 먼저 올린 뒤 백업이나 계정 삭제를 다시 |
| 409 | `recovery_conflict` | 복구 ID가 다른 계정의 것과 같음. 같은 `E`가 두 계정에 쓰였다는 뜻이라, 새 `E`를 만들고 다시 |
| 409 | `upload_incomplete` | 백업 객체가 없거나 크기가 다름. 다시 올리기 |
| 410 | `cursor_expired` | 외부 가입으로 다시 들어오기 |
| 413 | `too_large` | 미디어로 보내기 |
| 429 | `rate_limited` | `Retry-After`만큼 기다린 뒤 재시도 |
| 500 | `internal` | 지수 백오프로 재시도 |

바이트 값(암호문, 키, MLS 메시지)은 JSON에서 표준 base64(패딩 있음) 문자열입니다. 요청·응답 본문의 모양은 `server/api/openapi.yaml`에 있습니다.

## 12. 테스트 벡터

| 벡터 | 만드는 곳 | 검증하는 곳 |
| --- | --- | --- |
| MLS 헤더(커밋·메시지의 그룹 ID, epoch, 형식) | Rust 코어 | API 서버의 Kotlin 헤더 파서 |
| 엔트리 CBOR | Rust 코어 | Rust 코어(버전 간 호환) |
| 상태 블롭 AAD와 형식 | Rust 코어 | Rust 코어 |
| 실시간 이벤트 JSON | API 서버 | 게이트웨이, Rust 코어 |
| 푸시 페이로드 | API 서버 | Rust 코어(`decrypt_push`) |
| 복구 코드(단어 목록 해시, `E`와 12단어, `K`, 래핑 키와 고정 논스의 래핑된 AMK, 복구 ID, 복구 공개키와 게스트 복귀·덮어쓰기 서명) | Rust 코어 | Rust 코어, API 서버(복구 서명 확인) |
| 로그인 nonce 묶기(`nonce`, `signature_key` → 제공자에게 넘기는 값) | Rust 코어 | API 서버 |
| 백업 객체(고정 AMK·논스 접두사로 봉인한 아카이브, 아카이브 버전마다) | Rust 코어 | Rust 코어(버전 간 호환) |
| 로그인 결과 JSON(`registered`, `replace_required`) | API 서버 | Rust 코어 |

벡터는 `core/vectors/*.json`에 두고, 다른 파트의 CI가 같은 파일을 읽습니다.

## 13. 계정과 기기

계정당 활성 기기는 1대입니다. 로그인이 곧 기기 등록이고, 다른 기기가 쓰는 계정이면 교체를 거칩니다.

### 로그인 방법

| 방법 | 엔드포인트 | 계정을 찾는 값 | 비고 |
| --- | --- | --- | --- |
| 카카오 | `POST /auth/kakao` | ID 토큰의 `sub` | OIDC ID 토큰. 동의 항목은 닉네임까지 |
| Apple | `POST /auth/apple` | 아이덴티티 토큰의 `sub` | `aud`는 iOS 번들 ID 또는 안드로이드 웹 로그인용 서비스 ID |
| 게스트 | `POST /auth/guest` | 없음(늘 새 계정) | 나중에 `POST /auth/link`로 카카오·Apple에 연결 |
| 게스트 복귀 | `POST /auth/recover` | 복구 ID(10장) | 게스트 계정만. 복구 서명 키의 서명으로 확인 |
| 스텁 | `POST /auth/stub` | `subject` | 로컬·CI 전용. 카카오와 같은 규칙 |

모든 로그인 요청에 들어가는 값:
- **기기 증명:** `platform`, `signature_key`(Ed25519 공개키), `nonce`(`GET /auth/nonce`), `signature`(nonce의 ASCII 바이트에 대한 서명). M0와 같습니다.
- **nonce 묶기:** 카카오·Apple 로그인은 제공자 로그인의 nonce로 `hex(SHA-256("geunal login v1" ‖ nonce ‖ signature_key))`(소문자 16진수)를 넘깁니다.
  - `nonce`는 `GET /auth/nonce`의 값(ASCII 바이트), `signature_key`는 이 기기의 공개키 32바이트입니다.
  - 서버는 요청의 `nonce`와 `signature_key`로 같은 값을 계산해 토큰의 `nonce` 클레임과 비교합니다.
  - 제공자 토큰이 이 기기의 키에 묶입니다. 토큰이나 nonce 원문이 새어도 다른 기기 키로는 로그인하지 못하게 하려는 것입니다.
- **계정을 만들 때만 쓰는 값:** `age_confirmed_at`(만 14세 확인을 체크한 시각, RFC 3339)과 `display_name`(1–40자). 이미 있는 계정이면 무시합니다. 서버는 생년월일을 받지 않습니다.
- **Apple 토큰은 서버에 두지 않습니다.** 계정을 삭제할 때 앱이 Sign in with Apple을 한 번 더 받아 새 `authorization_code`를 `DELETE /me`에 넣습니다(아래 계정 삭제). 오래 사는 Apple 자격 증명과 그 암호화 키를 서버에 두지 않으려는 것입니다.
- **안드로이드의 Apple 로그인:** 웹 방식입니다.
  - Apple이 `POST /auth/apple/callback`으로 보낸 `code`·`id_token`·`state`·`user`를 서버가 App Links 주소(`https://<도메인>/auth/apple`)의 프래그먼트로 앱에 넘깁니다. 앱은 `state`를 확인한 뒤 그 값으로 `POST /auth/apple`을 부릅니다.
  - `code`는 로그인에는 쓰지 않고, 계정 삭제 때 `apple_authorization_code`로만 씁니다. 안드로이드는 Apple 로그인이 웹뿐이라, 삭제 때 새 코드를 받을 길이 이 콜백뿐입니다. 코드는 우리 client secret 없이는 교환할 수 없고, `DELETE /me`는 활성 기기의 세션이 있어야 부를 수 있어서 넘겨도 위험이 늘지 않습니다.
  - 303 `Location`에 넣는 값은 퍼센트 인코딩하고 길이를 제한합니다.
  - 앱이 없을 때 열리는 그 주소의 페이지는 프래그먼트를 읽거나 화면에 그리지 않는 정적 페이지입니다(DOM XSS 방지).

### 로그인 결과

- **`registered`:** 이 기기가 활성 기기가 됐습니다. M0의 토큰에 `new_account`(이번에 계정을 만들었는지)와 `recovery`(서버에 복구 자료가 있는지)가 더해집니다. `new_account`가 거짓이고 `recovery`가 참이면 앱이 복원을 제안합니다.
- **`replace_required`:** 계정에 다른 활성 기기가 있습니다. 서버는 이 기기를 아직 등록하지 않고 교체 티켓(30분)과 지금 활성 기기의 플랫폼·등록 시각을 줍니다.
  - 티켓은 128비트 이상의 불투명 난수이고, 서버는 SHA-256만 저장합니다. JWT가 아니라서 게이트웨이의 JWKS 검증을 통과하지 않습니다.
  - 티켓으로는 `GET /me/recovery`, `GET /me/backups`, `GET /me/backups/latest`만 부를 수 있습니다(`Authorization: Bearer <티켓>`). 나머지는 403 `replace_required`입니다.
  - `POST /auth/replace`에는 티켓과 함께, 로그인 때 증명한 기기 키로 `"geunal replace v1" ‖ 티켓`(ASCII)에 한 서명을 보냅니다. 서버는 그 키로 이 기기를 등록하고 이전 기기를 해제합니다. 새어 나간 티켓만으로는 토큰을 받지 못하게 하려는 것입니다.
  - 교체는 복원을 마친 뒤나 "백업 없이 새로 시작"을 고른 뒤에 합니다.
  - 티켓이 만료되면 401 `token_expired`이고, 로그인부터 다시 합니다.
- **기기 해제:** 서버는 이전 기기의 세션을 지우고 `revoke`를 보냅니다. 이전 기기는 다음 요청에서 403 `device_revoked`를 받고 로그아웃 상태가 됩니다. 그룹에서는 2장의 기기 교체 규칙을 따릅니다.
  - 해제를 받은 기기(403 `device_revoked`나 `revoke` 이벤트)는 계정 삭제 때와 같은 비밀을 지웁니다: 세션, 기기 서명 키, `E`와 AMK, 그룹 상태.
  - 일정·사람·생일과 그것을 여는 데이터베이스 키만 남깁니다. 도난 기기에 남은 `E`로 게스트 계정을 다시 가져가거나, 받아 둔 백업을 풀지 못하게 하려는 것입니다.
  - 기기 교체로 해제된 기기가 받는 403 `device_revoked` 본문에는 세 값이 더 들어갑니다: `replaced_at`(해제된 시각, RFC 3339), `replaced_by`(그 계정의 지금 활성 기기 플랫폼, `ios`·`android`), `replaced_via`(`provider`는 카카오·Apple 로그인, `recovery`는 게스트 복귀).
  - 이 값들은 이전 기기가 "언제 어떤 기기에서, 무엇으로 로그인했는지" 보여 주려고 넣습니다. 내가 한 로그인이 아니면 알아챌 수 있게 하려는 것이고, 게스트는 `recovery`를 보고 내 복구 코드가 쓰였음을 바로 압니다.

### 게스트 복귀

- 게스트는 복구 코드로만 다른 기기에서 돌아올 수 있습니다. `POST /auth/recover`에 복구 ID, 복구 서명, 기기 증명을 보냅니다.
  - 복구 서명은 복구 서명 키(10장)로 `"geunal recover v1" ‖ nonce`에 한 서명입니다. nonce는 기기 증명과 같은 `GET /auth/nonce` 값입니다.
- 서버는 복구 ID로 계정을 찾아 저장된 복구 공개키로 서명을 확인합니다. 계정이 없거나, 서명이 틀리거나, 카카오·Apple에 연결된 계정이면 모두 401 `recovery_invalid`입니다.
  - 서버에는 공개키만 있어서, DB나 요청 기록이 새도 다시 쓸 값이 없습니다.
- 맞으면 교체 티켓 없이 바로 이 기기를 등록하고 이전 기기를 해제합니다. 복구 코드로 이미 증명했기 때문입니다.
- 카카오·Apple에 연결된 계정은 복구 코드만으로 들어올 수 없습니다. 종이에 적은 코드를 누가 봤을 때 계정까지 넘어가지 않게 하려는 것입니다.
- 게스트에게 12단어는 백업 열쇠이자 계정 자격 증명입니다. 코드를 본 사람은 자기 기기로 들어와 원래 기기를 해제할 수 있습니다. 이 위험은 계약으로 막지 못해서, 앱이 할 일(코드를 보여 주기 전 기기 인증, 앱 전환기에서 가리기, 카카오·Apple 연결 권유)을 [위협 모델](../security/threat-model.md)에 둡니다.

### 연결과 계정 정보

- **연결:** 게스트는 `POST /auth/link`로 카카오나 Apple 계정을 하나 연결합니다. nonce 묶기는 로그인과 같고, `signature_key` 자리에는 세션 기기의 등록된 키를 씁니다.
  - 그 제공자 계정이 이미 다른 계정에 쓰이면 409 `account_exists`, 이미 연결한 계정이면 409 `already_linked`입니다.
  - 연결한 뒤에는 게스트 복귀를 쓸 수 없고, 복구 자료와 백업은 그대로입니다.
- **제공자 쪽에서 끊길 때:** 카카오 연결 끊기 알림이나 Apple의 동의 철회 알림을 받으면, 서버는 그 연결에 끊긴 시각을 적습니다. 연결 행은 지우지 않습니다.
  - 끊긴 동안 그 계정은 게스트처럼 복구 코드로 돌아올 수 있습니다. 제공자 계정이 사라진 사람이 돌아올 길을 잃지 않게 하려는 것입니다.
  - 같은 제공자 계정으로 다시 로그인하면 원래 계정으로 돌아오고 끊긴 표시가 지워집니다. 연결을 지우면 다시 로그인했을 때 빈 새 계정이 생기기 때문입니다.
  - 엔드포인트는 보낸 쪽을 확인하고, 확인되지 않으면 아무것도 바꾸지 않습니다. 카카오는 카카오 문서의 확인 방법(요청 헤더의 앱 어드민 키)을 따르고, Apple은 서버 알림 JWT의 서명을 Apple JWKS로 확인하고 `iss`와 `aud`(우리 client ID)도 봅니다. 위조한 알림으로 남의 계정을 제공자에게서 떼어 내지 못하게 하려는 것입니다.
  - 엔드포인트의 경로는 백엔드가 정합니다.
- **`GET /me`:** 계정 종류를 보여 주려고 `providers`(`kakao`, `apple`; 게스트는 빈 배열)와 `recovery`를 함께 줍니다.

### 계정 삭제

- `DELETE /me` → 204. 활성 기기만 부를 수 있고, 계정 종류에 따라 다시 증명합니다. 되돌릴 수 없는 유일한 동작이라, 세션만 훔친 사람이 지우지 못하게 하려는 것입니다.

| 계정 | 본문에 넣는 증명 |
| --- | --- |
| 게스트(제공자 연결이 끊긴 계정 포함) | `nonce`(`GET /auth/nonce`)와 복구 서명 키로 `"geunal delete v1" ‖ nonce`에 한 서명. 코어가 기기의 `E`로 사용자 입력 없이 만듭니다 |
| 카카오 | 삭제 직전에 받은 `kakao_id_token`과 `nonce`. nonce 묶기는 로그인과 같고, `signature_key` 자리에는 이 기기의 등록된 키를 씁니다 |
| Apple | 삭제 직전에 받은 `apple_authorization_code`. 서버가 교환해 받은 아이덴티티 토큰의 `sub`가 이 계정의 것인지 확인합니다 |

- 증명이 없거나, 계정 종류에 맞지 않거나, 검증되지 않으면(다른 계정의 제공자 토큰 포함) 403 `reauth_required`이고 아무것도 지우지 않습니다. 세션은 그대로 유효하므로 기기는 증명만 다시 받습니다.
- 복구 자료를 아직 올리지 못한 게스트는 확인할 공개키가 없어 409 `recovery_missing`입니다. 기기는 복구 자료를 먼저 올린 뒤(10장) 다시 지웁니다.
- 서버는 한 트랜잭션에서 계정의 식별 정보, 기기, 세션, 키 패키지, 복구 자료, 백업, 그룹 멤버십, 상태 블롭, 올린 미디어, 알파 통계를 지웁니다. 오브젝트는 `object_deletions`에 넣고, 남은 멤버의 기기에는 `membership`을 보냅니다.
- **로그 항목은 지우지 않습니다.** 그 사람이 쓴 커밋과 메시지(MLS 암호문)는 우편함 보관 기간 30일이 지나면 파티션과 함께 사라집니다.
  - 지우면, 이미 처리한 기기와 아직 따라잡지 않은 기기의 그룹 상태가 갈라집니다. 지운 커밋 뒤의 epoch는 처리할 수도 없습니다.
  - 계정 행은 식별 정보(제공자 `sub`, 닉네임, 만 14세 확인 시각, 복구 ID)를 지운 자리표시자로 남겨, 로그의 `author_id`가 가리킬 곳을 둡니다. 화면에는 "나간 멤버"로 보입니다.
- 트랜잭션 뒤에 두 가지를 합니다.
  - 카카오: 연결 끊기(앱 어드민 키). 실패하면 다시 시도하는 작업으로 남깁니다.
  - Apple: 요청의 `apple_authorization_code`를 Apple 토큰으로 바꿔 그 자리에서 철회합니다(App Store 지침 5.1.1(v), Apple TN3194). 실패하면 바꿔 받은 토큰을 재시도 작업에만 잠깐 두고, 철회가 끝나면 지웁니다.
- 접속 기록(api-server.md 10장)은 법정 보관 기간 3개월이 지난 뒤 지웁니다. 구독은 스토어에서 따로 해지해야 하므로 앱이 안내합니다(M4).
- 앱은 204를 받으면 세션, 기기 서명 키, AMK와 `E`, 그룹 상태를 지우고, 일정과 사람·생일은 남깁니다.

## 14. 공휴일과 알파 통계

### 공휴일

- `GET /holidays?from_year=YYYY&to_year=YYYY`는 로그인 없이 부를 수 있고, 한 번에 두 해까지입니다. 요청은 늘 해 단위입니다. 해의 일부만 받아 그해를 바꾸면 나머지 공휴일이 지워지기 때문입니다.
- 응답의 `years`는 요청한 해 중 서버가 그해 공휴일을 모두 아는 해이고, `holidays`는 그 해들의 공휴일 전부입니다. 서버가 다 알지 못하는 해(아직 발표되지 않은 다음 해 등)는 `years`에서 빠지고 항목도 오지 않습니다.
- 항목은 `{day, name, substitute}`입니다. 같은 날 공휴일이 둘이면 항목도 둘입니다(2025-05-05 어린이날·부처님오신날).
- 서버는 매일 한국천문연구원 특일 정보 API로 갱신합니다.
- 기기는 앱에 넣어 둔 2026–2027년 표로 시작하고, `years`에 든 해만 통째로 바꿉니다. `years`에 없는 해는 가진 것을 그대로 둡니다. 임시공휴일이 생기거나 빠진 것까지 반영하려는 것입니다.
- 응답에는 `Cache-Control`과 `ETag`가 있고, 기기는 하루 한 번 넘게 부르지 않습니다.
- 요청은 서버에 저장된 표만 읽습니다. 특일 정보 API는 매일 작업만 부릅니다. 누구나 부를 수 있는 엔드포인트가 공공데이터포털 인증키의 하루 한도를 쓰지 못하게 하려는 것입니다.
- `from_year`는 올해 + 1 이하입니다. 레이트 리밋은 IP당 시간당 60회입니다(api-server.md 9장).

### 알파 통계

- 알파 빌드에서 동의한 테스터의 기기만 하루 한 번 `POST /me/alpha-stats`를 부릅니다. 보내지 못한 날은 다음에 함께 보냅니다(최근 14일까지).
- 날마다 `{day, sessions, events, suggested, crashes}`를 보냅니다. `day`는 한국 날짜입니다.

| 값 | 뜻 |
| --- | --- |
| `sessions` | 그날 앱이 앞으로 나온 횟수 |
| `events` | 그날 이 기기에서 새로 만든 일정 수. 교대 찍기는 찍은 근무 하나가 하나, 반복 일정은 묶음 하나가 하나입니다. 5초 안에 되돌린 일정, 기기 캘린더에서 가져온 일정, 복원한 일정은 세지 않습니다 |
| `suggested` | 그중 추천 칩을 눌러 만든 수. 누른 뒤 고쳐도 추천으로 셉니다 |
| `crashes` | 그날 앱이 크래시로 끝난 횟수. iOS는 MetricKit의 `MXCrashDiagnostic`을 세고, 안드로이드는 `ApplicationExitInfo` 중 `REASON_CRASH`와 `REASON_CRASH_NATIVE`만 셉니다. 메모리 부족 종료, ANR, 사용자가 끈 것은 세지 않아 두 플랫폼이 같은 것을 셉니다. ANR은 Play Console에서 따로 봅니다. OS가 주는 숫자라 크래시 리포트 SDK가 아닙니다 |

- 같은 날을 다시 보내면 덮어씁니다. 크래시 수처럼 다음 날 알게 되는 값도 이렇게 고칩니다.
- 숫자 말고는 아무것도 보내지 않고([개요](../kickoff.md) 6장), 공개 빌드의 서버는 404입니다.
- M1 게이트는 이 값으로 셉니다([직군별 할 일](../plan.md) 3장).
  - 캘린더 활성은 `sessions`가 1 이상인 날을 앱을 연 날로 셉니다(개요 1장 "첫 7일 중 4일 이상 앱을 연 사용자"). 자동 백업처럼 앱을 열지 않은 날의 백그라운드 요청도 서버의 접속 기록에 남아서, 접속 날짜로 세면 활성이 부풀기 때문입니다.
  - 크래시 없는 세션 비율은 1 − (`crashes` 합 ÷ `sessions` 합)입니다.
- 알파 통계는 공개 베타 전에 테이블째 지웁니다([인프라](infra.md) 9장).
