# 인프라·조정 인수인계

갱신: 2026-11-04, 이전 세션 https://claude.ai/code/session_0132zdS5vn6SS8q9nPbAMcQo

## 지금 상태

- **최종 목표(사용자, 2026-11-04):** 그룹(M2)과 채팅(M3) 완성. 닿으면 보류를 걸고 정리해 보고합니다.
  - TestFlight 빌드에서 여러 사람이 그룹을 만들고 초대 링크로 들어옵니다.
  - 그룹 캘린더, 날짜 잡기, 투표를 쓰고, 그룹 채팅(첨부 포함)을 실시간과 푸시로 주고받습니다.
  - 알파 서버에는 API와 실시간 게이트웨이가 함께 떠 있습니다.
  - 안드로이드는 사용자 환경 때문에 실기기 확인 없이 코드와 CI까지 합니다.
- **M1 개발은 끝났습니다.** 게이트 지표는 테스터 운영 뒤에 봅니다.
- **알파 서버(`api.geunaleottae.com`):** main `ac1d125`(API `175c90065a3c`)로 배포됐습니다.
  - Caddy는 HTTP/1.1만 받고, 요청 본문을 512KB로 막습니다. API에는 경로별 한도가 따로 있습니다.
  - 그룹·멤버·Welcome은 0건입니다. 매일 DB 덤프가 버킷에 올라갑니다.
- **배포:** 운영자 맥에서 `make deploy-alpha`로 합니다([infra/alpha](../../infra/alpha/README.md)).
  - ACG가 SSH를 운영자 IP에만 엽니다. IP가 바뀌면 SSH가 시간 초과로 멈추니 콘솔에서 고칩니다.
- **릴리스:**
  - release-ios run 3(main `52df814`)은 사용자 승인을 기다립니다. 위젯용 App Group과 App ID는 사용자가 등록했습니다.
  - 안드로이드는 `android-release-442214`를 만들었습니다. 첫 번들은 손으로 플레이에 올리고, 다음부터 `upload=true`입니다.
- **보안 점검(2026-11-03~04, 독립 세션):** 서버·코어 수정은 머지되고 배포됐습니다. 남은 항목은 QA가 비공개로 추적하고, 공개 문서에는 재현 방법을 쓰지 않습니다([qa.md](../../docs/qa.md) 4장).

## 열린 PR과 브랜치

| PR | 상태 | 남은 일 |
| --- | --- | --- |
| #175 코어 엔트리 크기 기준(계약 4장) | `user-approved` 필요 | 사용자가 확인하면 라벨 |
| #176 QA 문서(위협 모델 3.4, m1.md 2.6) | `user-approved` 필요 | 사용자가 확인하면 라벨 |
| #170 코어 그룹 번들 처리 | 자동 머지 | 없음 |

## 다음 할 일

1. 새 세션이 모두 시작했는지 봅니다. 실시간(게이트웨이) 세션은 이번에 처음 엽니다(M2: `state`·`membership` 즉시 전달, 알파 배포).
2. M2 인프라([plan](../../docs/plan.md) 4장, [infra.md](../../docs/parts/infra.md) 2장):
   - 알파 서버를 2 vCPU·4GB로 옮기고 Micro는 스테이징으로 둡니다. 사용자가 비용을 보고 정합니다.
   - WAL-G 시점 복구, 모니터링과 경보
   - 게이트웨이 배포(compose, Caddy)
3. **푸시 키:** 사용자 결정대로 기존 환경을 최대한 씁니다.
   - iOS는 같은 Apple Developer 계정의 APNs 키를 씁니다.
   - 안드로이드는 플레이 서비스 계정이 있는 GCP 프로젝트 `geunal-eottae`에 Firebase를 붙여 FCM HTTP v1을 씁니다.
   - 서버 자격 증명은 NCP Secret Manager에 둡니다.
4. **초대 링크 도메인:** Cloudflare Pages의 `geunaleottae.com`에 apple-app-site-association과 assetlinks를 둡니다. 공개 사이트(처리방침, 약관, 삭제 안내, 문의)와 함께 만듭니다.
5. **Caddy 이미지:** 2026-12-04 전에 고친 Caddy 이미지로 올립니다. 그때 `infra/alpha/.trivyignore`의 Caddy 줄을 지우고 HTTP/2를 되돌립니다.
6. **서버 밖 덤프 보호와 API 저장소 키 범위:** 추천안은 운영자 맥이 덤프를 당겨오는 것입니다. 사용자 결정을 기다립니다.
7. 게이트웨이를 배포하기 전에 점검할 항목(비공개 목록, 새 세션 프롬프트로 넘김)을 처리하고, 자체 CA 계획 문서를 씁니다.

## 기다리는 결정

| 결정 | 누가 | 상태 |
| --- | --- | --- |
| 알파 서버 증설 시점(월 약 8.8만 원, 부가세 별도) | 사용자 | 보고함 |
| 활동 카테고리 20개 값 | 사용자(디자인 초안) | M2 시작 전 |
| 덤프 보호 방식, API 저장소 키 범위 | 사용자 | 추천안 있음 |
| #175, #176, release-ios run 3 | 사용자 | 승인 대기 |

## 알아 둘 것

- **`gate`:** 기본 브랜치의 `gate.sh`로 판정합니다(#153). `pull_request_target`은 쓰지 않습니다. 세션은 사용자 계정으로 움직여 라벨과 상태를 직접 쓸 수 있으니, gate는 실수를 막는 장치입니다.
- **비밀값:** 사용자에게 키나 비밀번호를 대화에 붙이게 하지 않습니다. 값은 콘솔, 터미널, 서버 파일에만 둡니다.
- **SSH:** 클라우드 세션은 HTTPS 프록시만 지나므로 서버에 SSH로 닿지 못합니다. 서버 작업은 사용자 맥에서 합니다.
- **Caddy 재시작:** Caddyfile이 바뀐 배포에서만 해시 라벨로 Caddy를 다시 만듭니다(#154).
- **Apple 키:** 로그인은 ID 토큰만 검증합니다. 계정 삭제와 안드로이드 Apple 로그인은 `.p8`로 코드를 교환하므로, 키가 틀리면 `invalid_client`가 납니다.

## 이전 세션

| 직군 | 세션 |
| --- | --- |
| 조정·인프라 | `session_0132zdS5vn6SS8q9nPbAMcQo` |
| 코어 | `session_0132zdS4uHGbzCbfbEzPa2Wd` |
| iOS | `session_0132zdS4Pe4XmKMtDFPdWSST` |
| 안드로이드 | `session_0132zdS54G1z5EBNcf5qoDge` |
| 백엔드 | `session_0132zdS5vEr5VQDpmakFFJR9` |
| 디자인 | `session_0132zdS6N6MJFEgcRXTttwBV` |
| QA | `session_0132zdS53MCuju8pdcCTjN6T` |
| 보안 점검(독립) | `session_0132zdS6QZvXWGQbW2f2MckC` |
