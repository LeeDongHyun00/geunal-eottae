# 앱 PR 보안 점검표

iOS·안드로이드 PR마다 보안 담당이 보는 것입니다. 앱 파트는 PR을 올리기 전에 스스로 확인하고, PR 설명에 해당하는 항목을 적습니다. 근거는 [위협 모델](threat-model.md) 3.3장(분실 기기), 3.6장(계정 탈취), 4장(로그)입니다.

## 1. 비밀 보관

M1에서 새로 생기는 비밀은 복구 코드의 엔트로피 `E`와 AMK입니다([공통 계약](../parts/protocol.md) 10장). 기기 서명 키, 데이터베이스 키, 세션과 같은 규칙을 따릅니다.

| 항목 | iOS | 안드로이드 |
| --- | --- | --- |
| 비밀은 코어의 `SecretStore` 콜백으로만 읽고 씀 | `KeychainSecretStore` | `KeystoreSecretStore` |
| 다른 곳에 복사하지 않음 | UserDefaults, 파일, 클립보드 기록에 두지 않음 | SharedPreferences, DataStore, 파일에 두지 않음 |
| 기기를 떠나지 않음 | `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. `kSecAttrSynchronizable`은 넣지 않음(iCloud 키체인 동기화 안 함) | `noBackupFilesDir/secrets`에 두고, 내보낼 수 없는 Keystore의 AES-256-GCM 키로 봉인 |
| 백업에서 빠짐 | ThisDeviceOnly 항목은 백업으로 다른 기기에 넘어가지 않음 | `noBackupFilesDir`는 자동 백업과 기기 간 이전에서 빠짐. 백업 규칙 파일은 `sharedpref`만 넣음 |
| 함께 쓰는 곳 | 키체인 접근 그룹은 앱과 알림 확장만 | 해당 없음 |
| 다시 설치 | 저장소가 없을 때(이 기기에 처음 설치됐을 때) 이 앱의 키체인 항목을 모두 지움. 앱을 지워도 키체인 항목은 남기 때문. UserDefaults의 실행 표시는 기준으로 쓰지 않음. 표시는 기기 이전으로 따라오고, 표시가 생기기 전 버전에서 올린 설치에는 없기 때문 | 앱을 지우면 Keystore 키와 파일이 함께 지워짐 |
| 기기 이전 | iCloud 백업 복원과 빠른 시작은 UserDefaults만 옮기고, ThisDeviceOnly 항목과 백업에서 뺀 저장소는 옮기지 않음. 그래서 온보딩을 마쳤는지도 저장소가 있는지로 판단하고, 없으면 온보딩 상태를 비움. 표시만 믿으면 새 기기가 세션 없이 탭으로 가서 복원할 길을 잃음 | 자동 백업과 기기 간 이전은 `sharedpref`만 옮김. 온보딩 상태를 SharedPreferences에 두면 iOS와 같은 기준으로 비움 |
| 해제와 계정 삭제 | 403 `device_revoked`·`revoke` 이벤트를 받거나 계정을 지우면 세션, 기기 서명 키, `E`, AMK, 그룹 상태를 지우고 일정과 DB 키만 남김. 도난 기기에 남은 `E`로 게스트 계정을 다시 가져가지 못하게 하려는 것([공통 계약](../parts/protocol.md) 13장) | 같음 |

- SharedPreferences와 UserDefaults는 기기 백업에 들어갑니다. 비밀과 개인정보를 두지 않습니다. "종이에 적었어요" 같은 표시는 괜찮습니다.
- 2026-10-25 `main` 기준으로 두 `SecretStore`는 위 설정과 맞습니다(`apps/ios/Packages/CoreBridge/Sources/CoreBridge/KeychainSecretStore.swift`, `apps/android/bridge/src/main/kotlin/app/geunal/bridge/KeystoreSecretStore.kt`, `AppStorage.kt`). `E`와 AMK도 이 콜백으로만 다니면 같은 보호를 받습니다. 첫 실행 키체인 정리만 아직 없습니다(iOS #36에서 더하는 중).

## 2. 그 밖에 평문이 남는 곳

| 곳 | 기준 |
| --- | --- |
| 위젯 스냅샷 | 오늘·내일 일정 제목이 평문으로 들어갑니다. iOS는 App Group 컨테이너에 파일 보호 `completeUntilFirstUserAuthentication`과 백업 제외(`isExcludedFromBackup`). 안드로이드는 `noBackupFilesDir` 아래. 로그아웃과 계정 삭제 때 지움 |
| 로컬 알림 | 예약한 알림의 제목은 OS가 보관합니다. 로그아웃과 계정 삭제 때 예약과 이미 뜬 알림을 지움(iOS `removeAllPendingNotificationRequests`·`removeAllDeliveredNotifications`, 안드로이드 알람 취소와 `NotificationManager.cancelAll`) |
| 앱 전환기 미리보기 | iOS는 앱이 뒤로 갈 때 화면을 저장합니다. 복구 코드 화면과 기기 바꾸기 입력 화면은 `scenePhase`가 `.active`가 아니면 가림 |
| 클립보드 | 복구 코드 복사는 iOS `UIPasteboard`에 `.localOnly`(다른 Apple 기기로 넘기지 않음)와 `.expirationDate`(60초). 안드로이드는 `ClipDescription.EXTRA_IS_SENSITIVE`(13 이상)를 붙이고, 앱은 앞에 있을 때만 클립보드를 지울 수 있으므로 화면을 떠날 때도 지움 |

## 3. 복구 코드 화면

| 항목 | 기준 |
| --- | --- |
| 기기 인증 | 2026-10-26 사용자 결정. 세 곳에서 기기 주인을 확인함: 복구 코드 12단어를 보여 주기 전(13번), "백업 없이 새로 시작"이 백업을 지우기 직전(21번), 계정 삭제의 마지막 단계(22번, 제공자 재인증·게스트 서명 전). 앱이 그리는 화면 없이 OS 확인 창만 띄우고 이유 문구는 [M1 화면](../design/m1.md) 4장 "기기 인증"을 따름. 취소하면 아무것도 하지 않음. 잠금이 풀린 채 잃은 기기에서 12단어를 읽거나, 카카오톡 확인 한 번으로 받은 새 제공자 증명으로 백업과 계정을 지우는 것을 막으려는 것([위협 모델](threat-model.md) 3.6) |
| 기기 인증 방법 | iOS는 `LAContext`의 `.deviceOwnerAuthentication`(생체 인증이 안 되면 기기 암호). `Info.plist`에 `NSFaceIDUsageDescription`이 있어야 함(없으면 Face ID 인증이 실패할 수 있음). 안드로이드는 `BiometricPrompt`에 `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. 이 조합은 API 28·29에서 지원되지 않아 `build()`가 실패하므로, API 28·29(안드로이드 9·10)에서는 `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`을 씀. API 30 미만을 모두 이 조합으로 해도 됨. 키를 여는 것이 아니라 화면을 여는 확인이라 그래도 됨 |
| 화면 잠금이 없을 때 | 묻지 않고 진행하고, 화면 잠금을 권하는 문구를 보여 줌. iOS는 `canEvaluatePolicy`가 `LAError.passcodeNotSet`일 때, 안드로이드는 `KeyguardManager.isDeviceSecure`가 거짓일 때. 확인할 것이 없고, 계정 삭제는 스토어 규정상 늘 할 수 있어야 하기 때문 |
| 캡처 | 안드로이드는 복구 코드 화면과 기기 바꾸기 입력 화면에 `FLAG_SECURE`. iOS는 막을 수 없어 캡처를 감지하면 토스트([M1 화면](../design/m1.md) 13번) |
| 12칸 입력 | 자동 고침, 맞춤법, 키보드 학습을 끔(iOS `.autocorrectionDisabled()`, 안드로이드 `TYPE_TEXT_FLAG_NO_SUGGESTIONS`와 `IME_FLAG_NO_PERSONALIZED_LEARNING`). 단어가 키보드 사전이나 추천에 남지 않게 하려는 것 |

## 4. 로그인과 계정 삭제

| 항목 | 기준 |
| --- | --- |
| 제공자에게 넘기는 nonce | 카카오·Apple 로그인에는 `GET /auth/nonce`의 값을 그대로 넘기지 않고, 코어가 계산한 묶기 값(`hex(SHA-256("geunal login v1" ‖ nonce ‖ signature_key))`)을 넘김([공통 계약](../parts/protocol.md) 13장). 벡터 `login_nonce.json`과 같아야 함 |
| 서명 | 교체(`POST /auth/replace`), 게스트 복귀(`POST /auth/recover`), 복구 자료 덮어쓰기, 게스트 계정 삭제의 서명은 코어가 만듦. 앱은 키를 직접 다루지 않음 |
| 계정 삭제 전 재인증 | 기기 인증(3장)을 받은 뒤, 삭제 직전에 다시 증명함([공통 계약](../parts/protocol.md) 13장). 카카오는 카카오 로그인을 한 번 더 받아 ID 토큰을, Apple은 Sign in with Apple을 한 번 더 받아 인가 코드를 넣음. 안드로이드는 웹 로그인 콜백이 넘긴 `code`를 로그인에는 버리고 삭제 때만 씀. 게스트는 코어가 복구 서명을 조용히 만들므로 앱이 따로 묻지 않음("삭제" 입력 확인은 그대로) |
| 백업 없이 새로 시작 | 기기 인증(3장)을 받은 뒤 지움. 제공자 로그인으로 세션을 받은 지 30분이 지났으면, 계정 삭제와 같은 새 제공자 증명을 `PUT /me/recovery`에 붙임([공통 계약](../parts/protocol.md) 10장). 다시 로그인하지 않음. 다른 카카오 계정으로 로그인하면 이 기기가 그 계정에 등록되기 때문 |
| 재인증 실패 | 403 `reauth_required`는 세션이 유효하다는 뜻이라, 로그아웃하지 않고 증명만 다시 받음. 409 `recovery_missing`이면 복구 자료를 먼저 올린 뒤 다시 지움 |
| 받은 토큰과 코드 | 삭제에 쓴 ID 토큰과 인가 코드는 저장하거나 로그에 남기지 않음 |

## 5. 로그와 크래시

| 항목 | 기준 |
| --- | --- |
| 남기지 않는 것 | 일정 제목·메모·시각, 사람 이름·생일, 12단어와 `E`, AMK·키·토큰·nonce·서명, 서버 응답 본문, 기기 캘린더 내용 |
| 남겨도 되는 것 | 상태 코드(OSStatus, HTTP), 계약의 오류 코드(`error`), 개수, 걸린 시간, 화면 이름 |
| iOS | `Logger`(OSLog)만 씀. `print`, `NSLog`, `debugPrint`는 쓰지 않음. 문자열 보간에 `privacy: .public`을 붙이지 않음(기본이 비공개) |
| 안드로이드 | `Log.v`·`Log.d`·`Log.i`는 릴리스에서 R8이 지우게 함(`-assumenosideeffects`). `Log.w`·`Log.e`에는 내용을 넣지 않음. `println`은 쓰지 않음 |
| 예외 메시지 | 예외와 오류 메시지에 내용을 넣지 않음. 크래시 보고(Xcode Organizer, Play Console)로 Apple·Google 서버에 올라감 |
| 코어 오류 | `CoreError`·`CoreException`의 메시지를 그대로 화면과 로그에 쓰지 않고, 코드로 분기 |
| 카카오 SDK | SDK 로그 출력을 릴리스에서 끔 |

## 6. 점검 방법

PR의 앱 변경에서 아래 줄을 찾아 하나씩 봅니다.

```sh
git diff origin/main... -- apps/ | grep -nE '^\+.*(print\(|NSLog|debugPrint|Log\.[vdiwe]\(|println\(|privacy: \.public|UserDefaults|SharedPreferences|DataStore|kSecAttr|FLAG_SECURE|UIPasteboard|ClipboardManager|allowBackup|dataExtractionRules)'
```

PR 설명에는 다음을 적습니다.
- 새 비밀이나 새 저장 위치가 생겼는지
- 새 로그 줄이 있으면 무엇을 남기는지
- 위 표에서 해당하는 항목과, 해당하지 않으면 "해당 없음"
