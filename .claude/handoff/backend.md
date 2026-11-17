# 백엔드 인수인계

갱신: 2026-11-17, 이전 세션 https://claude.ai/code/session_0132zdS6HneNc5uxU6uDCxQM

## 지금 상태

- 이번 세션에 머지: #193(상태 블롭 쓰기 한도. 사용자·그룹마다 분당 60개, 그중 `group_meta` 30개).
- main에서 되는 것: M1 전부와 #193. main의 `server/api`는 #193 뒤로 바뀌지 않았습니다(11-17 기준).
- main에서 안 되는 것: 초대 코드(#191), 푸시 발송과 알림 설정(`claude/api-push`), `pending` 만료. 알파에서는 여전히 스텁 초대 말고는 멤버를 더할 수 없습니다.
- 사용자 결정(11-04): 채팅(M3) 보류, 알파는 Micro 1GB 그대로. M3 항목(1:1·차단, 첨부 URL, 신고)은 시작하지 않고, API 메모리를 늘리는 변경은 피합니다.

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #191 `claude/api-invites`(b07e969) | Codex 완료(지적 없음), QA 리뷰 받음. CI는 벡터 시험 하나 빨강(#187의 `invites.json`이 main에 없음). 라벨은 예전 커밋에만 | 다음 할 일 1 |
| `claude/api-push`(088574f), PR 없음 | 로컬 `make lint-api test-api` 178개 녹색. main과는 충돌 없음 | 다음 할 일 2 |
| #187 코어 계약(15장), #192 코어 참여 흐름 | 코어 몫, 열림 | #191은 #187 뒤에 머지 |
| #216 `claude/api-hardening`, #213(문서) | 11-17 다른 세션이 연 백엔드 PR | 아래 "알아 둘 것"의 겹침 |

## 다음 할 일

1. **#191 마무리**
   - #187이 머지되면 main을 합칩니다. `RateLimitProperties.kt`, `RateLimitTests.kt`, `ApiTest.kt`가 #193과 충돌하니 양쪽 줄을 모두 살립니다.
   - 내보낸 사람은 내보낸 뒤 만든 코드로만 다시 들어오게 합니다. 스스로 나간 사람은 지금처럼 예전 코드로 돌아와도 됩니다. 누가 언제 내보냈는지와 `group_invites`의 만든 시각이 필요합니다. 시험 이름은 `a kicked member cannot rejoin with an old invite code`([qa/m2.md](../../docs/qa/m2.md) I6).
   - 새 머리 커밋을 조정 세션에 알려 `user-approved`를 다시 받고(`api-server.md`가 바뀜), `@codex review`를 남깁니다.
2. **푸시 발송 PR(`claude/api-push`)**
   - 내용: `alert`가 `none`이 아닌 메시지를 APNs(production만, 토큰 인증, `mutable-content`, `loc-key` `push.placeholder`)와 FCM HTTP v1(데이터만, high)로 보냅니다. 보낸 사람, `muted_until`, 끈 종류는 뺍니다. 죽은 토큰은 지웁니다. `PATCH /groups/{id}/members/me`와 `/me/groups`의 설정 필드도 들어 있습니다.
   - 비밀값: `GEUNAL_PUSH_APNS_KEY_ID`, `GEUNAL_PUSH_APNS_PRIVATE_KEY`, `GEUNAL_PUSH_FCM_SERVICE_ACCOUNT`. 사용자가 11-04에 Secret Manager `geunal-alpha`에 넣었습니다. 팀 ID는 `compose.yml`에 있습니다.
   - FCM 값 형식: `fetch-secrets.py`는 줄바꿈을 두 글자 `\n`으로 씁니다. 그래서 서비스 계정 JSON의 줄 사이와 `private_key` 안의 이스케이프가 섞여 옵니다. 서버는 `\n`을 줄바꿈으로 되돌리고 제어 문자를 허용해 읽습니다(`FcmSender.ServiceAccount.parse`, 시험으로 고정). 작은따옴표가 든 값은 배포가 멈춥니다.
   - PR "검토할 결정"에 적을 것: `c`는 base64 3,072자 이하일 때만 넣습니다(APNs 4KB 한도, 계약 8장 "3KB"를 이렇게 읽음, 코어 `decrypt_push`). iOS는 `push.placeholder` 문구를 정의해야 합니다. 접속 여부를 모르니 접속 중인 기기에도 보냅니다.
   - 푸시 페이로드 벡터(계약 12장, API가 만듦)가 아직 없습니다. 코어가 `decrypt_push`를 만들기 전에 `core/vectors`에 더합니다.
3. **퍼널:** 초대 전환은 서버가 이미 보는 사건(참여, Add 커밋, `availability` PUT)으로 셉니다(#187 댓글 6). 기기 마커는 서버가 못 보는 단계만.
4. **커버·프로필 사진 URL**([plan.md](../../docs/plan.md) 4장).
5. IPv6가 열리면 IP 한도를 /64로 묶어 셉니다. 알파는 IPv4만이라 나중에.

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| 그룹 만들기 시간당 10개, 코드 만들기 시간당 20개(초기값) | 사용자 | #191 "검토할 결정" 3 |
| "끝난 초대"와 "없는 코드"를 한 문구로 합칠지(둘 다 404 `invite_invalid`) | 디자인 | #187 |

## 알아 둘 것

- **#216과 겹침:** #216도 `V7`·`V8` 마이그레이션과 그룹 만들기 `group_limit`을 넣습니다. #191의 `V7__m2_invites.sql`은 먼저 머지되는 쪽 뒤 번호로 바꾸고, `group_limit` 검사는 한 곳만 남깁니다. `GroupController`·`GroupRepository`·`HourlyLimit`도 겹칩니다.
- **계약:** 계약은 코어가 `protocol.md`와 벡터로 정하고, 백엔드는 그 PR에 의견을 답니다(11-04 조정 결정). 서버 구현 PR은 계약 PR 뒤에 머지합니다.
- **보류 확인:** 푸시 전에 따로 한 번 봅니다. 같은 명령에 묶으면 보류가 열린 걸 놓칩니다(11-04에 한 번 있었음).
- **시험:**
  - Docker 데몬이 사라지면 `make test-api`가 `ensure-docker.sh`로 다시 띄웁니다. 린트 실패는 `./gradlew ktlintFormat`부터.
  - Redis는 시험 컨텍스트끼리 함께 씁니다. 한도는 `ApiTest`에서 크게, `RateLimitTests`·`SignInLimitTests`만 진짜 값입니다. IP 한도 시험은 같은 주소를 쓴 앞 시험의 몫까지 세므로 429가 나오는지만 봅니다.
  - `FakePush`가 APNs·FCM·OAuth를 한 서버로 흉내 냅니다. 발송은 커밋 뒤 가상 스레드라 기다려서 확인합니다.
- **로그:** `application.yml`이 Tomcat·Spring Web·JDBC·Lettuce 로거를 INFO로 고정합니다(#149, #163). 새 로그 줄에 값·토큰을 넣지 않습니다.
- **본문 한도:** 큰 본문을 받는 엔드포인트는 `common/BodyLimit.kt`의 `CAPS`에 더합니다.
- **알파 로그:** `ssh <주소> 'cd /opt/geunal && docker compose logs --since 1h --no-log-prefix api'`. 배포는 운영자 맥의 `make deploy-alpha`만.
- **공개 저장소:** 커밋·PR·주석에 공격 방법이나 빈틈 세부를 쓰지 않습니다. 보안 세부는 조정 세션에 send_message로.
- **리뷰와 머지:** Codex는 PR을 열 때만 리뷰합니다. 다시 받으려면 `@codex review`. 검사가 이미 녹색이면 직접 squash로 머지합니다.
