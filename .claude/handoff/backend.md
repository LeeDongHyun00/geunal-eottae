# 백엔드 인수인계

갱신: 2026-11-04, 이전 세션 https://claude.ai/code/session_0132zdS5vEr5VQDpmakFFJR9

## 지금 상태

- M1 백엔드는 모두 머지됐습니다(#62, #69, #102, #106, #111, #123, #131, #139).
- 그 뒤에 머지된 PR:
  - #149: 진단 로그
  - #155: 초대된 계정만 그룹에 추가
  - #157: 본문 크기 한도
  - #160: 분당 한도
  - #163: 암호문 그대로 저장·로그 위생 시험
  - #166: Welcome 30일 정리
- main에서 되는 것: protocol·api-server.md의 M0·M1 엔드포인트, 매일 정리, 접속 기록 출고, 공휴일 동기화. `make lint-api test-api`는 약 160개 시험이 녹색입니다.
- main에서 안 되는 것: 초대 코드가 없습니다. 그래서 `pending`을 만드는 길은 스텁 `POST /auth/stub/invite`(`GEUNAL_AUTH_STUB`일 때만)뿐이고, 알파에서는 멤버 추가가 모두 403입니다.

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| 없음 | 로컬에만 있는 커밋 없음 | 머지된 `claude/api-*` 브랜치는 지워도 됩니다 |

## 알파 서버 운영 메모

- 배포는 운영자 맥에서 `GEUNAL_ALPHA_SSH=root@<주소> make deploy-alpha`로 합니다([infra/alpha](../../infra/alpha/README.md)). 클라우드 세션에서는 배포할 수 없습니다.
- 로그 보기: `ssh <주소> 'cd /opt/geunal && docker compose logs --since 1h --no-log-prefix api'`. 값을 찍는 줄은 없으니 grep으로 거릅니다.
  - `POST /auth/apple 201 183ms`: 요청 한 줄입니다. 메서드, 경로 패턴, 상태, 처리 시간만 담고 보안 필터에서 거절된 요청도 남습니다.
  - `Apple refused a code exchange for ios: 400 invalid_client`: 서버의 .p8, Key ID, 팀 ID 중 하나가 틀렸습니다. `invalid_grant`이면 코드 문제(재사용·만료·다른 client)입니다.
  - `Apple's code names another Apple account than the one linked`: 연결된 것과 다른 Apple 계정입니다.
  - `deletion refused: <검사> did not verify`: 계정 삭제가 403 `reauth_required`를 받은 이유입니다.
- Apple 키 문제(삭제 실패)는 해결됐습니다.

## 다음 할 일(M2, [plan.md](../../docs/plan.md) 4장)

1. **초대 코드:** 만들기·참여(`pending`), IP당 시간당 10회([api-server.md](../../docs/parts/api-server.md) 9장, V1의 `group_invites`). `pending`이 생기면 기존 Add 검사(#155)가 그대로 씁니다. 스텁 초대는 e2e가 쓰니 남깁니다.
2. **멤버 상태와 방장 승계:** 탈퇴·강퇴·삭제 때의 승계는 있습니다(`GroupRepository.succeedOwner`). 초대·`pending` 만료와 `membership` 신호를 붙입니다.
3. **그룹 메타 공용 자리(버전 비교):** `PUT /groups/{id}/state/group_meta`의 `base_version` 충돌 규칙을 api-server.md 대로 맞춥니다.
4. **커버·프로필 사진 URL:** 미디어 범위별 업로드·다운로드 URL. 큰 본문은 S3로 가니 `BodyLimit.CAPS`는 그대로 둡니다.
5. **푸시와 종류별 알림 설정.** 사용자가 정한 방향은 "기존 환경을 최대한 활용"입니다.
   - iOS: 같은 Apple Developer 계정의 APNs 키(.p8)를 씁니다.
   - 안드로이드: 기존 GCP 프로젝트 `geunal-eottae`(플레이 서비스 계정 있음)에 Firebase를 붙여 FCM HTTP v1을 씁니다.
   - 서버 자격 증명: NCP Secret Manager에 두고 `infra/alpha/fetch-secrets.py`로 받습니다. 발급과 등록은 사용자·인프라 몫입니다.
6. **퍼널:** V1의 `funnel_events`. 개인정보 없이 이벤트와 시각만 둡니다(kickoff 6장).

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| 그룹 만들기에 시간당 한도를 둘지, 몇으로 할지 | 사용자(기획) | #160 "검토할 결정", api-server.md 9장 |

## 알아 둘 것

- **시험:**
  - Docker 데몬이 사라지면 `./gradlew test`가 실패합니다. `make test-api`가 `ensure-docker.sh`를 부릅니다.
  - Redis는 시험 컨텍스트끼리 함께 씁니다. 그래서 `ApiTest`가 로그인·분당 한도를 크게 두고, `SignInLimitTests`·`RateLimitTests`만 진짜 값을 씁니다.
  - 린트 실패는 `cd server/api && ./gradlew ktlintFormat`으로 먼저 고칩니다.
- **로그:**
  - `application.yml`이 Tomcat·Spring Web·JDBC·pgjdbc·Lettuce 등의 로거를 INFO로 고정합니다. 낮추면 본문과 토큰이 찍히고, `LogHygieneTests`가 실패합니다.
  - 새 로그 줄에는 값을 넣지 않습니다(경로는 패턴만).
- **본문 한도:** 큰 본문을 받는 엔드포인트를 더하면 `common/BodyLimit.kt`의 `CAPS`에도 더합니다. 필터는 모든 필터보다 앞에서 돕니다.
- **분당 한도:** `HourlyLimit.check(name, subject, limit, window)`와 `geunal.rate-limits.*`.
- **엔트리 크기:** 65,536은 패딩을 포함한 MLS 메시지의 크기로 코어와 합의했습니다. 코어가 계약 4장 PR을 올리고, 서버는 바꿀 것이 없습니다.
- **코어 가짜 서버:** `pending`을 따지지 않습니다. 초대를 만들 때 코어에 같은 규칙을 넣어 달라고 합니다(#155 QA 리뷰).
- **공개 저장소:** PR·커밋·주석에 공격 방법이나 무엇이 뚫렸는지를 쓰지 않습니다. 보안 세부는 조정 세션에 send_message로 보냅니다.
- **리뷰와 머지:** Codex 지적은 재현한 뒤 고치고, 스레드에 답하고 해결합니다. 검사가 이미 녹색이면 직접 squash로 머지합니다.
