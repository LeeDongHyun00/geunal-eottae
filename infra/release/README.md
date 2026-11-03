# 앱 배포(TestFlight, 플레이 내부 테스트)

M1부터 알파 빌드를 TestFlight와 플레이 내부 테스트 트랙으로 보냅니다([인프라](../../docs/parts/infra.md) 5장). 이 디렉터리는 그 흐름과, 인프라·앱 세션·사용자가 각자 맡는 몫을 적습니다.

## 흐름

| 워크플로 | 하는 일 | 환경(비밀값) |
| --- | --- | --- |
| `.github/workflows/release-ios.yml` | `main`에서 앱을 빌드해 App Store Connect API 키로 클라우드 서명하고 TestFlight에 올림 | `testflight` |
| `.github/workflows/release-android.yml` | `main`에서 앱 번들을 빌드해 업로드 키로 서명하고 내부 테스트 트랙에 올림 | `play-internal` |

- **손으로만 돌립니다.** Actions 탭에서 워크플로를 고르고 "Run workflow"를 누릅니다. `main`이 아니면 작업이 건너뛰어집니다.
- **사용자가 승인해야 비밀값이 열립니다.** 두 환경에 사용자를 필수 검토자로 둡니다. 실행하면 승인을 기다리고, 승인한 뒤에야 비밀값이 작업에 들어갑니다. PR이나 포크의 워크플로는 이 비밀값을 볼 수 없습니다([QA](../../docs/qa.md) 5.2 규칙 9).
- **비밀값은 그 작업 안에서만 파일이 됩니다.** 러너의 임시 디렉터리에 소유자만 읽게 쓰고, 끝나면 지웁니다.
- **빌드 번호는 2026-01-01(UTC)부터 지난 분입니다.** 두 플랫폼 모두 늘 앞의 업로드보다 크고, iOS `CFBundleVersion`과 안드로이드 `versionCode`의 한도보다 훨씬 작습니다. 표시 버전(`0.1.0`)은 앱 세션이 올립니다.
- **알파 빌드는 `https://api.geunaleottae.com`을 씁니다.** 워크플로가 서버 주소와 비밀이 아닌 식별자(Apple 팀 ID, 서비스 ID, App Link 호스트)를 넘깁니다.

## 앱 세션이 만들 `make` 대상

워크플로는 아래 환경 변수를 채운 뒤 `make release-ios`, `make release-android`를 부릅니다. 빌드와 업로드는 앱 파트의 일이라 앱 세션이 이 두 대상을 만듭니다. 대상이 생기기 전에 워크플로를 돌리면 `make`가 대상이 없다며 실패합니다.

| 변수 | iOS | 안드로이드 | 뜻 |
| --- | --- | --- | --- |
| `GEUNAL_BUILD_NUMBER` | ✓ | ✓ | `CFBundleVersion`, `versionCode` |
| `GEUNAL_API_BASE` | ✓ | ✓ | 서버 주소(iOS `GEUNAL_API_BASE`, 안드로이드 `geunalApiBase`) |
| `GEUNAL_KAKAO_APP_KEY` | ✓ | ✓ | 카카오 네이티브 앱 키 |
| `GEUNAL_APPLE_TEAM_ID` | ✓ | | `DEVELOPMENT_TEAM` |
| `GEUNAL_ASC_KEY_PATH`, `GEUNAL_ASC_KEY_ID`, `GEUNAL_ASC_ISSUER_ID` | ✓ | | 클라우드 서명과 업로드(`xcodebuild -allowProvisioningUpdates -authenticationKey*`, 내보내기 `destination: upload`) |
| `GEUNAL_APPLE_SERVICE_ID`, `GEUNAL_APP_LINK_HOST` | | ✓ | `geunalAppleServiceId`, `geunalAppLinkHost` |
| `GEUNAL_UPLOAD_KEYSTORE`, `GEUNAL_UPLOAD_KEYSTORE_PASSWORD`, `GEUNAL_UPLOAD_KEY_ALIAS`, `GEUNAL_UPLOAD_KEY_PASSWORD` | | ✓ | 릴리스 서명 설정 |
| `GEUNAL_PLAY_SERVICE_ACCOUNT` | | ✓ | Play Developer API로 내부 트랙에 올릴 서비스 계정 JSON 파일 |
| `GEUNAL_PLAY_UPLOAD` | | ✓ | `true`면 올리고, `false`면 서명한 번들만 만듦 |

- 안드로이드는 서명한 번들을 `apps/android/app/build/outputs/bundle/release/`에 둡니다. 워크플로가 그것을 7일짜리 아티팩트로 남깁니다.
- 대상은 비밀값을 명령줄 인자나 로그에 내지 않습니다. 파일 경로나 환경 변수로만 넘깁니다.
- 로컬 빌드는 지금처럼 이 변수 없이 돌고, 서명하지 않습니다.

## 사용자가 준비할 것

순서대로 하면 됩니다. 비밀값은 GitHub 환경의 비밀값에만 넣고, 저장소나 대화에는 붙여 넣지 않습니다.

1. **GitHub 환경 두 개:** 저장소 Settings › Environments에서 `testflight`, `play-internal`을 만듭니다. 둘 다 Required reviewers에 본인을 넣고, Deployment branches를 `main`으로 제한합니다.
2. **App Store Connect API 키:** Users and Access › Integrations › App Store Connect API › Team Keys에서 만듭니다. `.p8`은 한 번만 내려받을 수 있습니다.
   - 역할은 Admin으로 둡니다. 클라우드 서명이 배포 인증서와 프로필을 만들어야 하기 때문입니다. 더 낮은 역할(App Manager)로 되는지는 첫 업로드 뒤에 확인해 낮춥니다.
   - `testflight` 환경 비밀값: `ASC_KEY_P8`(파일 내용 전체), `ASC_KEY_ID`, `ASC_ISSUER_ID`, `KAKAO_APP_KEY`.
3. **Apple 개발자 계정의 App ID 설정:** `app.geunal.ios`에 앱이 쓰는 기능(Sign in with Apple, App Groups 등)을 켭니다. 무엇을 켤지는 iOS 세션이 적어 드립니다.
4. **플레이 업로드 키:** PC에서 키 저장소를 만듭니다. 예: `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 9125`. 이 파일과 비밀번호는 PC 밖 안전한 곳에도 보관합니다. 잃으면 Play에 업로드 키 재설정을 요청해야 합니다.
   - `play-internal` 환경 비밀값: `ANDROID_UPLOAD_KEYSTORE_B64`(`base64 -i upload.jks`의 결과), `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_KEY_PASSWORD`, `KAKAO_APP_KEY`.
5. **플레이 서비스 계정:** Google Cloud에서 서비스 계정을 만들고 JSON 키를 받습니다. Play Console › 사용자 및 권한에서 이 계정을 초대해, 이 앱에만 "테스트 트랙에 출시" 권한을 줍니다.
   - `play-internal` 환경 비밀값: `PLAY_SERVICE_ACCOUNT_JSON`(파일 내용 전체).
6. **플레이 첫 번들은 손으로:** Play는 새 앱의 첫 번들을 API로 받지 않습니다. `release-android`를 `upload` 끄고 돌려 아티팩트의 `.aab`를 받은 뒤, Play Console › 내부 테스트에 직접 올립니다. 이때의 서명이 업로드 키로 등록됩니다. 그 뒤로는 `upload`를 켠 채로 돌립니다.
7. **테스터:** TestFlight 내부 테스터와 플레이 내부 테스트의 테스터 목록에 알파 테스터를 넣습니다.

## 아직 정하지 않은 것

- **수출 규정(암호화):** 앱이 종단 간 암호화를 쓰므로, App Store Connect가 빌드마다 수출 규정 질문을 합니다. 답을 정해 `ITSAppUsesNonExemptEncryption`을 `Info.plist`에 두면 질문이 사라집니다. 답은 사용자가 정합니다.
- **자동 배포:** 지금은 손으로만 돌립니다. `main` 머지마다 올리면 테스터가 받는 빌드가 너무 잦고 TestFlight 처리량도 씁니다. 알파가 안정되면 다시 봅니다.
