# 안드로이드 앱

iOS 앱과 같은 역할입니다. 화면과 OS 연동만 맡고, 상태 계산·암호화·동기화는 [Rust 코어](rust-core.md)가 합니다. 기능 규칙은 [개요](../kickoff.md) 5장에 있습니다.

## 1. 기술과 최소 버전

- **언어와 UI:** Kotlin 최신 안정판, Jetpack Compose, Material 3, 코루틴과 Flow
- **SDK:** minSdk 26, targetSdk는 그해 Play 요구 버전
- **외부 의존성:**
  - `geunal-core`: Rust 코어의 `jniLibs`, 생성된 Kotlin, JNA
  - 카카오 SDK(로그인, 공유)
  - Firebase Cloud Messaging
  - Play Install Referrer
  - Glance
  - 분석·광고 SDK는 넣지 않습니다.
- **의존성 주입:** 앱 단위 컨테이너를 손으로 만듭니다. 모듈 수가 적어서 프레임워크를 들일 이유가 없습니다.

## 2. 모듈 구조

```
apps/android/
  settings.gradle.kts
  core/                  # Android library: jniLibs from cargo-ndk + UniFFI-generated Kotlin (JNA)
  bridge/                # SecretStore backed by Android Keystore, CoreListener -> Flow, file paths
  designsystem/          # colors (incl. the free-count scale), typography, components
  feature-calendar/
  feature-group/
  feature-appointment/
  feature-chat/
  feature-account/
  widget/                # Glance widgets reading the snapshot the app writes
  app/                   # navigation, DI container, FirebaseMessagingService, WorkManager workers
```

- **비밀 보관:** 안드로이드 키스토어는 임의의 값을 그대로 저장하지 못합니다. 그래서 `bridge`의 `SecretStore`가 키스토어의 AES 키(기기 밖으로 나가지 않음)로 비밀을 감싸 앱 전용 파일에 저장합니다. 자동 백업(`allowBackup`)에서는 이 파일과 데이터베이스를 뺍니다.
- **뷰모델:** UniFFI가 만든 인터페이스에만 의존하고, 테스트에서는 가짜 구현을 씁니다.
- **변경 알림:** 코어의 `CoreListener.onChange`는 `callbackFlow`로 바꿔 흘립니다.

## 3. 화면

iOS와 같습니다([iOS 앱](ios.md) 4장). 단계별 화면 목록과 색 규칙도 같습니다. 플랫폼 관례만 따릅니다(뒤로 가기, Material 컴포넌트, 알림 채널).

## 4. OS 연동

| 기능 | API | 메모 |
| --- | --- | --- |
| 기기 캘린더 미러 | `CalendarContract`(`READ_CALENDAR`), `ContentObserver` | 읽기만. 변경이 오면 WorkManager로 코어에 넘김 |
| 연락처 생일 | `ContactsContract` 생일 이벤트(`READ_CONTACTS`) | 선택 기능. 음력 여부는 표준 필드가 없어 사용자가 표시 |
| 로컬 알림 | `AlarmManager` 정확한 알람 + `NotificationCompat` | 캘린더 앱이라 `USE_EXACT_ALARM` 대상이 될 수 있으나 현재 Play 정책을 확인한 뒤 선언([개요](../kickoff.md) 7장). 안 되면 `SCHEDULE_EXACT_ALARM`을 요청 |
| 원격 푸시 | FCM 데이터 메시지(높은 우선순위) | 5장 |
| 알림 권한 | `POST_NOTIFICATIONS`(13 이상) | 온보딩에서 요청 |
| 백그라운드 | WorkManager | 기기 캘린더 변경 반영, 가용 시간 올리기(최소 주기 15분) |
| 로그인 | 카카오 SDK, Sign in with Apple(웹 방식) | iOS에서 Apple로 가입한 사람이 안드로이드로 옮겨도 같은 계정으로 들어오게 함 |
| 초대 | App Links(`/i/<code>`), Play Install Referrer, 카카오톡 공유 | 설치 전에 받은 초대 코드를 Install Referrer로 넘겨받음 |
| 사진·파일 | Photo Picker(`PickVisualMedia`), 문서 선택기 | 저장소 권한 없이 |
| 민감 이미지 | 서드파티 앱이 쓸 수 있는 기기 내 판별 공개 API를 확인하지 못함 | MVP는 신고·차단으로 대응. 2단계에 다시 조사 |
| 위젯 | Glance | 앱이 써 둔 스냅샷만 읽음 |

## 5. 푸시 처리

1. `FirebaseMessagingService.onMessageReceived`에서 `g`, `s`, `c`를 꺼냅니다.
2. `decryptPush(storeDir, payload)`를 부르고, 성공하면 `NotificationCompat.MessagingStyle`로 알림을 만듭니다. 채널은 채팅, 날짜 잡기, 약속 알림으로 나눕니다.
3. 실패하면 "새 메시지가 있어요"로 알립니다.

- **일반 문구라도 반드시 알림을 띄웁니다.** 높은 우선순위 메시지가 화면에 보이는 알림으로 이어지지 않으면 FCM이 우선순위를 낮춥니다. 그래서 서버는 알림을 띄울 엔트리만 보냅니다([공통 계약](protocol.md) 8장).
- **처리 시간:** 복호화는 수백 ms 안에 끝나므로 서비스 안에서 바로 처리합니다. 빈틈을 가져와야 하면 긴급 작업(expedited work)으로 넘깁니다.

## 6. 빌드·CI·배포

- **환경:** 리눅스 개발 환경과 CI(Ubuntu)에서 빌드합니다.
- **CI 순서:**
  1. `cargo-ndk`로 코어 `.so` 빌드(캐시)
  2. Gradle 조립
  3. 단위 테스트(JUnit, Robolectric)
  4. 에뮬레이터 계측 테스트(주요 흐름 몇 개)
- **배포:** M1부터 Play 내부 테스트 트랙에 AAB를 올립니다. 서명 키는 Play 앱 서명을 쓰고, 업로드 키만 CI 비밀값으로 둡니다.
- **R8 난독화:** UniFFI가 만든 클래스와 JNA는 보존 규칙(keep rules)에 넣습니다.

## 7. 테스트

- **뷰모델:** 가짜 코어로 단위 테스트합니다.
- **계측 테스트:** 로컬 compose 백엔드에 붙어서 iOS UI 테스트와 같은 흐름을 돌립니다.
- **푸시 핸들러:** 고정된 저장소와 페이로드로 알림 생성을 검사합니다.
- **Install Referrer:** 초대 코드 전달을 확인합니다.

## 8. 완료 조건

| 단계 | 완료 조건 |
| --- | --- |
| M0 | 코어를 불러 `Core.open`과 `sync` 결과를 화면 하나에 표시. CI 녹색 |
| M1 | M1 화면 전부, Glance 위젯, 기기 캘린더 미러, 정확한 알람, 복구 코드로 새 기기 복원. 내부 테스트 트랙 배포 |
| M2 | M2 화면 전부, App Links와 Install Referrer 초대, 그룹 캘린더 스크롤 60fps |
| M3 | 채팅 화면, 푸시 복호화 성공 99% 이상, 신고·차단 |
| M4 | Play 정책 점검(사용자 생성 콘텐츠, 정확한 알람, 데이터 보안 섹션), 출시 |
