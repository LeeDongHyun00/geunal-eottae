# 테스트 벡터

[공통 계약](../../docs/parts/protocol.md) 12장의 벡터를 두는 곳입니다. 만드는 파트가 생성해 커밋하고, 검증하는 파트의 CI가 같은 파일을 읽습니다. 이 디렉터리가 바뀌면 `api`·`gateway` 워크플로도 돕니다(`scripts/ci/changed-parts.sh`).

| 파일 | 만드는 곳 | 검증하는 곳 |
| --- | --- | --- |
| `mls_headers.json` | Rust 코어 | API 서버의 Kotlin MLS 헤더 파서 |
| `entries.json` | Rust 코어 | Rust 코어(버전 간 호환) |
| `state_blobs.json` | Rust 코어 | Rust 코어 |
| `realtime_events.json` | API 서버 | 게이트웨이, Rust 코어 |
| `push_payloads.json` | API 서버 | Rust 코어(`decrypt_push`) |
| `recovery.json` (M1) | Rust 코어 | Rust 코어, API 서버(복구 인증값의 해시) |
| `backups.json` (M1) | Rust 코어 | Rust 코어(이전 버전 아카이브 복원) |
| `auth_results.json` (M1) | API 서버 | Rust 코어 |

M1 벡터는 [공통 계약](../../docs/parts/protocol.md) 10·13장을 따라 만드는 파트가 첫 구현과 함께 커밋합니다.
- `recovery.json`: 단어 목록 파일의 SHA-256, `E`와 NFC 12단어의 짝(체크섬이 틀린 예 포함), 복구 ID·복구 인증값·그 해시, 고정 salt로 만든 복구 키, 고정 논스로 래핑한 AMK.
- `backups.json`: 고정 AMK와 고정 논스 접두사로 봉인한 아카이브. 아카이브 버전이 오를 때마다 항목을 더하고 지우지 않습니다.
- `auth_results.json`: `registered`와 `replace_required` 응답 본문.

- 파일 이름은 처음 쓰는 파트가 이대로 만듭니다. 바꾸려면 이 표와 함께 바꿉니다.
- Rust 코어의 벡터는 테스트가 만들고 검사합니다. `cd core && GEUNAL_WRITE_VECTORS=1 cargo test --locked -p geunal-domain -p geunal-mls --test vectors`가 세 파일을 다시 쓰고, 보통의 `make test-core`는 커밋된 파일이 지금 빌드와 맞는지만 확인합니다.
  - `entries.json`: 엔트리 종류마다 JSON 형태와 CBOR(16진수). 바이트 필드는 CBOR 바이트 문자열입니다.
  - `mls_headers.json`: mls-rs가 만든 커밋(wire_format 1)과 메시지(2)의 바이트, 그 안의 그룹 ID와 epoch. Welcome·GroupInfo·키 패키지는 헤더가 없다는 뜻으로 `has_header: false`입니다.
  - `state_blobs.json`: 상태 블롭의 AAD 문자열과 `nonce ‖ 암호문 ‖ 태그` 형식. 재현을 위해 고정 논스를 쓴 것이고, 실제 코드는 늘 무작위 논스를 씁니다.
- 형식은 JSON이고 맨 위에 `version`(정수)과 `generated_by`(만든 명령)를 둡니다. 바이트는 소문자 16진수 문자열로 씁니다.
- 공통 계약을 바꾸는 PR은 계약 문서와 벡터를 먼저 바꿉니다([개요](../../docs/kickoff.md) 9장).
