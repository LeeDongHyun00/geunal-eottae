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
- 클라우드 세션의 바깥 연결은 프록시를 거칩니다.
  - Docker Hub는 세션들이 함께 쓰는 주소에 익명 내려받기 한도(429)를 걸어서, `ensure-docker.sh`가 데몬을 Google 미러(`mirror.gcr.io`)를 거치게 띄웁니다.
  - Maven Central은 같은 파일에도 404·429를 섞어 돌려줍니다. 그래서 `make setup`이 `~/.gradle/init.d`에 초기화 스크립트를 넣어 Gradle이 Google의 Maven Central 미러를 쓰게 합니다. 빌드 파일에는 `mavenCentral()`을 그대로 씁니다.
  - 컨테이너 안의 프로세스는 프록시에 닿지 못하고 프록시 인증서도 믿지 않습니다. 이미지 빌드처럼 컨테이너 안에서 무언가를 내려받는 작업은 이 점을 따로 처리해야 합니다.
  - 세션에 붙지 않은 GitHub 저장소는 웹 페이지와 API가 막혀 있습니다. 릴리스 파일 직접 다운로드와 `git ls-remote`는 됩니다.

## 명령

`make help`가 전체 목록을 보여 줍니다. CI도 같은 대상을 부르므로, 로컬에서 통과한 명령이 CI에서도 그대로 돕니다.

| 대상 | 하는 일 |
| --- | --- |
| `make setup` | 도구 설치(다시 실행해도 안전) |
| `make lint` | 모든 린트 |
| `make lint-repo` | 워크플로(actionlint)와 셸 스크립트(shellcheck) 검사 |

## 규칙

- **커밋:** Conventional Commits, 영어, 명령형. 범위(scope)는 파트 이름입니다(`feat(core): ...`, `fix(api): ...`, `ci: ...`).
- **PR:** 파트 단위로 나눠 `main`으로 보냅니다. `ci-ok`가 녹색이어야 머지합니다.
- **계약:** 공통 계약을 바꾸는 PR은 `docs/parts/protocol.md`와 `core/vectors/`를 먼저 바꿉니다.
- **버전 고정:** 툴체인(`.tool-versions`와 각 파트의 툴체인 파일), 잠금 파일, 컨테이너 이미지 태그를 고정하고, GitHub Actions는 커밋 SHA로 고정합니다. Rust 코어는 [개요](docs/kickoff.md) 부록 B에서 검증한 버전을 쓰고, 버전 올리기는 기능 PR과 섞지 않고 따로 합니다.
- **주석과 문서:** 주석은 영어로, 무엇을 하는지보다 왜 그런지를 적습니다. 문서는 한국어로 씁니다.
- **비밀값과 로그:** 비밀값은 저장소에 넣지 않습니다. 로그에 요청·응답 본문, 암호문, 토큰, 개인정보를 남기지 않습니다([개요](docs/kickoff.md) 6장, [인프라](docs/parts/infra.md) 8장).

## CI에 파트 붙이기

1. `.github/workflows/<part>.yml`을 `on: workflow_call`로 만들고, 단계에서는 `make` 대상을 부릅니다.
2. `ci.yml`에 작업을 더합니다: `needs: changes`, `if: needs.changes.outputs.<part> == 'true'`, `uses: ./.github/workflows/<part>.yml`.
3. 그 작업을 `ci-ok`의 `needs`에 넣습니다. 빠뜨리면 그 파트가 실패해도 머지가 막히지 않습니다.
4. 경로 규칙은 `scripts/ci/changed-parts.sh`에 있습니다. core, api, gateway, e2e, ios, android 규칙이 이미 있습니다.
5. `.github/dependabot.yml`에 그 파트의 생태계(cargo, gradle, mix, docker)를 더합니다.
