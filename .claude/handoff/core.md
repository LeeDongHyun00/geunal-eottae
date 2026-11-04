# 코어 인수인계

갱신: 2026-11-04, 이전 세션 https://claude.ai/code/session_0132zdS4uHGbzCbfbEzPa2Wd

## 지금 상태

- 이번 세션에 머지된 코어 PR: #164(Welcome 자동 가입 끔), #165(로그인 뒤 복구 업로드를 백그라운드로), #167(outbox 영구 거절 처리), #170(새 멤버 번들을 한 번, 나를 추가한 멤버에게서만). 그 앞의 M1 작업(기기 키 서명, 계정·복구·백업 e2e 등)도 모두 main에 있습니다.
- main에서 되는 것: M1 API 전부. `make e2e`가 게스트 계정 흐름(로그인·복구 업로드·백업·복귀·삭제)과 M0 그룹 게이트를 실제 스택으로 돕니다.
- main에서 안 되는 것: 앱 코어는 Welcome으로 가입하지 않습니다(`geunal_ffi` `build_engine`의 `with_welcomes(false)`). M2 초대 흐름이 생길 때까지의 의도된 동작입니다. e2e만 `join_welcomes_for_tests()`로 켭니다.

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #175 `claude/core-entry-size` | Codex 완료(지적 없음), CI 녹색 | 계약 4장을 바꿔 현재 커밋에 `user-approved`가 필요. 붙으면 squash 머지 |
| #177 `claude/core-bundle-order` | Codex·CI 진행 중 | 번들 흡수를 한 트랜잭션으로. 리뷰 끝나면 자동 머지 |
| `claude/core-size-tools` | 보관, PR 없음 | 크기 분석 도구와 `size-notes.md`. 예산을 넘을 때 꺼내 씀 |

## 다음 할 일

M2 목표는 "그룹(M2)과 채팅(M3) 완성"입니다. 코어 순서는 [plan.md](../../docs/plan.md) 4장을 따릅니다.

1. 그룹 생성·초대·가입: 초대 코드로 수락한 그룹의 Welcome만 받게 하고 `with_welcomes` 스위치를 그 흐름으로 바꿉니다(#164 "검토할 결정" 2). 외부 커밋 재가입 포함.
2. 가용 시간 봉인과 합치기: `docs/parts/rust-core.md` 5장. 그룹 비트맵 시험 2개(`blocks_free = 0` 빼기, 시간 있는 약속은 바쁨)도 여기서.
3. 날짜 잡기·투표·시간·활동 추천, 그룹 설정(그룹 메타 충돌 시 재시도), 그룹 프로필.
4. 새 멤버 번들과 상태 재봉인 마무리, 성능 예산.
5. M2 게이트 항목: 백그라운드에서 빠진 outbox 행을 메시지별로 앱에 알리기(지금은 `Change::Group`뿐, QA "메시지 미전달"). FFI에 새 `Change`나 조회 함수가 필요하므로 iOS·안드로이드와 정함(#167 "검토할 결정").
6. 보안 잔여 항목: 비공개 추적 중(QA 목록). 새 세션 프롬프트로 받습니다.

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| #175 계약 4장(엔트리 한도 = 패딩 포함 MLS 메시지 65,536) | 사용자 | #175 |
| 백그라운드 미전달 메시지를 앱에 알리는 방식 | 코어·iOS·안드로이드 | #167 "검토할 결정" |
| 초대 미리보기, 초대 코드 10자·7일, 떠난 주도자, 주도자 넘김 알림 | 코어·백엔드 | `docs/design/m2.md` 5장 |
| 앱이 API 주소에 우리 CA만 믿게 할지 | 인프라 초안 → 코어 검토 → 사용자 | `docs/security/threat-model.md` 3.7 |

## 알아 둘 것

- **하지 않기로 한 것:** 7일마다 키 자동 갱신(B3)과 키 패키지 업로드 미루기(B안)는 사용자가 "지금은 하지 마"라고 했습니다.
- **공개 저장소 규칙:** 커밋·PR·주석에 보안 재현 방법이나 빈틈 세부를 쓰지 않고, 바뀐 동작만 적습니다(qa.md 4장).
- **엔트리 크기:** mls-rs 기본 패딩 때문에 인코딩 57,273바이트까지 메시지 57,424, 그 위는 65,616으로 서버가 413. #175가 `MAX_ENTRY_BYTES`를 57,216으로 낮춥니다.
- **outbox:** 엔트리·상태 블롭은 400·404·413·422·403(`not_member`·`not_owner`·`blocked`)이면 큐에서 뺍니다. 커밋 의도는 빼지 않고 다시 만듭니다. Add에서 기기 조회가 404인 사람은 빠집니다(#155 뒤 서버는 추가할 수 없는 계정에 404).
- **번들:** `groups.bundle_from/epoch/through`(마이그레이션 0004)가 "받을 번들"입니다. MLS가 확인한 Welcome 서명자의 것만, seq는 Add 커밋 seq 이하만 받습니다.
- **복구 업로드:** 모든 업로드가 `recovery_upload` 비동기 잠금을 지납니다. 시험은 `recovery_upload_settled()`(fake-server 기능)로 기다립니다. 코어 런타임은 `CoreRuntime`이 작업 스레드에서 놓이면 `shutdown_background`로 내립니다.
- **가짜 서버:** `fail_next`, `lose_next`, `refuse_next`(#175), `corrupt_state`, `set_now`. 모르는 계정의 기기 조회는 404입니다.
- **e2e:** 로그인 한도가 IP당 시간 30회라 로컬에서 되풀이하면 `docker exec geunal-redis-1 redis-cli FLUSHALL`. 디스크가 차면 S3가 500을 내므로 `core/target/debug/incremental`부터 지웁니다.
- **작업 트리:** 다른 경로의 작업 트리에서 같은 `CARGO_TARGET_DIR`로 빌드하면, 그 경로가 박힌 시험 바이너리가 남아 벡터 시험이 "파일 없음"으로 실패합니다. `cargo clean -p geunal-crypto`로 풉니다.
- **충돌:** 같은 시험 파일(`geunal-sync/tests/engine.rs`)에 시험을 더하는 PR끼리 끝부분이 충돌합니다. 양쪽 시험과 import를 모두 살립니다.
- **UniFFI:** `CoreError`·`Change`에 종류를 더하면 앱의 빠짐없는 `switch`/`when`이 깨집니다. `Core`에 함수를 더하는 것은 괜찮습니다.
- **CI:** 이전 실행이 취소돼 생긴 `ci-ok` 실패는 할 일이 없습니다. Codex는 PR을 열 때만 리뷰하고, 다시 받으려면 `@codex review`.
