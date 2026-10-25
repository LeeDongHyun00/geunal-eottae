# 그날어때

내 일정을 가장 편하게 넣고 보는 캘린더 앱입니다. 그룹 캘린더는 멤버들의 일정을 합쳐 보여 주고, 그룹에서 날짜를 잡으면 수락한 사람들의 캘린더에 약속이 들어갑니다. 일정·채팅·투표는 MLS로 종단간 암호화되어, 운영자를 포함해 멤버가 아닌 누구도 볼 수 없습니다.

## 문서

| 문서 | 다루는 것 |
| --- | --- |
| [개요](docs/kickoff.md) | 제품, 범위, 설계 원칙, 보안 모델, 마일스톤과 게이트 |
| [직군별 할 일](docs/plan.md) | M0부터 출시 후 운영까지 직군마다 할 일과 담당 |
| [공통 계약](docs/parts/protocol.md) | 모든 파트가 지키는 규칙 |
| 파트 문서 | [Rust 코어](docs/parts/rust-core.md) · [iOS](docs/parts/ios.md) · [안드로이드](docs/parts/android.md) · [API 서버](docs/parts/api-server.md) · [게이트웨이](docs/parts/gateway.md) · [인프라](docs/parts/infra.md) |

## 저장소 구조

[개요](docs/kickoff.md) 9장의 구조를 M0에서 파트마다 PR로 채워 갑니다.

```
core/               # Rust workspace shared by both apps
apps/ios/           # XcodeGen spec, Swift packages, app and test targets
apps/android/       # Gradle project
server/api/         # Spring Boot (Kotlin)
server/gateway/     # Elixir/Phoenix
infra/              # local compose, alpha compose, Terraform
docs/               # kickoff, plan, part documents, ADRs
scripts/            # toolchain setup and CI helpers
.github/workflows/  # ci.yml calls one workflow per part
```

## 시작하기

필요한 것은 GNU Make, Bash, curl, unzip이고, 서버 파트부터는 Docker도 필요합니다. 파트별 도구는 그 파트가 들어올 때 여기에 더합니다.

- **Rust 코어:** rustup. 툴체인 버전은 `core/rust-toolchain.toml`이 고정하고, 안드로이드 빌드에 쓰는 NDK와 cargo-ndk는 `make build-android`가 받아 둡니다. iOS 빌드(`make build-ios`)는 macOS와 Xcode에서만 됩니다.
- **API 서버:** JDK 21과 Docker. Gradle은 `server/api/gradlew`가 고정한 버전을 받아 쓰고, 테스트는 Testcontainers로 PostgreSQL과 Redis를 띄웁니다.
- **게이트웨이:** `.tool-versions`의 Erlang/OTP와 Elixir(`make setup`이 설치)와 Docker. `make test-gateway`는 `REDIS_URL`이 없으면 쓰고 버릴 Redis 컨테이너를 띄웁니다.
- **iOS:** macOS와 `apps/ios/.xcode-version`에 고정한 Xcode. 프로젝트 파일은 `make project-ios`가 XcodeGen(`scripts/tool.sh`가 고정 버전을 받음)으로 `apps/ios/project.yml`에서 만들고, 코어는 `make build-ios`로 먼저 빌드합니다. 이 개발 환경에는 macOS가 없어서 CI의 macOS 러너가 유일한 검증 수단입니다.
- **안드로이드:** JDK 17 이상. Gradle은 `apps/android/gradlew`가 고정한 버전을, Android SDK는 `scripts/tool.sh android-sdk`가 고정한 패키지를 받아 씁니다(`ANDROID_HOME`이 있으면 그 SDK에 더함). `make test-android`가 코어의 안드로이드 라이브러리(`make build-android`)를 먼저 만듭니다. 계측 테스트(`make connected-android`)는 에뮬레이터나 기기가 필요해서 CI가 돌립니다.

```sh
make setup   # install the toolchain; safe to rerun
make help    # list targets
make lint    # lint workflows and shell scripts
```

- `make setup`은 `scripts/dev-setup.sh`를 실행합니다. 우분투(x86_64)에서는 `.tool-versions`에 고정한 Erlang/OTP와 Elixir를 builds.hex.pm에서 받아 체크섬을 확인한 뒤 설치합니다. 다른 시스템에서는 mise나 asdf로 `.tool-versions`를 설치합니다.
- Claude Code 클라우드 세션은 시작할 때 SessionStart 훅(`.claude/hooks/session-start.sh`)이 `scripts/dev-setup.sh`를 실행하므로 따로 할 일이 없습니다.

## 로컬 실행

`make up`은 API와 게이트웨이 이미지를 지금 코드로 빌드한 뒤, `infra/docker-compose.yml`로 PostgreSQL·Redis·S3(SeaweedFS)·API·게이트웨이를 띄웁니다. 계정과 암호가 고정값이라 포트는 이 기기(127.0.0.1)에만 엽니다.

| 서비스 | 주소 |
| --- | --- |
| API | `http://127.0.0.1:8080` (상태: `/actuator/health`) |
| 게이트웨이 | `http://127.0.0.1:4000` (상태: `/healthz`, 기기 소켓: `/socket/websocket`) |
| PostgreSQL | `127.0.0.1:5432` (DB와 사용자 `geunal`, 암호 `geunal-local`) |
| Redis | `127.0.0.1:6379` |
| S3 | `http://127.0.0.1:8333` |

```sh
make up     # build the images and start the stack
make e2e    # headless end-to-end tests in core/geunal-e2e (starts the stack first)
make down   # stop the stack and drop its data
```

## 클라우드 구성

NCP 자원은 `infra/terraform`의 Terraform 코드로 만듭니다(모듈 `vpc`, `server`, `bucket`과 환경 루트 `envs/alpha`). M0에서는 `make lint-terraform`으로 형식과 유효성만 확인하고, 적용은 M1부터 합니다. 상태 파일 위치와 적용 절차는 [infra/terraform/README.md](infra/terraform/README.md)에 있습니다.

## CI와 머지 규칙

- `.github/workflows/ci.yml` 하나가 진입점입니다. 바뀐 경로로 파트를 고르고(`scripts/ci/changed-parts.sh`) 그 파트의 워크플로만 부릅니다. `main`에 푸시되면 모든 파트를 돌립니다.
- 필수 검사는 `ci-ok` 하나입니다. 경로 필터 때문에 돌지 않은 워크플로를 필수 검사로 걸면 대기 상태로 남아 머지가 막히기 때문입니다.
- PR은 파트 단위로 나눠 `main`으로 보내고, `ci-ok`가 녹색이어야 머지합니다.
- 커밋은 Conventional Commits 형식으로 씁니다.
- 공통 계약을 바꾸는 PR은 계약 문서와 테스트 벡터를 먼저 바꿉니다.
