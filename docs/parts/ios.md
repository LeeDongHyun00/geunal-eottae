# iOS 앱

화면과 OS 연동을 맡습니다. 상태 계산, 암호화, 동기화는 모두 [Rust 코어](rust-core.md)가 하고, 앱은 그 결과를 그립니다. 같은 기능의 규칙은 [개요](../kickoff.md) 5장에 있습니다.

## 1. 기술과 최소 버전

- **언어와 UI:** Swift 최신 안정판, SwiftUI, Swift Concurrency, `@Observable` 뷰모델
- **최소 버전:** iOS 17. 민감 콘텐츠 분석(SensitiveContentAnalysis), 상호작용 위젯, Observation이 iOS 17부터입니다.
- **외부 의존성:**
  - `GeunalCore`: Rust 코어의 XCFramework와 생성된 Swift
  - 카카오 SDK(SPM: 로그인, 사용자, 공유)
  - 그 밖의 서드파티는 쓰지 않습니다. 분석·광고 SDK는 넣지 않습니다.

## 2. 타깃과 공유 자원

| 타깃 | 하는 일 | 코어 사용 |
| --- | --- | --- |
| 앱 | 모든 화면 | `Core.open` |
| 알림 서비스 확장 | 암호문 푸시를 복호화해 알림 문구 교체 | `decryptPush`만 |
| 위젯 확장(WidgetKit) | 오늘 일정, 다음 약속 | 쓰지 않음. 앱이 써 둔 스냅샷만 읽음 |

- **App Group 컨테이너:** SQLCipher 데이터베이스, 잠금 파일, 위젯 스냅샷(JSON)을 둡니다. 파일 보호 등급은 `completeUntilFirstUserAuthentication`입니다.
- **키체인 접근 그룹:** 앱과 알림 확장이 함께 씁니다. 기기 서명 키, AMK, 데이터베이스 키를 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`로 저장해서 iCloud로 넘어가지 않게 합니다.
- **위젯이 코어를 열지 않는 이유:** 위젯 확장의 메모리와 시간 한도 안에서 SQLCipher와 MLS를 열 이유가 없습니다. 앱이 일정이 바뀔 때마다 오늘·다음 약속 스냅샷을 써 두고 `WidgetCenter`에 갱신을 요청합니다.

## 3. 모듈 구조

```
apps/ios/
  GeunalApp.xcodeproj
  Packages/
    GeunalCore/          # binary target (XCFramework) + generated Swift, built by the core workflow
    CoreBridge/          # Keychain SecretStore, CoreListener -> AsyncStream, App Group paths
    DesignSystem/        # 12 group colors with five tints each (the free-count scale), typography, components
    FeatureCalendar/     # month/week/list, quick entry with suggestion chips, event editor with attachments, shift templates, people
    FeatureGroup/        # group list, group calendar (hour strip, cover header), day table (30-min, drag), group settings, invites
    FeatureAppointment/  # date-setting sheet, appointment detail with attachments, vote screen, leader transfer
    FeatureChat/         # timeline with appointment cards, composer, media, gallery, search, report/block
    FeatureAccount/      # onboarding, sign-in, recovery code, backup, device change, account deletion
  NotificationService/   # Notification Service Extension target
  Widget/                # WidgetKit extension target
```

- 뷰모델은 UniFFI가 만든 `CoreProtocol`에만 의존합니다. 테스트에서는 가짜 구현을 넣습니다.
- 코어의 변경 알림(`CoreListener.onChange`)은 `CoreBridge`가 `AsyncStream`으로 바꿔 뷰모델에 흘립니다.
- 코어의 동기 함수(`groupMonth` 등)는 메인 스레드가 아닌 곳에서 부르고, 결과만 메인 액터로 넘깁니다.

## 4. 화면

| 단계 | 화면 |
| --- | --- |
| M1 | 온보딩(로그인, 만 14세 확인, 기기 캘린더 권한, 알림 권한), 월·주·목록 뷰, 빠른 입력 시트(추천 칩 5개), 일정 편집(반복, 알림, 캘린더), 교대 템플릿, 사람·생일, 설정(복구 코드, 백업, 기기 변경, 계정 삭제), 위젯 |
| M2 | 그룹 목록(미응답 배지), 그룹 만들기, 초대(카카오톡 공유·링크·코드), 초대 코드 입력, 그룹 캘린더(월 + 날짜 칸의 1시간 띠), 날 테이블(30분, 합친 줄 + 멤버 줄, 드래그), 날짜 잡기 시트(결정 방식, 독재면 시간·활동), 약속 상세(응답, 참가자, 주도자 이전, 취소), 투표(추천 3개 + 추가 + 아무거나), 그룹 설정(공통: 이름·커버·색·날짜 잡기 기본값 / 나에게만: 그룹 프로필·공유·알림·표시 / 멤버·초대·나가기) |
| M3 | 채팅방(타임라인 + 날짜 잡기 카드), 1:1 시작(같은 그룹 멤버), 사진·파일 보내기와 보기, 일정·약속 첨부, 그룹 사진·파일 모아보기, 기기 안 검색, 신고·차단 |

**색:** 그룹 색의 명도 5단계로 비어 있는 인원수를 칠합니다. 0명은 비워 두고, 1명은 가장 연하게, 전원은 가장 진하게 칠합니다. 색만으로 구분하지 않도록, 칸을 누르면 숫자와 이름이 나옵니다.

**그룹 색:** 팔레트 12색은 밝은·어두운 모드에서 다섯 단계가 서로 구분되고, 가장 진한 단계 위의 흰 글자가 WCAG AA 대비(4.5:1)를 넘도록 고릅니다. 그룹 머리글, 강조색, 내 캘린더의 그 그룹 약속 색이 같은 색을 씁니다. 커버 이미지는 그룹 캘린더 위에 3:1 머리글로 두고 스크롤하면 접습니다.

## 5. OS 연동

| 기능 | API | 메모 |
| --- | --- | --- |
| 기기 캘린더 미러 | EventKit `requestFullAccessToEvents`, `EKEventStoreChanged` | 읽기만. 일정은 코어로 넘겨 로컬에만 저장 |
| 연락처 생일 | Contacts `CNContactStore` | 선택 기능. `nonGregorianBirthday`로 음력 생일도 가져옴 |
| 로컬 알림 | UserNotifications `UNCalendarNotificationTrigger` | iOS는 예약 알림을 64개까지만 유지하므로, 가까운 64개만 예약하고 앱 실행·백그라운드 갱신 때 다시 채움 |
| 원격 푸시 | APNs + 알림 서비스 확장 | 6장 |
| 백그라운드 갱신 | `BGAppRefreshTask` | 기기 캘린더 변경 반영, 가용 시간 올리기. 실행 시점은 보장되지 않음 |
| 로그인 | Sign in with Apple(AuthenticationServices), 카카오 SDK | Apple 로그인은 App Store 지침 4.8 |
| 초대 | Universal Links(`/i/<code>`), 카카오톡 공유, 첫 실행 코드 붙여넣기 | 붙여넣기는 `PasteButton`으로 해서 권한 경고가 뜨지 않게 함 |
| 사진·파일 | `PhotosPicker`, 문서 선택기 | 저장소 권한 없이 |
| 민감 이미지 | SensitiveContentAnalysis `SCSensitivityAnalyzer` | 엔타이틀먼트 필요. 사용자가 기기 설정에서 켰을 때만 동작하고, 받은 사진을 가린 뒤 탭하면 보여 줌 |
| 위젯 | WidgetKit | 2장의 스냅샷 방식 |

## 6. 알림 서비스 확장

1. `didReceive`에서 페이로드의 `g`, `s`, `c`를 꺼냅니다.
2. `decryptPush(storeDir:payload:)`를 부릅니다. 저장소는 App Group 경로입니다.
3. 성공하면 문구를 바꿉니다. 제목은 그룹 이름, 본문은 내용에 따라 다음처럼 씁니다.
   - 채팅: "보낸 사람: 내용"
   - 사진: "보낸 사람: 사진"
   - 날짜 잡기: "보낸 사람: 11/8(토) 날짜 잡기"
4. 잠금 대기(`Locked`)나 큰 빈틈으로 실패하면 서버가 넣은 자리표시 문구를 그대로 둡니다.
5. `serviceExtensionTimeWillExpire`가 오면 자리표시 문구로 끝냅니다.

메모리 한도는 약 24MB입니다. 확장에서는 이미지를 디코드하지 않고, 코어의 `decryptPush` 경로만 씁니다. 실기기에서 최대 메모리를 재서 [Rust 코어](rust-core.md) 8장의 예산(15MB)을 확인합니다.

## 7. 접근성·현지화

- 문자열은 String Catalog로 관리합니다. 한국어가 기본이고 영어는 2단계입니다.
- Dynamic Type을 지원합니다. 띠와 테이블은 글자 크기가 커지면 칸 대신 목록 보기로 바뀝니다.
- VoiceOver 레이블은 "14시, 3명 비어 있음"처럼 숫자로 읽어 줍니다.

## 8. 빌드·CI·배포

- **환경:** 이 개발 환경에는 macOS가 없습니다. iOS 빌드와 테스트는 GitHub Actions의 macOS 러너에서만 합니다.
- **CI 순서:**
  1. 코어 XCFramework 빌드(캐시)
  2. 앱 빌드
  3. 단위 테스트
  4. 시뮬레이터 UI 테스트
- **서명과 배포:** App Store Connect API 키와 인증서를 CI 비밀값으로 두고, M1부터 TestFlight로 올립니다.
- **엔타이틀먼트:** App Groups, Keychain Sharing, Push Notifications, Sign in with Apple, Associated Domains, Sensitive Content Analysis.
- **수출 규정:** `ITSAppUsesNonExemptEncryption`을 설정하고 연간 자가분류 보고 대상인지 확인합니다([개요](../kickoff.md) 7장).

## 9. 테스트

- **뷰모델:** 가짜 `CoreProtocol`로 단위 테스트합니다.
- **UI 테스트:** 로컬 compose 백엔드에 붙어서 돌립니다.
  - 온보딩부터 일정 만들기까지
  - 그룹 캘린더 띠와 날 테이블 드래그
  - 날짜 잡기부터 수락, 캘린더 반영까지
  - 투표 열기, 표, 닫기
  - 채팅 보내기, 받기, 날짜 잡기 카드(M3)
- **알림 확장:** 고정된 저장소와 페이로드로 문구 생성을 검사합니다.
- **스냅샷 테스트:** 그룹 색 12개 각각에서 띠의 색 단계(0명, 1명, 전원)를 밝은·어두운 모드로 확인합니다.

## 10. 완료 조건

| 단계 | 완료 조건 |
| --- | --- |
| M0 | 코어를 불러 `Core.open`과 `sync` 결과를 화면 하나에 표시. CI에서 빌드·테스트 녹색 |
| M1 | M1 화면 전부, 위젯, 기기 캘린더 미러, 로컬 알림 64개 순환, 복구 코드로 새 기기 복원. TestFlight 배포 |
| M2 | M2 화면 전부, Universal Links 초대, 그룹 캘린더 성능 예산(월 화면 스크롤 60fps) |
| M3 | 채팅 화면, 알림 확장 복호화 성공 99% 이상과 메모리 예산 확인, 민감 이미지 가림, 신고·차단 |
| M4 | 접근성 점검, App Store 심사 통과 |
