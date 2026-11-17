# iOS 인수인계

갱신: 2026-11-17, 이전 세션 https://claude.ai/code/session_0132zdS6HBvBLe8C9VXg25Z8

## 지금 상태

- M1 화면과 흐름은 main에 있습니다: 온보딩·복원(#36), 월·주·목록·빠른 입력(#46, #132), 편집·교대(#60), 사람(#66), 나 탭(#75), 계정 흐름(#107, #136), 알림·백그라운드·기기 캘린더(#120), 위젯·App Group(#129, #142).
- 11-04 이후 main에 들어온 iOS PR은 #214 하나입니다(조정 세션 작성): 개인정보 매니페스트, 알림을 시각 하나로 예약, App Group 컨테이너가 없으면 키를 지우지 않고 시작 실패로 처리.
- **M2 화면은 아직 시작하지 않았습니다.** 디자인 시안과 계약이 나온 뒤 조정 세션이 시작을 알립니다.
- TestFlight: release-ios run 3(main 52df814, 위젯 첫 빌드)이 11-04에 올라갔습니다. 그 뒤 빌드는 없습니다.

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #159 `claude/ios-week-overlap` | 초안, CI 녹색 | 결정 44(묶음과 시트)로 만든 것. 결정 45(대표 카드, 리본, "+n", 시트 없이 열 포커스)로 다시 고칩니다. 코어 `calendar_week` 변경(그날 블록 전부 + 묶음 번호)이 먼저인데, 그 코어 PR은 아직 없습니다 |
| #222 `claude/ios-suggestion-index` | 조정 세션이 연 PR | 빠른 입력 추천 칩이 목록 인덱스 대신 값을 받습니다. QA 리뷰 대상 |
| #221 `claude/core-ios-journal`(코어) | 조정 세션이 연 PR | iOS 저장소를 롤백 저널로 바꿈. 사용자가 보고한 아이폰 충돌의 가장 유력한 원인(App Group 파일 잠금) |
| `claude/ios-wip-notes` | 머지하지 않음 | 리눅스 하네스 안내(`notes/ios/README.md`), 대역 파일(`shims/`) |

`claude/ios-wip-5b-account`, `-6-os`, `-7-widgets`는 이전 세션들의 작업 브랜치입니다. 확인하기 전에는 지우지 않습니다.

## 다음 할 일

1. **충돌 확인:** #221·#222가 머지되면 TestFlight 빌드를 올리고, 사용자가 충돌이 사라졌는지 봅니다. 남으면 분석 데이터의 `GeunalApp-*.ips`에서 Termination Reason(`0xdead10cc`인지)을 받습니다.
2. **백그라운드 만료(#221 "검토할 결정"):** `BackgroundTime`의 만료 처리는 요청만 끝내고 코어 작업은 계속 돕니다. 만료 때 코어의 쓰기를 멈추는 길을 코어와 맞춥니다.
3. **APNs 푸시 등록:** 코어 #194의 `Core::set_push_token(Option<String>)`이 main에 있습니다.
   - 실행할 때와 토큰이 갱신될 때마다 토큰을 소문자 hex로 넘기고, 알림을 끄면 `None`을 넘깁니다.
   - 서버는 **APNs production만** 씁니다. Xcode 디버그 빌드의 토큰(sandbox)으로는 푸시가 오지 않으므로, 푸시는 TestFlight 빌드로 확인합니다.
   - 엔타이틀먼트 `aps-environment`를 더합니다. 그 전에 사용자가 포털에서 App ID `app.geunal.ios`에 Push Notifications를 켜야 합니다(`infra/release/README.md` 3번).
4. **초대 Universal Links:** 코어 #192(`create_invite`, `invite_preview`, `join_invite`, `revoke_invite`, `parse_invite`, `invite_link`)와 계약 #187(`protocol.md` 15장)이 아직 열려 있습니다.
   - 링크 `https://geunaleottae.com/i/<코드>#n=..&c=..`는 `parse_invite`에 그대로 넘깁니다.
   - AASA는 Cloudflare Pages가 줍니다(인프라 인수인계 4번, 아직 없음).
   - 앱에는 Associated Domains `applinks:geunaleottae.com`을 더합니다. 사용자가 App ID에 Associated Domains를 켜야 합니다.
5. **M2 화면**(시작 알림 뒤, [plan.md](../../docs/plan.md) 4장, `docs/parts/ios.md`): 그룹 캘린더 띠와 날 테이블, 날짜 잡기·투표, 그룹 설정, 초대와 카카오톡 공유, 그룹 캘린더 60fps. 시안은 `docs/design/m2.md`와 보드(#189).
6. **결정 45:** 코어 변경 뒤 #159를 고쳐 디자인 세션에 스크린숏 대조를 부탁합니다(시안 `boards/WeekOverlap`, `07-week--overlap*`).
7. **M3 채팅:** 실시간 주소는 `wss://rt.geunaleottae.com`입니다(#197). 코어의 채팅은 앱이 끄는 기능 뒤에 있습니다(#199).
8. **M1에서 남은 것:** 결정 38–40(m1.md 5장), 25번 "열 수 없던 캘린더"(#67, 코어 `store_reset()`).

## TestFlight에서 볼 것(run 3 결과는 아직 받지 못함)

- 위젯: 오늘 일정, 잠금 화면·StandBy에서 제목 가림, VoiceOver가 가려진 제목을 읽지 않는지(QA #144 시나리오 3.6 9번)
- 업데이트 뒤 로그인 유지(키체인 그룹 #152)
- 로그인 시간: Instruments의 `app.geunal.ios`·`account` signpost(#146)

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| 세션이 끝난 뒤 복구 코드 보기, 게스트가 기기의 코드로 바로 되찾기 | 디자인·코어 | m1.md 결정 43, #136 "검토할 결정" 1 |
| Apple 계정 삭제가 403으로 끝나는 원인 | 백엔드(#149 로그), 사용자 | 앱·코어 경로는 확인 끝. `invalid_grant`가 나오면 앱 흐름을 다시 봄 |

## 알아 둘 것

- 클라우드 세션은 SwiftUI를 빌드하지 못합니다. macOS CI가 유일한 검증이고, 모델 시험은 리눅스 하네스로 먼저 돌립니다(notes 브랜치). 애플 전용 모듈은 하네스에 대역을 둡니다.
- `strings.py sync` 뒤에는 문자열 보간의 `%lld`를 `%@`로 손봅니다(정수는 `%lld` 그대로).
- 새 푸시가 앞선 CI를 취소하면 옛 커밋의 `ci-ok`가 빨갛게 옵니다. 현재 커밋을 봅니다.
- 시뮬레이터가 앱을 띄우지 못하고 멈추면(`Timed out while launching application`) 실패한 작업을 한 번 다시 돌립니다.
- 검사가 이미 녹색이면 자동 머지가 돌지 않습니다. 같은 조건을 확인하고 squash로 바로 머지합니다.
- 시트 높이는 내용을 재어 맞추고 화면 90%에서 멈춥니다(`QuickEntrySheet`, #132).
- 앱 PR은 QA 세션에, 스크린숏이 있는 PR은 디자인 세션에 직접 리뷰·대조를 요청합니다. 둘 다 구독하지 않습니다.
- 공개 저장소라 보안 점검의 세부는 커밋·PR 설명에 쓰지 않고, 바뀐 동작만 적습니다.
