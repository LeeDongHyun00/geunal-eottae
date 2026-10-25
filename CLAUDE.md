# Claude 작업 안내

그날어때는 Claude가 직군을 나눠 개발합니다([직군별 할 일](docs/plan.md)). 어느 직군으로 들어오든 이 파일의 규칙을 따릅니다.

## 먼저 읽을 것

1. [개요](docs/kickoff.md): 3장 설계 원칙, 6장 보안·개인정보 모델, 8장 게이트, 9장 진행 규칙
2. [직군별 할 일](docs/plan.md): 지금 단계에서 내 직군이 할 일
3. 내 파트 문서와 [공통 계약](docs/parts/protocol.md)

## 디렉터리와 담당 직군

| 경로 | 담당 |
| --- | --- |
| `core/` | Rust 코어 |
| `apps/ios/`, `apps/android/` | iOS, 안드로이드 |
| `server/api/` | 백엔드 |
| `server/gateway/` | 실시간 |
| `infra/`, `scripts/`, `.github/`, `.claude/`, `Makefile`, 루트 설정 파일 | 인프라 |
| `docs/` | 기획(사용자가 확정). `docs/parts/protocol.md`는 계약을 바꾸는 파트가 벡터와 함께 고침 |

- 파트는 자기 `make` 대상과 `.github/workflows/<part>.yml`을 직접 더합니다.
- 그 밖에 다른 직군의 경로를 고쳐야 하면 PR 설명에 이유를 적습니다.

## 환경

- 클라우드 세션은 시작할 때(재개 포함) SessionStart 훅(`.claude/settings.json`, `.claude/hooks/session-start.sh`)이 `scripts/dev-setup.sh`를 실행합니다. 다른 곳에서는 `make setup`을 직접 실행합니다.
- Docker가 필요한 대상은 `scripts/ensure-docker.sh`를 먼저 부릅니다. 클라우드 세션은 Docker 데몬 없이 시작하고 기계가 재시작되면 데몬도 사라지므로, 이 스크립트가 필요할 때 데몬을 띄웁니다.
- 린트 도구(actionlint, shellcheck)는 `scripts/tool.sh`가 GitHub 릴리스에서 받아 SHA-256을 확인한 뒤 `.tools/`에 둡니다. 버전을 올릴 때는 이 파일의 해시도 함께 바꿉니다.
- Android SDK(플랫폼 37.0, build-tools 36.0.0, platform-tools, NDK r28c)도 `scripts/tool.sh android-sdk`가 Google의 zip을 SHA-256으로 확인해 설치합니다. `ANDROID_HOME`이 있으면 그 SDK에 빠진 패키지를 넣고, 없으면 `.tools/android-sdk`에 둡니다. 안드로이드 `make` 대상이 이 경로를 Gradle에 넘깁니다.
- 클라우드 세션의 바깥 연결은 프록시를 거칩니다.
  - Docker Hub는 세션들이 함께 쓰는 주소에 익명 내려받기 한도(429)를 걸어서, `ensure-docker.sh`가 데몬을 Google 미러(`mirror.gcr.io`)를 거치게 띄웁니다.
  - Maven Central은 같은 파일에도 404·429를 섞어 돌려줍니다. 그래서 `make setup`이 `~/.gradle/init.d`에 초기화 스크립트를 넣어 Gradle이 Google의 Maven Central 미러를 쓰게 합니다. 빌드 파일에는 `mavenCentral()`을 그대로 씁니다.
  - 컨테이너 안의 프로세스는 프록시에 닿지 못하고 프록시 인증서도 믿지 않습니다. 그래서 이미지는 `scripts/docker-build.sh`로 빌드합니다. 클라우드 세션에서는 이 스크립트가 빌드를 호스트 네트워크에 붙이고 프록시 주소(`https_proxy`)와 인증서(빌드 비밀값 `ca`)를 넘깁니다. 이 프록시는 HTTPS만 통과시키므로, 무언가를 내려받는 Dockerfile은 `ca`가 있을 때 HTTPS로 받고 그 인증서를 믿게 합니다(`server/api/Dockerfile`, `server/gateway/Dockerfile` 참고).
  - 세션에 붙지 않은 GitHub 저장소는 웹 페이지와 API가 막혀 있습니다. 릴리스 파일 직접 다운로드와 `git ls-remote`는 됩니다.

## 명령

`make help`가 전체 목록을 보여 줍니다. CI도 같은 대상을 부르므로, 로컬에서 통과한 명령이 CI에서도 그대로 돕니다.

| 대상 | 하는 일 |
| --- | --- |
| `make setup` | 도구 설치(다시 실행해도 안전) |
| `make lint` | 모든 린트 |
| `make test` | 모든 파트의 테스트 |
| `make lint-repo` | 워크플로(actionlint)와 저장소의 모든 셸 스크립트(shellcheck) 검사 |
| `make lint-core` | Rust 코어: rustfmt, clippy, cargo-deny |
| `make test-core` | Rust 코어: 단위·스모크 테스트 |
| `make build-android` | 안드로이드 3개 ABI 라이브러리와 Kotlin 바인딩(`core/out/android`), ABI별 크기 기록 |
| `make build-ios` | XCFramework와 Swift 바인딩(`core/out/ios`). macOS에서만 되고, 이 환경에서는 CI가 확인합니다 |
| `make lint-ios` | iOS: swift-format, 디자인 토큰의 Swift가 `docs/design/tokens.json`과 같은지. macOS에서만 |
| `make project-ios` | iOS: 코어 산출물을 `apps/ios/Packages/GeunalCore`로 복사하고 XcodeGen으로 `GeunalApp.xcodeproj`를 생성. macOS에서만 |
| `make app-ios` | iOS: 시뮬레이터용 앱 빌드. macOS에서만 |
| `make test-ios` | iOS: 시뮬레이터 단위 테스트. macOS에서만 |
| `make uitest-ios` | iOS: 시뮬레이터 UI 테스트. macOS에서만 |
| `make lint-android` | 안드로이드: ktlint와 Android Lint. 먼저 `build-android`를 돌림 |
| `make test-android` | 안드로이드: 디버그·R8 릴리스·계측 테스트 조립과 단위 테스트. 먼저 `build-android`를 돌림 |
| `make connected-android` | 안드로이드: adb에 보이는 에뮬레이터·기기에서 계측 테스트 |
| `make lint-api` | API: ktlint |
| `make test-api` | API: Testcontainers의 PostgreSQL·Redis에 붙는 테스트 |
| `make image-api` | API: boot jar와 이미지 `geunal/api:local` |
| `make scan-api` | API: 이미지 Trivy 검사. 고칠 수 있는 높음·심각 항목이 있으면 실패 |
| `make lint-gateway` | 게이트웨이: 잠긴 의존성, format, 컴파일 경고, credo, 이미지의 Elixir·OTP 버전 |
| `make test-gateway` | 게이트웨이: Redis에 붙는 테스트. `REDIS_URL`이 없으면 쓰고 버릴 컨테이너를 띄움 |
| `make image-gateway` | 게이트웨이: 릴리스 이미지 `geunal/gateway:local` |
| `make scan-gateway` | 게이트웨이: 이미지와 `mix.lock`의 Trivy 검사 |
| `make lint-terraform` | Terraform: `fmt` 검사와 `infra/terraform/envs/*` 루트의 `init`(백엔드 없이)·`validate` |
| `make lock-terraform` | Terraform: 제공자 버전을 올린 뒤 루트의 잠금 파일에 Linux·macOS 체크섬을 다시 기록 |
| `make up` | 로컬 스택(`infra/docker-compose.yml`): 이미지를 빌드하고 PostgreSQL·Redis·S3·API·게이트웨이를 띄움 |
| `make down` | 로컬 스택을 멈추고 데이터를 지움 |
| `make e2e` | 로컬 스택을 띄우고 `core/geunal-e2e`의 헤드리스 종단 시험을 돌림. 스택은 그대로 둠 |
| `make audit` | 모든 파트의 의존성 취약점 게이트(cargo-deny advisories, Trivy). `.github/workflows/audit.yml`이 주 1회 `main`에서 돌림 |
| `make audit-core` | Rust 코어: cargo-deny advisories만 |

## 규칙

- **커밋:** Conventional Commits, 영어, 명령형. 범위(scope)는 파트 이름입니다(`feat(core): ...`, `fix(api): ...`, `ci: ...`).
- **PR:** 파트 단위로 나눠 `main`으로 보냅니다. `ci-ok`가 녹색이어야 머지합니다.
- **계약:** 공통 계약을 바꾸는 PR은 `docs/parts/protocol.md`와 `core/vectors/`를 먼저 바꿉니다.
- **버전 고정:** 툴체인(`.tool-versions`와 각 파트의 툴체인 파일), 잠금 파일, 컨테이너 이미지 태그를 고정하고, GitHub Actions는 커밋 SHA로 고정합니다. Rust 코어는 [개요](docs/kickoff.md) 부록 B에서 검증한 버전을 쓰고, 버전 올리기는 기능 PR과 섞지 않고 따로 합니다.
- **주석과 문서:** 주석은 영어로, 무엇을 하는지보다 왜 그런지를 적습니다. 문서는 한국어로 씁니다.
- **비밀값과 로그:** 비밀값은 저장소에 넣지 않습니다. 로그에 요청·응답 본문, 암호문, 토큰, 개인정보를 남기지 않습니다([개요](docs/kickoff.md) 6장, [인프라](docs/parts/infra.md) 8장).

## 여러 세션이 함께 일할 때

직군마다 세션을 따로 열고, 모두 같은 `main`으로 PR을 보냅니다.

- **브랜치:** 최신 `main`에서 `claude/<직군>-<주제>`로 만듭니다. 다른 세션의 브랜치에는 푸시하지 않습니다.
- **공유 파일:** `Makefile`, `.github/workflows/ci.yml`, `.github/dependabot.yml`, `scripts/ci/changed-parts.sh`, `CLAUDE.md`, `README.md`는 여러 파트가 함께 고칩니다. 자기 파트의 줄만 더하고, 충돌이 나면 `main`을 합친 뒤 다시 검사합니다.
- **머지:** 다음을 모두 갖춘 PR은 squash로 머지합니다.
  - `ci-ok`가 녹색입니다.
  - 리뷰 봇(Codex)의 지적을 고쳤거나, 고치지 않는 이유를 답한 뒤 스레드를 해결했습니다.
  - `docs/` 문서는 사용자가 확정하므로, 문서 PR은 사용자의 확인을 받았습니다.
- **다른 직군에 걸린 결정:** 계약이나 다른 파트의 동작에 영향을 주는 결정은 PR 설명의 "검토할 결정"에 적습니다. 그 직군이 이어받을 수 있게 하려는 것입니다.
- **보고:** 단계마다 바뀐 것, 확인한 방법, 확인하지 못한 것, 다음 할 일을 사용자에게 알립니다.

## CI에 파트 붙이기

1. `.github/workflows/<part>.yml`을 `on: workflow_call`로 만들고, 단계에서는 `make` 대상을 부릅니다.
2. `ci.yml`에 작업을 더합니다: `needs: changes`, `if: needs.changes.outputs.<part> == 'true'`, `uses: ./.github/workflows/<part>.yml`.
3. 그 작업을 `ci-ok`의 `needs`에 넣습니다. 빠뜨리면 그 파트가 실패해도 머지가 막히지 않습니다.
4. 경로 규칙은 `scripts/ci/changed-parts.sh`에 있습니다. core, api, gateway, e2e, terraform, ios, android 규칙이 이미 있습니다.
5. `.github/dependabot.yml`에 그 파트의 생태계(cargo, gradle, mix, docker)를 더합니다.
