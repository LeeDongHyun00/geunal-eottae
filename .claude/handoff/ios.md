# iOS 인수인계

갱신: 2026-11-03, 이전 세션 https://claude.ai/code/session_01RfjiuYQGAn1o6QhASZ2Rry

## 지금 상태

- 마지막으로 머지된 iOS PR은 #66(사람·생일)입니다. main에는 온보딩·로그인·복원(#36), 월·주·목록·빠른 입력(#46), 편집·교대·템플릿(#60), 사람·생일(#66)이 있습니다.
- #75(나 탭)는 221087d에서 PR CI(`ios / app`, `ci-ok`)가 녹색이었습니다. 그 뒤 main에 들어온 코어 #63(`KeychainSecretStore`가 `SecretStoreError`를 던짐)과 새 `gate`는 아직 거치지 않았습니다.
- 5b·6·7번은 브랜치로만 있고 #75 위에 차례로 쌓여 있습니다. 셋 다 지금 main과 글자 충돌은 없습니다.

M1 완료 조건(`docs/parts/ios.md` 10장) 대조:

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| 화면 1–3, 21(복원), 24 | main | #36, `OnboardingUITests`, `RestoreUITests` |
| 화면 4–8 | main | #46, `CalendarUITests` |
| 화면 9·10·18 | main | #60, `EditorUITests` |
| 화면 11·17 | main | #66, `PeopleUITests` |
| 화면 12·13·15·16·19·20·22·23 | 열린 PR | #75, `MeUITests`, `SettingsUITests` |
| 계정 흐름(결정 24·25·30·36), 24번 시트 | 브랜치 | `claude/ios-wip-5b-account`, `AccountFlowUITests`, `MeModelTests` |
| 기기 캘린더 미러 | 브랜치 | `claude/ios-wip-6-os`, `DeviceCalendarUITests`, `DeviceCalendarMirrorTests` |
| 로컬 알림 64개 순환 | 브랜치 | `claude/ios-wip-6-os`, `AlertSchedulerTests`, `AlertContentTests` |
| 시작 오류 화면(결정 37) | 브랜치 | `claude/ios-wip-6-os`, `StartFailureUITests` |
| 위젯(14), App Group 저장소 | 브랜치 | `claude/ios-wip-7-widgets`, `WidgetUITests`, `StoreDirectoryTests` |
| 복구 코드로 새 기기 복원 | main | #36, 5b에서 체크섬 확인과 "나중에 넣을게요" 보완 |
| 25번 "열 수 없던 캘린더"(#67) | 안 함 | m1.md 2.1과 25번, 코어 `store_reset()` |
| 결정 38–40(#74) | 안 함 | m1.md 5장 |
| TestFlight 배포 | 사용자 몫 | Apple 팀·서명·App Group 등록, API https 주소(#61) |

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #75 `claude/epic-hawking-e6vi9t` | Codex P2 3건, QA 요청 D7·D11 | main 합치기, 고치기, QA 리뷰 요청, 자동 머지 |
| `claude/ios-wip-5b-account` | 푸시만 함 | #75 뒤 main에 다시 쌓아 PR |
| `claude/ios-wip-6-os` | 5b 위 | 5b 뒤 PR |
| `claude/ios-wip-7-widgets` | 6 위 | 6 뒤 PR. #61과 `Info.plist` 한 곳 충돌 |
| `claude/ios-wip-notes` | 머지하지 않음 | PR 설명·커밋 초안, #75 할 일, 리눅스 하네스 |
| #61 `claude/ios-api-base` | 사용자 맥 세션이 올림, Codex 지적 없음, CI는 옛 main 기준 | 이어받아 main 합치기, QA 리뷰, 자동 머지 |

## 다음 할 일

1. #75: main을 합쳐 #63 뒤의 컴파일을 확인하고, Codex 3건과 QA D11·D7을 고칩니다. 근거: notes 브랜치 `notes/ios/pr5-review-todo.md`, #75의 QA 리뷰. 설명에 D11과 기존 시험(`testStartsFreshWithoutTheCodeOnceTheOwnerPasses`, `testCancellingTheOwnerPromptKeepsEverything`)의 대응을 적습니다.
2. #61: main을 합쳐 CI·`gate`를 다시 돌리고 QA 리뷰를 받아 자동 머지합니다. #75·5b·6과는 겹치지 않습니다.
3. 5b → 6 → 7을 차례로 새 main에 다시 쌓아 PR로 올립니다. 설명 초안은 `notes/ios/drafts/`에 있고, 시험 수와 CI 줄은 다시 셉니다.
   - 5b의 결정 36은 #72(5b00ae8)·#71을 머지 전에 보고 만들었습니다. 머지된 글과 다시 대조합니다.
   - 7번은 #61과 합칠 때 `Info.plist`의 `GeunalAPIBase`와 `GeunalAppGroup`을 둘 다 남깁니다.
4. 결정 38–40(#74): 삭제 실패 문구(편집·템플릿·사람 시트), 교대 실패 카드(`ShiftStampModel.failed`는 이미 있음), 빠른 입력 아래 줄의 `ViewThatFits`.
5. 25번 "열 수 없던 캘린더"(#67): 코어 `store_reset()`이 있으면 온보딩보다 먼저 띄웁니다.
6. TestFlight는 사용자·조정 세션과 정합니다.

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| 각 PR의 "검토할 결정" | 디자인·코어 | #75 설명, `notes/ios/drafts/*-body.md` |
| 알파 서버의 https 주소 | 인프라 | #61 "검토할 결정" 3 |
| Apple 팀·서명·App Group 등록 | 사용자 | `notes/ios/drafts/pr7-body.md` "확인하지 못한 것" |

## 알아 둘 것

- 클라우드 세션은 Swift를 빌드하지 못합니다. macOS CI가 유일한 검증이고, 모델 시험은 리눅스 하네스로 먼저 돌립니다(`notes/ios/README.md`).
- 수동 실행(`workflow_dispatch`)은 끝나도 알림이 오지 않습니다. 실행 상태나 `ios-screenshots` 아티팩트로 봅니다. 사용자 지시는 "UI 시험 결과가 나오면 PR을 연다"입니다.
- iOS 18·26 API는 `#available`로 감쌉니다(`Calendar.Identifier.dangi`는 iOS 26부터).
- `strings.py sync` 뒤에는 문자열 보간의 `%lld`를 `%@`로 손봅니다.
- 시험 번들은 카카오 SDK를 링크하지 않습니다. SDK를 보는 검사는 FeatureAccount의 DEBUG 함수로 합니다.
- 코어 `birthday_in_year`의 연도는 음력 해입니다.
- 키체인 기본 그룹은 `keychain-access-groups`의 첫 항목이라, App Group을 더해도 키 항목은 그대로입니다.
- 스크린숏이 있는 PR은 디자인 세션에, 앱 PR은 QA 세션에 직접 대조·리뷰를 요청합니다. 둘 다 구독하지 않습니다.
- 안드로이드와 맞춘 것: 반복 칩은 편집을 연 때의 규칙을 남깁니다(#73, 5b에 있음).
