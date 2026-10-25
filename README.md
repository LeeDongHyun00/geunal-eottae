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
apps/ios/           # Xcode project
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

```sh
make setup   # install the toolchain; safe to rerun
make help    # list targets
make lint    # lint workflows and shell scripts
```

- `make setup`은 `scripts/dev-setup.sh`를 실행합니다. 우분투(x86_64)에서는 `.tool-versions`에 고정한 Erlang/OTP와 Elixir를 builds.hex.pm에서 받아 체크섬을 확인한 뒤 설치합니다. 다른 시스템에서는 mise나 asdf로 `.tool-versions`를 설치합니다.
- Claude Code 클라우드 세션은 시작할 때 SessionStart 훅(`.claude/hooks/session-start.sh`)이 `scripts/dev-setup.sh`를 실행하므로 따로 할 일이 없습니다.

## CI와 머지 규칙

- `.github/workflows/ci.yml` 하나가 진입점입니다. 바뀐 경로로 파트를 고르고(`scripts/ci/changed-parts.sh`) 그 파트의 워크플로만 부릅니다. `main`에 푸시되면 모든 파트를 돌립니다.
- 필수 검사는 `ci-ok` 하나입니다. 경로 필터 때문에 돌지 않은 워크플로를 필수 검사로 걸면 대기 상태로 남아 머지가 막히기 때문입니다.
- PR은 파트 단위로 나눠 `main`으로 보내고, `ci-ok`가 녹색이어야 머지합니다.
- 커밋은 Conventional Commits 형식으로 씁니다.
- 공통 계약을 바꾸는 PR은 계약 문서와 테스트 벡터를 먼저 바꿉니다.
