# QA·보안 인수인계

갱신: 2026-11-03, 이전 세션 https://claude.ai/code/session_01GRmPm3J6S7gJxWpYESbzX6

## 지금 상태

마지막으로 머지된 내 PR은 #68(A5 `account_deleted`, 점검표의 연락처 줄)입니다. 기준 문서는 모두 main에 있습니다: [QA](../../docs/qa.md)(결함 등급, 머지 규칙), [M1 시험](../../docs/qa/m1.md), [위협 모델](../../docs/security/threat-model.md), [앱 PR 점검표](../../docs/security/app-review.md), [의존성 검사](../../docs/security/dependencies.md).

M1 완료 조건 대조(main 9f98efe):

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| 계획 QA: 실기기 시나리오 | 사용자 몫(알파 서버, 계정, TestFlight·Play 뒤) | m1.md 3장 |
| 계획 QA: 새 기기 복원 | 코어 자동판은 main에 있음. 종단 E1·E2는 열린 PR 뒤 | `a_second_phone_brings_the_backup_over_with_the_code_and_replaces_the_first`, `a_guest_comes_back_on_a_new_phone_with_the_code_alone`. API #62·#69, 인프라 #64 |
| 계획 QA: 기기 캘린더 가져오기(D1·D2) | 안 함(앱의 미러 PR 전) | 코어 `mirrors_device_calendars_read_only_and_replaces_them_on_the_next_mirror`만 있음 |
| 계획 QA: 크래시 확인 | 사용자 몫(배포 뒤). 크래시 수 보내기는 안 함(iOS MetricKit, 안드로이드 `ApplicationExitInfo`) | m1.md 4장, 계약 14장 |
| 계획 보안: 백업 암호 구조 | main에 있음(검토 끝) | 벡터 독립 재계산(#26), #47·#56 검토 |
| 계획 보안: 키체인·Keystore | main에 있음 | 점검표 1장, #63(읽기 실패를 "없음"으로 보지 않음, 읽을 때 키를 만들지 않음) |
| 계획 보안: 로그 개인정보 | PR마다 점검 중 | 점검표 5·6장, #56(서명 URL을 오류 문구에서 뺌) |
| 지표 1–3(활성, 추천, 크래시 없음) | 셈 방법은 main. 앱 기록·코어 보내기는 main(#56). 서버 받기는 안 함(`/me/alpha-stats`는 openapi와 테이블만) | m1.md 5장 |
| 지표 4(기기 캘린더 90%) | 사용자 몫(3.3 기록). 앱 미러는 안 함 | m1.md 3.3 |
| 지표 5(새 기기 복원 100%) | 사용자 몫(3.2). 하나라도 실패하면 S0 | m1.md 3.2 |
| 결함 등급 | main에 있음. 이 세션이 아는 열린 S0·S1은 없음 | qa.md 4장 |
| 시험 이름 대조 | 안 함. 표 이름 그대로인 것은 A5 둘째 줄, D10, D12뿐. 코어는 같은 동작을 다른 이름으로 가짐(위 근거 열의 예) | m1.md 2장 |

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #75 iOS 나 탭 | QA 리뷰 남김. D7·D11 시험 요청 | 새 iOS 세션이 처리. 요청이 오면 다시 봄 |
| #73 안드로이드 편집·교대 | QA 리뷰 끝(막을 것 없음) | 없음 |
| #62·#69 API 로그인·복구 자료·삭제 | 보안 검토 안 함 | 요청이 오면 위협 모델 5장의 API 시험과 대조 |
| #64 인프라 알파 배포 | 안 봄 | 요청이 오면 비밀값, TLS, 로그 점검 |
| `claude/laughing-bohr-iyq4lu` | 이 세션의 브랜치 | 인수인계 PR 머지 뒤 `qa-tools/`(벡터 재계산, 문서 검사 스크립트)를 PR 없이 둠 |

## 다음 할 일

1. 게이트 전 시험 이름 대조: m1.md 2장 표에 "실제 시험" 열을 더하거나 파트에 이름을 맞춰 달라고 함([m1.md](../../docs/qa/m1.md) 2장).
2. DELETE /me와 "새 키로 시작"에 기기 키 서명을 더하는 계약 변경의 보안 검토. 위협 모델 3.6 "세션 탈취" 줄도 함께 고침.
3. 앱 PR 리뷰(요청이 오면):
   - iOS 6번: 로그아웃·삭제 때 알림 지우기, 위젯 스냅샷 보호.
   - iOS 7번: App Group 이동. `FreshInstall`은 두 자리 모두 DB가 없을 때만 키체인을 비움.
   - 안드로이드 4번: 연락처 기준(점검표 2장).
   - 안드로이드 5번: 13·22 기기 인증, D8, 클립보드 `EXTRA_IS_SENSITIVE`, 13번 `FLAG_SECURE`.
4. 앱이 우리 CA만 믿게 하는 계획 문서 검토(M2 전, 인프라 초안).

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| DELETE /me·새 키로 시작에 기기 키 서명 | 사용자 확정. 계약 변경을 기다림 | 조정 세션 전달(2026-11-03) |
| 앱 CA 고정 | 인프라 초안 → 보안 검토 → 사용자 | 조정 세션 전달(2026-11-03) |
| 게스트의 첫 복구 자료 업로드 창(세션 탈취 시 공격자 코드로 먼저 올림)을 위협 모델에 적을지 | 보안 | 이 파일. 서버가 첫 업로드에도 기기 키 서명을 요구하면 닫힘 |

## 알아 둘 것

- QA는 PR을 구독하지 않습니다. 담당 세션이 요청하면 리뷰만 남깁니다(CLAUDE.md "PR 구독").
- 보안 결함은 S0·S1입니다. 공개 저장소라 재현 방법은 공개 이슈에 쓰지 않습니다(qa.md 4장).
- 리뷰의 `commitID`는 API로 받은 전체 SHA를 씁니다. 짐작한 값은 "commitOID is not part of the pull request"로 실패합니다.
- 강제 푸시는 자동 분류기가 막습니다. 머지된 브랜치를 다시 쓸 때는 main을 합치고 빨리 감기로 푸시합니다.
- 카카오 SDK 토큰 구조(안드로이드 2.26.0, iOS 2.29.0)와 메모리 관리자 교체 근거는 #53과 점검표 4장에 있습니다. devrepo.kakao.com은 프록시에서 가끔 끊기니 다시 받으면 됩니다.
- 안드로이드 API 26–30의 `KeyStore.getKey`가 null이면 데몬 무응답일 수 있습니다(AOSP `KeyStore.contains`). 읽을 때 키를 만들면 비밀을 모두 잃습니다(#63).
- 코어 오류 문구에 URL이 실리지 않게 reqwest 오류는 `without_url()`로 바꿉니다. 객체 저장소 응답은 `checked()`를 거치지 않아 `account_deleted`로 계정을 지우지 않습니다(#56, #71).
