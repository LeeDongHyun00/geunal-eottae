# 안드로이드 인수인계

갱신: 2026-11-03, 이전 세션 https://claude.ai/code/session_018cHvJnb7AzZGk493KhFQM4

## 지금 상태

마지막으로 머지된 내 PR은 #59(월·주·목록 보기, 빠른 입력)이고, 그 앞은 #49(기반, 온보딩, 로그인, 복구 코드로 복원)입니다. M1 완료 조건([android.md](../../docs/parts/android.md) 8장)과 대조하면 다음과 같습니다.

| M1 항목 | 상태 | 근거 |
| --- | --- | --- |
| 화면 1–3 시작·로그인·권한 | main에 있음 | #49, `OnboardingFlowTest` |
| 화면 4–8 월·빠른 입력·추가한 뒤·주·목록 | main에 있음 | #59, `CalendarScreenTest`, `AgendaModelTest` |
| 화면 9·10·18 일정 편집·교대 찍기·템플릿 | 열린 PR | #73, `EditorScreenTest`, `ShiftStampScreenTest` |
| 화면 11·17 사람·생일 | 브랜치만 | `claude/android-people` 6b1d94c, `PeopleScreenTest` |
| 화면 15·16·19 캘린더 관리·편집, 알림 설정 | 브랜치만 | `claude/android-me` 32c378f, `CalendarsScreenTest`, `AlertSettingsScreenTest` |
| 화면 13·20 복구 코드, 암호화 백업 | 브랜치만 | `claude/android-me` 24eebae, `RecoveryCodeScreenTest`, `BackupScreenTest` |
| 화면 12 나 | 일부 | main은 프로필 카드만. 묶음(캘린더, 보안과 백업, 계정)은 위 두 브랜치 |
| 화면 21 새 기기에서 복원 | main에 있음 | #49 `RestoreFlowTest`. 지금 기기의 "기기 바꾸기" 안내는 안 함 |
| 화면 22·23 계정 삭제, 로그아웃됨 | 안 함 | 코어는 준비됨(#56 `deleteAccount`, #71 `account_deleted`, `revocation()`) |
| 화면 24 다른 계정의 일정 | 일부 | 복원 흐름의 시트만(#49 `RestoreSheets`). 로그인 뒤 시트는 안 함 |
| 화면 14 위젯(Glance) | 안 함 | 코어 `widgetSnapshot()`(#55, `ignoreUnknownKeys`로 읽기) |
| 기기 캘린더 미러 | 안 함 | 권한 묻기만 #49 `DevicePermissions`. 코어 `mirrorDeviceCalendars` |
| 정확한 알람 | 안 함 | 코어 `scheduledAlerts`. 알림 설정 값은 `claude/android-me`에서 저장만 |
| 복구 코드로 새 기기 복원 | main에 있음 | #49 `RestoreFlowTest` |
| 내부 테스트 트랙 배포 | 사용자 몫 | Play Console 업로드와 서명 |

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #73 `claude/compassionate-rubin-aae4or` d502d7c | Codex 4건 고쳐 답하고 해결. QA "막을 것 없음". `android / instrumented` 빨강 | 다음 할 일 1 |
| `claude/android-people` 6b1d94c | 로컬 전체 검사 통과, PR 없음 | 다음 할 일 2 |
| `claude/android-me` 32c378f, 24eebae | 같음. 위에 0f6fbfc(PR 설명 초안 `.claude/handoff/android-drafts/`) | 다음 할 일 3. 0f6fbfc는 옮기지 않음 |

## 다음 할 일

1. #73 빨강: `CalendarScreenTest.opensTheEditorAndTheShiftStampFromEachView`가 `month.day.2026-10-02`를 두 개 찾습니다.
   - 원인: 월 페이저가 화면 밖 9월도 그리고, 9월 격자가 10월 3일까지라 같은 날 칸이 두 개입니다.
   - 고칠 것: `MonthView`의 칸 태그를 그 달의 날만 `month.day.*`로, 앞뒤 달의 날은 `month.outside.*`로 바꿉니다.
   - 그 뒤 PR에 원인을 한 번 적고, CI가 녹색이고 QA 리뷰가 오면 자동 머지합니다.
2. 4번 PR: #73이 머지된 뒤 main에서 `claude/android-people-…`를 만들고 6b1d94c를 cherry-pick합니다. 설명은 `android-drafts/pr4-body.md`이고, `__LOCAL__` 자리에 검사 결과를 채웁니다.
3. 5번 PR 둘: 32c378f(5a), 24eebae(5b)를 차례로 옮겨 PR로 올립니다. 설명은 `pr5a-body.md`, `pr5b-body.md`입니다.
   - 5a의 `CalendarFakes.kt`는 d502d7c와 같은 `settings`·`notificationSettings()`를 더하므로, 충돌하면 하나만 남깁니다.
4. 시작 오류 화면 문구와 "다시 시도" 버튼(결정 37, #70): `StartFailureScreen.kt`
5. 5번 마지막: 22 계정 삭제, 23 로그아웃됨, 24 로그인 뒤 시트, 기기 바꾸기 안내, 서버가 아는 것, 테스트 통계.
   - 계정 삭제: 재인증과 `CoreException.Api(code = "account_deleted")`([m1.md](../../docs/design/m1.md) 22, #71).
   - 테스트 통계: `setAlphaStats`, `recordAppForeground`, `recordCrashes`(`ApplicationExitInfo`).
6. 6번: 알림 예약(정확한 알람), Glance 위젯, 기기 캘린더 미러(15번의 기기 캘린더 묶음 포함), 자동 백업 예약(WorkManager).

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| API 26–30에서 keystore 키를 정말 잃으면 시작 오류에서 못 벗어남. 빠져나갈 길(새로 시작 → 복구 코드로 백업) | 안드로이드 제안, 사용자 | 코어 #63 검토 메시지, 5번 마지막 PR에서 올릴 것 |
| 복구 코드 대기의 "복구 코드 넣기 ›"를 메인에서 21번 입력으로 잇기 | 안드로이드, 디자인 | `android-drafts/pr5b-body.md` 결정 1 |
| 4·5a·5b PR의 "검토할 결정"(6·5·4건) | 디자인, iOS | `android-drafts/*.md` |
| #73 결정 1–6 중 38–41에 없는 것(덮개 내비게이션, 일정 고유 반복 칩) | 디자인, iOS | #73 설명 |

## 알아 둘 것

- 결정 38–41(#74)은 #73이 이미 한 방식을 확정한 것이라 고칠 것이 없습니다. 41의 "날짜 줄은 늘 빠른 입력"만 다시 확인합니다.
- 이 환경에는 KVM이 없어 계측 시험은 CI의 `android / instrumented`에서만 돕니다. 스크린숏은 `android-screenshots` 아티팩트로 올라갑니다.
- 스크린숏이 있는 PR은 디자인 세션에, 앱 PR은 QA 세션에 직접 대조·리뷰를 요청합니다. 두 세션 모두 PR을 구독하지 않습니다.
- 작업 트리(git worktree)를 여럿 쓸 때 `core/out`을 main 트리로 링크하면 코어 빌드를 한 번만 합니다.
  - 옛 코어 위의 브랜치에서 `make`를 돌리면 링크된 바인딩이 낡은 것으로 바뀝니다.
  - 그때는 Gradle 작업만 직접 부릅니다(`ktlintCheck lint assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest`).
- Compose 시험의 함정:
  - `clearAndSetSemantics` 뒤의 `testTag`는 사라지므로 태그를 앞에 둡니다.
  - 키보드가 시트를 줄이므로 `performScrollTo`나 `performImeAction`을 먼저 씁니다.
  - 월 페이저는 앞뒤 달도 그립니다.
- UniFFI의 `AlertRule.Inherit`와 `Off`는 `object`라서, 가짜 코어 기록에는 직접 문자열로 적습니다. `NoHandle` 가짜의 실제 호출은 `InternalException`입니다.
- 연락처(#68)는 미리 고르지 않고, 이름은 `StructuredName`에서만 읽고, 목록을 저장하지 않습니다. 백업 규칙은 구역마다 `calendar.xml` 하나(`BackupRulesTest`)입니다.
- Codex는 PR마다 P2를 몇 건 남깁니다. 실제 경로를 따라가 보고, 맞으면 고쳐 시험을 더한 뒤 답하고 해결합니다.
