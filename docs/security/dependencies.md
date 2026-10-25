# 의존성 취약점 검사

무엇을 어떤 도구로 언제 검사하는지, 새 권고(advisory)가 나왔을 때 어떻게 대응하는지 정합니다. [위협 모델](threat-model.md) 2장의 경계 4(개발·배포 경로)를 다룹니다.

## 1. 무엇을 검사하나

| 대상 | 도구 | 설정 | 명령 | 실패 기준 |
| --- | --- | --- | --- | --- |
| Rust 코어의 크레이트(`core/Cargo.lock`), 안드로이드·iOS 타깃 포함 | cargo-deny `advisories` (RustSec DB) | `core/deny.toml`: yanked 금지, `ignore`에 이유 적힌 예외 2개 | `make audit-core`(advisories만), `make lint-core`(라이선스·중복·출처까지) | 권고가 있고 `ignore`에 없으면 실패 |
| API 이미지(`geunal/api:local`): OS 패키지, JDK, boot jar 안의 라이브러리 | Trivy `image` | 기본. 예외 파일 없음 | `make scan-api` | HIGH·CRITICAL 중 수정판이 있는 것 |
| 게이트웨이 이미지(`geunal/gateway:local`): OS 패키지, ERTS | Trivy `image` | 같음 | `make scan-gateway` | 같음 |
| 게이트웨이 Hex 패키지(`server/gateway/mix.lock`) | Trivy `fs --scanners vuln` | 릴리스 안에서는 Hex 패키지가 안 보여서 잠금 파일을 따로 검사 | `make scan-gateway` | 같음 |
| GitHub Actions | Dependabot(`github-actions`) | 커밋 SHA 고정, 주 1회 묶음 PR | 없음 | 없음(PR로만 옴) |
| 버전 올리기 | Dependabot(cargo, gradle, mix, docker, docker-compose) | `.github/dependabot.yml`. 코어는 patch만, 나머지는 major 제외 | 없음 | PR의 `ci-ok` |

도구 자체는 `scripts/tool.sh`가 GitHub 릴리스에서 받아 SHA-256을 확인합니다(cargo-deny 0.20.2, Trivy 0.76.1). 버전을 올릴 때 해시도 함께 바꿉니다.

**아직 검사하지 않는 것:**
- iOS Swift 패키지와 안드로이드 Gradle 의존성. 파트가 들어올 때 `ios.yml`·`android.yml`에 더합니다(Swift는 공개 DB가 없어 Dependabot 버전 올리기로 대신, 안드로이드는 Trivy `fs`로 `gradle.lockfile`).
- 로컬 compose의 PostgreSQL·Redis·SeaweedFS 이미지. 로컬 전용이고 Dependabot이 버전을 올립니다. 알파 서버의 compose(M1)는 운영 구성이므로 그때 `audit.yml`에 더합니다.
- Trivy의 `--ignore-unfixed`가 숨기는, 수정판 없는 HIGH·CRITICAL. [QA](../qa.md) 5.3 제안 5.

## 2. 언제 검사하나

| 때 | 어디 | 무엇 |
| --- | --- | --- |
| PR마다 | `ci.yml` → `core.yml`·`api.yml`·`gateway.yml` | 바뀐 파트의 게이트. 빨간 PR은 머지되지 않음 |
| `main` 푸시마다 | `ci.yml`(모든 파트) | 같은 게이트 전부 |
| **주 1회(월요일 06:17 KST)와 수동 실행** | `.github/workflows/audit.yml` | `make audit` = `audit-core` + `scan-api` + `scan-gateway`를 `main`에서 |
| 주 1회 | Dependabot | 버전 올리기 PR |

주기 검사가 필요한 이유: PR 검사는 그 파트가 바뀔 때만 돕니다. 어떤 파트도 건드리지 않는 주에 그 파트의 의존성에 권고가 나오면, 다음 PR이 올 때까지 아무도 모릅니다. 권고 데이터베이스(RustSec, Trivy DB)는 매번 새로 받으므로, 같은 `main`을 다시 검사해도 결과가 바뀔 수 있습니다.

**결과가 가는 곳:** `audit.yml`의 `report` 작업이 `scripts/ci/audit-issue.sh`로 이슈를 다룹니다.
- 하나라도 실패하면 `security` 라벨의 이슈 "주간 의존성 취약점 검사 실패"를 열고, 이미 열려 있으면 실행 링크를 댓글로 더합니다. 이슈는 하나만 유지됩니다.
- 모두 통과하면 열린 이슈를 닫습니다.
- 예약 워크플로는 저장소에 60일 활동이 없으면 GitHub이 끄므로, 운영 중단기가 생기면 사용자가 Actions에서 다시 켭니다.

## 3. 권고가 나왔을 때

이슈가 열리면 그 주 안에 아래 순서로 처리합니다. 처리하는 PR은 `build(<part>): ...` 또는 `fix(<part>): ...`이고, 기능 PR과 섞지 않습니다([CLAUDE.md](../../CLAUDE.md) 버전 고정).

1. **읽기.** 권고 ID(RUSTSEC-, CVE-, GHSA-)와 취약한 함수·조건을 확인합니다. 실행 로그는 이슈에 링크된 Actions 실행에 있습니다.
2. **우리가 그 경로를 쓰는지 판단.** [QA](../qa.md) 4장 등급으로 적습니다.
   - 쓴다(또는 모른다) → **S1**. 수정판으로 올리는 PR을 바로 만듭니다. Dependabot PR이 이미 있으면 그것을 머지합니다. ignore 규칙 때문에 PR이 없으면(코어의 minor 올리기 등) 손으로 올리고, 올리는 이유에 권고 ID를 적습니다.
   - 쓰지 않는다 → **S2**. 그래도 수정판이 있으면 다음 Dependabot 묶음에서 올립니다.
3. **수정판이 없을 때만 예외를 둡니다.** 예외는 "왜 우리에게 해당하지 않는지"와 "언제 다시 볼지"를 함께 적습니다.
   - cargo-deny: `core/deny.toml` `[advisories].ignore`에 `{ id, reason }`. `reason`에 영향받지 않는 이유와 다시 볼 조건(예: "mls-rs-crypto-rustcrypto가 다음 elliptic-curve로 옮기면 제거")을 적습니다. 지금 있는 두 항목이 그 형식입니다.
   - Trivy: 저장소 루트에 `.trivyignore`를 두고 한 줄에 ID 하나, 바로 윗줄 주석에 이유, `exp:` 날짜로 만료를 둡니다(`CVE-2026-0000 exp:2026-12-31`). 만료되면 검사가 다시 실패해 다시 보게 됩니다.
   - 예외를 더하는 PR은 보안 담당(이 직군)이 리뷰하고, 사용자가 머지합니다.
4. **이미지의 OS 패키지 취약점**은 보통 베이스 이미지를 다시 받으면 풀립니다. Dockerfile의 태그가 digest가 아니라면 CI가 다시 빌드하는 것으로 충분하고, 그래도 남으면 베이스 이미지 태그를 올립니다.
5. **검사가 다시 통과하면 이슈가 자동으로 닫힙니다.** 수동 실행(`workflow_dispatch`)으로 바로 확인할 수 있습니다.
6. **공격자가 실제로 쓸 수 있는 취약점**(인증 우회, 원격 코드 실행, 암호 라이브러리의 키 노출)은 S0로 올리고 [QA](../qa.md) 5.2 규칙 5(다른 머지 중단)를 적용합니다. 알파 이후에는 배포까지 이어집니다.

## 4. 운영 중(M5)

- 이슈가 열린 채 2주를 넘기지 않습니다. 넘기면 사용자에게 보고합니다.
- 분기마다 `core/deny.toml`과 `.trivyignore`의 예외를 다시 봅니다. 다시 볼 조건이 이미 충족된 것은 지웁니다.
- 툴체인(`.tool-versions`, `rust-toolchain.toml`, JDK)과 베이스 이미지는 보안 지원 기간 안에 둡니다. Dependabot이 major를 올리지 않는 것은 사람이 따로 결정합니다.
- 서비스를 끝내면([직군별 할 일](../plan.md) M5) 마지막 앱 업데이트 뒤에는 서버 의존성 검사를 멈추고 워크플로를 비활성화합니다.
