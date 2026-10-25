# 테스트 벡터

[공통 계약](../../docs/parts/protocol.md) 12장의 벡터를 두는 곳입니다. 만드는 파트가 생성해 커밋하고, 검증하는 파트의 CI가 같은 파일을 읽습니다. 이 디렉터리가 바뀌면 `api`·`gateway` 워크플로도 돕니다(`scripts/ci/changed-parts.sh`).

| 파일 | 만드는 곳 | 검증하는 곳 |
| --- | --- | --- |
| `mls_headers.json` | Rust 코어 | API 서버의 Kotlin MLS 헤더 파서 |
| `entries.json` | Rust 코어 | Rust 코어(버전 간 호환) |
| `state_blobs.json` | Rust 코어 | Rust 코어 |
| `realtime_events.json` | API 서버 | 게이트웨이, Rust 코어 |
| `push_payloads.json` | API 서버 | Rust 코어(`decrypt_push`) |

- 파일 이름은 처음 쓰는 파트가 이대로 만듭니다. 바꾸려면 이 표와 함께 바꿉니다.
- 형식은 JSON이고 맨 위에 `version`(정수)과 `generated_by`(만든 명령)를 둡니다. 바이트는 소문자 16진수 문자열로 씁니다.
- 공통 계약을 바꾸는 PR은 계약 문서와 벡터를 먼저 바꿉니다([개요](../../docs/kickoff.md) 9장).
