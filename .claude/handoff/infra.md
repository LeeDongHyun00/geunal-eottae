# 인프라·조정 인수인계

갱신: 2026-11-17, 이전 세션 https://claude.ai/code/session_0132zdS6HdxaQLVf2TtDg67g

## 지금 상태

- **조정:** 사용자 결정(2026-11-17)으로 조정은 `session_0132zdS4MTLxXdVNDS9A2fcB`("현대 코드 평가 및 인프라 설계")가 맡습니다. 이 세션과 11-04에 연 직군 세션 7개는 인수인계 뒤 멈춥니다(아래 "세션").
- **목표(사용자, 2026-11-04):** M2 그룹 캘린더를 먼저 마칩니다. 채팅(M3)은 보류이고, 알파 서버는 키우지 않고 Micro에 게이트웨이를 더합니다(#198).
- **알파 서버:** API는 11-17에 바깥에서 `UP`입니다. 게이트웨이는 아직 배포 전입니다(#197 머지, `rt.geunaleottae.com` A 레코드 있음, 응답 없음).
  - 배포는 운영자 맥에서 [infra/alpha](../../infra/alpha/README.md)의 명령으로 합니다. 변수는 `export`로 넘기고, `GEUNAL_API_HOST`와 `GEUNAL_RT_HOST`를 함께 줍니다.
  - 게이트웨이를 처음 띄우는 배포는 compose 네트워크를 다시 만들어 API가 1~2분 끊깁니다.
  - #215가 바깥 경보에 게이트웨이 검사를 더해서, 배포 전까지는 `alpha-alert` 이슈가 열릴 수 있습니다.
- **푸시 키:** 사용자가 Secret Manager에 `GEUNAL_PUSH_APNS_KEY_ID`, `GEUNAL_PUSH_APNS_PRIVATE_KEY`, `GEUNAL_PUSH_FCM_SERVICE_ACCOUNT`를 넣었다고 했습니다(11-04). 서버가 쓰는 것은 백엔드의 발송 PR 뒤입니다. APNs는 production만 씁니다.
- **11-17 머지:** #194, #199(코어 채팅을 기능 플래그 뒤로), #215(덤프·게이트웨이 감시, 복구 연습), #218, #219(Caddy 2.11.9, HTTP/2 복구).

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #198 M2는 Micro, 채팅 보류 기록 | Codex 지적 해결, `user-approved` 대기(`6957973`) | 사용자 확인 뒤 라벨·머지 |
| #187 초대 계약(코어) | `user-approved` 대기(`56cb876`) | 머지 뒤 #191·#192 |
| #191 초대 서버(백엔드) | 머리 `b07e969`, 라벨이 이 머리에 없음 | main 합치기, QA 지적 수정, 다시 확인 |
| #192, #196, #200(코어), #202(안드로이드) | 리뷰·머지 대기 | 각 직군 인수인계 참고 |
| #212~#217 | 새 조정 세션이 진행 | 그 세션 |
| #220 QA 문서 | `user-approved` 대기 | #216·#217 뒤 |
| Dependabot #168, #204~#211 | 쌓여 있음 | 조정 세션 담당 |
| `claude/api-push`, `claude/core-group-meta-2` | PR 없음 | 백엔드 푸시 발송, 코어 그룹 메타 |

## 다음 할 일

1. 게이트웨이 배포(사용자 맥). 배포 출력의 `fetch-secrets: wrote …`에 푸시 키 이름 셋이 있는지 봅니다.
2. 직군 세션 인수인계 PR을 머지한 뒤, 새 직군 세션을 열지 정합니다([CLAUDE.md](../../CLAUDE.md) "세션 교체").
3. 이전 세션 보관: 아래 표 8개와 11-04 이전 8개(이 파일의 이전 판, `f29aa99`). 사용자 확인 뒤.
4. QA 요청: JWT 키 교체 절차에 "이전 비밀 키를 리프레시 유예보다 오래 남김", "침해 때 모든 세션 끝내기"를 더합니다(#216 뒤).
5. 초대 도메인 `geunaleottae.com`(Cloudflare Pages): AASA, assetlinks, `/i/<code>` 페이지(디자인 M2 21번), 공개 사이트. 페이지는 링크의 `#` 값을 `textContent`로만 그립니다(계약 15장).

## 기다리는 결정

| 결정 | 누가 | 어디에 |
| --- | --- | --- |
| 디스크 증설: 10GB 중 85%, 게이트웨이 이미지로 약 88%. 블록 스토리지 10GB(월 약 1,200원) 권함 | 사용자 | 이 파일 |
| 계약 7장: WebSocket 토큰을 쿼리에서 헤더로(실시간·코어 동의) | 사용자 | 실시간·코어 인수인계 |
| Firebase 앱 변수 `GEUNAL_FIREBASE_*` 4개를 넣었는지 | 사용자 | #202 본문 |
| release-ios run 3 승인, 안드로이드 첫 번들 업로드 | 사용자 | 11-04 판 |

## 알아 둘 것

- **보류:** 세션들이 같은 GitHub 계정을 써서 보류 이슈를 누가 닫았는지 기록으로 구별되지 않습니다. 보류를 연 직후에도 몇 세션이 확인 없이 푸시했습니다(#201, #203). 바깥 작업 바로 앞에서 다시 확인하게 합니다.
- **비밀값 파일:** `fetch-secrets.py`는 값의 줄바꿈을 두 글자 `\n`으로 바꿔 씁니다. 여러 줄 JSON(FCM 서비스 계정)은 API가 되돌려 읽어야 합니다(백엔드에 전달함). 작은따옴표가 든 값은 배포를 멈춥니다.
- **SSH 시간 초과:** ACG `geunal-alpha-app-acg`의 22번 허용 IP가 운영자 IP와 다를 때입니다. 콘솔에서 고치고 tfvars의 `admin_cidr`도 맞춥니다. 새 환경은 첫 접속에서 호스트 키를 받아야 배포 스크립트가 돕니다.
- **비공개 항목:** 보안 점검의 남은 항목은 QA 세션과 새 조정 세션에 직접 전했고, 저장소에는 쓰지 않습니다.

## 세션(11-04에 엶, 인수인계 뒤 보관 대상)

| 직군 | 세션 |
| --- | --- |
| 조정·인프라 | `session_0132zdS6HdxaQLVf2TtDg67g` |
| 코어 | `session_0132zdS7mwpftUmPh7999RdK` |
| iOS | `session_0132zdS6HBvBLe8C9VXg25Z8` |
| 안드로이드 | `session_0132zdS8SDxNVFKTywyX4wkG` |
| 백엔드 | `session_0132zdS6HneNc5uxU6uDCxQM` |
| 실시간 | `session_0132zdS72drDTVz5N8nhS98C` |
| 디자인 | `session_0132zdS6ZvVTjdo7UHj8sPRp` |
| QA | `session_0132zdS8UWcdDZETX36CM8KY` |
