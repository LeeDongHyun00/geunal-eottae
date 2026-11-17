# 코어 인수인계

갱신: 2026-11-17, 이전 세션 https://claude.ai/code/session_0132zdS7mwpftUmPh7999RdK

## 지금 상태

- 이번 세션에 머지된 코어 PR: #175(엔트리 한도 = 패딩 포함 MLS 메시지), #177(번들 흡수를 한 트랜잭션으로), #194(푸시 토큰 `set_push_token`), #199(채팅을 `chat` 기능 뒤로).
- main에서 되는 것: M1 전부, 푸시 토큰 등록. 앱 빌드에는 채팅 FFI가 없습니다(`chat` 기능은 시험·e2e만 켬).
- main에서 안 되는 것: 초대·가입, 가용 시간 자동 올리기, 그룹 메타 쓰기, 키 패키지 보충. 모두 아래 PR·브랜치에 있습니다. 앱 코어는 아직 Welcome으로 가입하지 않습니다.
- 다른 세션의 코어 PR: #212(동기화 복구, 마이그레이션 `0005_rejoin`), #221(iOS 롤백 저널). #218은 머지됨(e2e 약속 날짜).

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #187 `claude/core-invite-contract` | 계약 15장(초대)과 `invites.json`. 백엔드 합의(#191), Codex 지적 없음(첫 커밋), main과 충돌 없음 | 머리 56cb876에 `user-approved`. 붙으면 squash 머지 |
| #192 `claude/core-invite-join` | #187 위. 초대 FFI, 참여를 요청한 그룹의 Welcome만 받기(`0005_accepted_invites`), 가짜 서버 초대 규칙. Codex 지적 1건 고침, 재리뷰 지적 없음 | #187 뒤 main 합치기(충돌: `geunal-ffi/src/lib.rs`, `geunal-sync/tests/engine.rs`). 마이그레이션 번호를 #212와 맞춤 |
| #196 `claude/core-availability` | 기기 캘린더로 가용 시간을 만들어 `sync()`마다 바뀐 것만 올림, `group_sharing`. CI·gate·Codex 녹색, 자동 머지 켜짐 | #194와 `lib.rs`의 `sync()`에서 충돌. 풀면 자동 머지 |
| #200 `claude/core-key-packages` | Add에서 키 패키지 404·MLS 거절 기기를 빼고 큐 계속, 한 시간마다 키 패키지 보충. CI 녹색, QA 재확인 끝 | 충돌(`lib.rs`, `fake.rs`). QA 권함: 마지막 `add_members`가 조합 때문에 실패해도 의도를 빼거나 한 명씩. 새 커밋에 `@codex review` 뒤 자동 머지 |
| `claude/core-group-meta-2`(PR 없음) | #192 위. `group_meta` 쓰기, 409면 최신 블롭에 내 필드만 다시 얹기, 커밋한 기기의 재봉인(`changed_by` 유지), FFI `group_settings`·`change_group_settings`, `0006_group_meta`. 시험 통과 | #192 뒤 main 합쳐 PR. 커버는 미디어 업로드가 생긴 뒤 |
| `claude/core-size-tools` | 보관, PR 없음 | 크기 예산을 넘을 때 꺼내 씀 |

## 다음 할 일

M2만 합니다(사용자 결정으로 채팅 M3 보류). 순서는 [plan.md](../../docs/plan.md) 4장.

1. 위 PR 정리: #187 → #192 → #196·#200 → 그룹 메타 PR.
2. 공유 일시중지: "공유 안 함" 블롭(예: `days = 0`)을 계약 5장에 정하고 구현(#196 "검토할 결정").
3. 상태 요약값: QA #186 결정 1, `docs/qa/m2.md` S7.
4. 주 보기 겹침(`docs/design/m1.md` 결정 45): 앱이 쓰도록 `calendar_week`가 3열 상한 없이 겹친 일정을 모두 줌. 앱 세션들과 (가)안으로 정함.
5. 날짜 잡기 남은 규칙: 떠난 주도자 넘김(`docs/design/m2.md` 결정 6), 주도자 넘김 알림(결정 10), 수락한 약속을 내 캘린더에 넣기, 그룹 프로필.
6. 실시간 B4: 루프백만 `ws://` 허용은 코어가 먼저. 토큰 헤더 전환은 실시간 세션이 계약 7장 PR을 올린 뒤.
7. 백그라운드 미전달 메시지를 앱에 알리기(#167 "검토할 결정"), 성능 예산(rust-core.md 8장).

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| 계약 15장(초대) | 사용자 | #187 |
| 없는 코드·끝난 코드 문구를 하나로(계약상 같은 404) | 디자인·사용자 | #187 "검토할 결정" |
| 내 종일 일정을 바쁨으로 셈 | 기획 | #196 "검토할 결정" |
| 앱이 API 주소에 우리 CA만 믿게 할지 | 인프라 → 코어 → 사용자 | `docs/security/threat-model.md` 3.7 |

## 알아 둘 것

- **하지 않기로 한 것:** 7일 자기 갱신(B3), 키 패키지 업로드 미루기(B안), 채팅(M3) 새 작업.
- **공개 저장소:** 커밋·PR·주석에 보안 재현이나 빈틈 세부를 쓰지 않습니다(qa.md 4장). 세부는 조정 세션에 send_message.
- **보류:** 푸시·PR·댓글·자동 머지 켜기 각각 바로 앞에서 `hold` 이슈를 봅니다. 한 번 본 결과를 이어 쓰다 보류 중에 푸시한 적이 있습니다.
- **마이그레이션 번호:** 저장소는 기록된 가장 큰 번호보다 큰 것만 적용합니다. 번호가 겹치거나 뒤늦게 작은 번호가 들어오면 안 되므로, 나중에 머지하는 PR이 번호를 올립니다(#212 `0005_rejoin`, #192 `0005_accepted_invites`, 그룹 메타 `0006`).
- **가짜 서버(#192부터):** 대기·활성 계정만 Add·기기 조회·키 패키지 가져가기. `invite`(동의 없는 대기), `expire_invite`, `lose_next("join_invite")`, `group_meta` 공용 자리와 `base_version`. #200: `use_key_packages`, `spoil_key_packages`.
- **outbox:** 엔트리·상태 블롭은 400·404·413·422·403(`not_member`·`not_owner`·`blocked`)이면 뺍니다. 커밋 의도는 다시 만듭니다. 그룹 메타 브랜치는 `Outgoing::Meta`(변경 자체)를 더합니다.
- **충돌:** `geunal-sync/tests/engine.rs` 끝과 `geunal-ffi/src/lib.rs`의 `sync()`에 여러 PR이 줄을 더합니다. 양쪽을 모두 살립니다.
- **UniFFI:** `CoreError`·`Change`에 종류를 더하면 앱의 `switch`/`when`이 깨집니다. 함수·레코드 추가는 괜찮습니다.
- **Codex:** PR을 열 때만 리뷰합니다. 머지 조건을 위해 새 커밋을 다시 보게 하려면 `@codex review`.
- **CI:** #192의 산출물 업로드 시간 초과와 #200의 에뮬레이터 화면 시험 시간 초과는 코드와 무관했고, #200은 다음 커밋에서 통과했습니다.
- **e2e:** 로그인 한도가 IP당 시간 30회라 로컬에서 되풀이하면 `docker exec geunal-redis-1 redis-cli FLUSHALL`.
- **작업 트리:** 다른 경로의 작업 트리와 `CARGO_TARGET_DIR`을 같이 쓰면 벡터 시험이 "파일 없음"으로 실패합니다. `cargo clean -p geunal-crypto`.
