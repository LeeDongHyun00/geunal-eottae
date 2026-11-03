# 코어 인수인계

갱신: 2026-11-03, 이전 세션 https://claude.ai/code/session_01QdPReBuCP4AFqFEaYXCiQx

## 지금 상태

- 마지막으로 머지된 내 PR은 #71(85e588a, 401 `account_deleted`)입니다. 그 앞은 #63(9d73bf5, `SecretStore` 오류 경로와 열 수 없는 저장소), #56(계정 삭제·공휴일·알파 통계), #55(위젯·알림 필드)입니다. 열린 코어 PR은 없고, #71 뒤로 main의 `core/`는 바뀌지 않았습니다.
- main에서 되는 것: `docs/parts/rust-core.md` 3장 API 표의 M1 함수에 모두 몸통이 있습니다. `NotImplemented`를 돌려주는 함수는 없습니다.
- main에서 안 되는 것: 계정·복구·백업·삭제·공휴일·알파 통계는 가짜 서버(`core/geunal-sync/src/fake.rs`)로만 시험했습니다. 백엔드 M1(#62, #69)이 열려 있어 진짜 서버와는 붙여 보지 않았고, `make e2e`는 M0 흐름만 돕니다.

**M1 완료 조건 대조.** `docs/parts/rust-core.md` 10장의 M1은 "캘린더·추천·백업·복원 항목 통과. 크기 예산 충족"입니다. 캘린더·추천은 9장 "첫 테스트 목록"의 "캘린더와 추천" 13개로 봤습니다(이름이 조금 다른 시험이 있음). 백업·복원은 9장에 목록이 없어 4장 "백업 아카이브"와 5장 "저장소의 주인, 복원, 백업"의 동작으로 봤습니다.

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| 캘린더 4개: 반복·교대·자정 넘김·30분 칸 | main에 있음 | `geunal-domain/src/calendar.rs`: `expands_a_weekly_rrule_with_an_exdate_across_a_month_boundary_in_asia_seoul`, `applies_a_shift_template_to_three_days_as_three_ordinary_events`, `treats_an_end_time_before_the_start_time_as_a_shift_past_midnight`, `rounds_an_18_10_19_20_event_to_the_18_00_19_30_slots` |
| 추천 4개: 순위·겹침·생일·반복 제안 | main에 있음 | `geunal-domain/src/suggest.rs`: `ranks_weekday_work_above_a_weekend_outing_on_a_tuesday`, `drops_a_suggestion_that_overlaps_an_existing_event`. `geunal-ffi/tests/suggestions.rs`: `puts_a_birthday_within_a_week_first_unless_the_person_is_left_out`. `geunal-ffi/tests/calendar.rs`: `offers_to_make_a_title_recurring_after_three_same_weekday_uses` |
| 추천 1개: 음력 생일을 양력으로 바꿔 추천 | 일부 | 변환은 `geunal-ffi/tests/people.rs`의 `adds_a_lunar_birthday_and_shows_this_years_solar_day_in_the_calendar`와 `geunal-domain/src/lunar/mod.rs`의 시험이 봅니다. 추천(`calendar.rs`의 `suggestion_candidates` → `local.rs`의 `person_birthdays`)도 같은 변환을 거치지만, 음력 생일로 추천을 받는 시험은 없습니다 |
| 공휴일 2개: 대체공휴일·연차 다리 | main에 있음 | `geunal-domain/src/holidays.rs`: `marks_substitute_holidays_and_accepts_two_holidays_on_one_date`, `suggests_one_leave_day_that_bridges_a_holiday_into_a_four_day_weekend` |
| 그룹 비트맵 2개: `blocks_free = 0` 빼기, 시간 있는 약속은 바쁨 | 안 함(M2) | 5장 "가용 시간 올리기"가 없고, 앱이 비트맵을 넘기는 `set_availability`(M0)만 있습니다. `docs/plan.md` 4장이 "가용 시간 봉인과 합치기"를 M2에 둡니다 |
| 백업 | main에 있음 | `geunal-ffi/tests/restore.rs`: `the_automatic_backup_runs_only_after_a_change`, `a_new_account_on_a_phone_with_someone_elses_events_settles_them_before_any_backup`. `geunal-store/tests/vectors.rs`: `backups_json_restores_every_version_and_matches_this_build` |
| 복원 | main에 있음 | `restore.rs`: `a_second_phone_brings_the_backup_over_with_the_code_and_replaces_the_first`, `a_guest_comes_back_on_a_new_phone_with_the_code_alone`, `another_accounts_events_go_only_once_the_code_opens_the_backup`, `starting_over_forgets_the_deleted_backups` |
| 크기 예산: arm64·armv7 각 8MB | main에 있음 | CI `core / android`가 넘으면 실패합니다. #71 CI: arm64 7.86MB(여유 0.14MB), armv7 5.74MB, x86_64 9.71MB(기록만) |

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| `claude/core-size-tools` | 보관, PR 없음 | 크기 분석 도구와 메모입니다(arm64만 빌드, 링크 맵, 크레이트별 크기). 예산을 넘을 때 꺼내 씁니다 |
| `size-probe`(로컬) | 버림, 푸시 안 함 | 10월 29일에 머지 전 브랜치들을 합쳐 크기를 잰 것입니다. 합치기 말고 새 내용은 없고, 합친 브랜치들은 main에 머지됐습니다 |

## 다음 할 일

1. **`DELETE /me`와 "새 키로 시작"에 기기 키 서명 `"geunal delete v1" ‖ nonce`를 더합니다.** 사용자가 확정했고, 새 코어 세션의 첫 일입니다(`docs/security/threat-model.md` 3.7).
   - 계약 변경이므로 `docs/parts/protocol.md`(13장 "계정 삭제", 10장 "새 키로 시작")와 `core/vectors/`부터 고칩니다. `DELETE /me`를 구현하는 백엔드 #69와 맞춥니다.
   - 코어: `account.rs`의 `deletion_request`(지금은 게스트만 복구 키로 같은 문장에 서명)와 `upload_recovery`의 `new_key` 경로, `fake.rs`의 `delete_account`·`put_recovery`.
2. **앱이 우리 CA만 믿게 하는 계획(M2 전):** 인프라가 초안을 쓰면, 코어는 `core/geunal-sync/src/tls.rs`(지금은 webpki-roots 번들, `docs/adr/0002-http-tls-stack.md`)를 바꾸는 비용을 검토합니다.
3. 음력 생일로 추천을 받는 시험을 더합니다(위 대조표의 "일부").
4. 백엔드 #62·#69와 인프라 #64가 머지되면 `make e2e`에 계정 흐름(로그인, 복구 업로드, 백업, 삭제)을 더합니다. 지금은 가짜 서버와 `server/api/openapi.yaml`의 필드 대조까지입니다.
5. QA가 시험 이름을 대조할 때(`.claude/handoff/qa.md`) 같은 동작의 코어 시험을 알려 줍니다. `docs/qa/m1.md` 2장과 `threat-model.md` 5장의 이름 그대로인 코어 시험은 적습니다.
6. 두 앱이 `NotImplemented` 분기를 지우면 `CoreError::NotImplemented`도 지웁니다(#56 "검토할 결정" 6).

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| API 26–30에서 키스토어 키를 정말 잃은 기기의 빠져나갈 길 | 안드로이드 제안 → 사용자 | #63 "검토할 결정" 4, `.claude/handoff/android.md` |
| 앱이 API 주소에 우리 CA만 믿게 할지 | 인프라 초안 → 코어 검토 → 사용자 | `docs/security/threat-model.md` 3.7 |
| 초대 미리보기, 초대 코드 10자·7일, 떠난 주도자, 주도자 넘김 알림 | 코어·백엔드(M2 준비 때 조정 세션이 넘김) | `docs/design/m2.md` 5장, `.claude/handoff/design.md` |

## 알아 둘 것

- **앱이 붙일 차례:** `store_reset()` 안내(디자인 #67)와 답을 잃은 삭제의 같은 증명 재시도(디자인 #72)는 디자인이 정해졌고 코어는 준비돼 있습니다.
- **크기:** 큰 항목과 줄일 후보는 `claude/core-size-tools`의 `size-notes.md`에 있습니다. 안드로이드 인라인 기준 75(`core/.cargo/config.toml`)와 mls-rs `rayon` 끄기는 이미 들어가 있습니다(#38).
- **디스크:** `core/target`이 12GB까지 큽니다. 모자라면 `core/target/debug/incremental`부터 지웁니다.
- **안드로이드 로컬 확인:** `ANDROID_HOME=$(scripts/tool.sh android-sdk)`이면 `apps/android/gradlew`가 돕니다. 전체 `make build-android` 대신 `core/`에서 `cargo build -p geunal-ffi` 직후 `cargo run -p uniffi-bindgen -- generate --no-format --language kotlin --library target/debug/libgeunal_ffi.so --out-dir out/android/kotlin`으로 바인딩만 새로 만들어 앱을 컴파일할 수 있습니다. iOS는 Swift 툴체인이 없어 CI `ios / app`로만 봅니다.
- **UniFFI:**
  - `CoreError`에 종류를 더하면 앱의 빠짐없는 `switch`/`when`이 깨집니다.
  - `Core`에 함수를 더하는 것은 괜찮습니다. 앱의 가짜는 `Core`를 상속합니다.
  - 콜백을 `Result`로 바꾸는 것은 소스 호환입니다.
- **reqwest:** 오류 문구 끝에 URL 전체(presigned 서명 포함)가 붙습니다. `http.rs`의 `network`·`malformed`를 거칩니다.
- **가짜 서버:** `fail_next`(받기 전에 끊기), `lose_next`(처리한 뒤 답 버리기), `set_now`가 있습니다. 코어 시계는 `Core::set_clock`으로 맞춥니다.
- **쌓은 PR:** 아래 PR이 squash로 머지된 뒤 main을 합치면 같은 내용끼리 충돌합니다. 이쪽 것을 택한 뒤, main에 내 커밋만 cherry-pick한 트리와 같은지 비교합니다. `git rerere`가 같은 해결을 다시 씁니다.
- **CI·리뷰:** 이전 실행이 취소돼 생긴 `ci-ok` 실패에는 할 일이 없습니다. Codex 재리뷰는 PR에 `@codex review`를 남깁니다.
- **약속:** 위젯 스냅샷은 같은 버전 안에서 키만 늘어납니다(앱은 모르는 키를 무시, #55). 삭제 신호는 `CoreError::Api(401, "account_deleted")`입니다(#71).
