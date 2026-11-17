# 안드로이드 인수인계

갱신: 2026-11-17, 이전 세션 https://claude.ai/code/session_0132zdS8SDxNVFKTywyX4wkG

## 지금 상태

- M1 화면은 `main`에 있습니다. 마지막으로 머지된 내 PR은 #174(시트 스크린숏, 시험 전용)입니다.
- #217(조정 세션)이 로컬 알림(정확한 알람)과 기기 캘린더 미러를 더했습니다.
- M2는 초대 수신(#202)만 열려 있습니다. 그룹 화면과 FCM은 시작 전입니다. 조정 순서상 디자인 화면과 계약이 나온 뒤 3단계에서 합니다.
- 릴리스 `android-release-442214`가 있습니다. 다음 번들부터 release-android를 `upload=true`로 돌립니다.
- 사용자 환경 때문에 실기기 확인은 없습니다. 코드와 CI(계측 시험 포함)까지 합니다.

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #202 `claude/android-invite-links` a85f67b | QA 리뷰를 반영했고(30060df), Codex 지적은 없습니다. a85f67b(잠금 파일)의 CI는 아직 보지 못했습니다. `main`과 `MainActivity.kt`가 충돌합니다(#217의 알림 열기) | 다음 할 일 1 |

## 다음 할 일

1. #202를 마무리합니다.
   - `main`을 합칩니다. `onCreate`·`onNewIntent`에서 `openAlert`와 `takeInvite`를 둘 다 부르고, `onCreate`의 조건은 `main`의 것(`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` 제외)을 함께 씁니다.
   - `make lint-android test-android`가 통과하고 CI가 녹색이면 자동 머지를 켭니다.
2. 초대 해석: 코어 `parse_invite`(#192, 계약 15장 #187)가 머지되면 그룹 화면이 `PendingInvite.invite`(링크 전체 또는 referrer 코드)를 그대로 넘깁니다.
   - QA와의 약속(#202 리뷰): 보관된 초대로 바로 `join`하지 않습니다. 서버 미리보기(`GET /invites/{code}`)를 보여 준 뒤 사람이 눌러야 참여합니다. 링크의 이름과 색은 "링크에 적힌 값"으로만 다룹니다.
3. 초대 도메인: 인프라가 `geunaleottae.com`에 `assetlinks.json`과 `/i/` 페이지를 올린 뒤에만, 인프라가 `release-android.yml` env와 `infra/release/README.md`에 `GEUNAL_INVITE_HOST`를 더합니다.
   - 그 전에 넣으면 Android 11 이하에서 검증 실패가 `/auth/apple` App Link까지 깨뜨립니다(#202 본문).
4. FCM 등록(3단계에 시작)
   - google-services 플러그인 없이 `FirebaseOptions`로 초기화합니다. Gradle 속성 `geunalFirebaseProjectId`·`SenderId`·`AppId`·`ApiKey`를 `release.sh`가 저장소 변수 `GEUNAL_FIREBASE_PROJECT_ID`·`_SENDER_ID`·`_APP_ID`·`_API_KEY`에서 넘깁니다(값의 출처는 #202 본문 표).
   - 사용자는 Firebase에 앱을 등록했습니다(11-04). 변수를 넣었는지는 조정 세션에 확인합니다.
   - 코어 `set_push_token(token: Option<String>)`(#194)을 시작할 때와 `onNewToken`에서 부르고, 알림을 끄면 `None`을 넘깁니다. 로그인 전·오프라인이면 코어가 기다렸다 보냅니다.
   - 푸시 처리는 [android.md](../../docs/parts/android.md) 5장을 따릅니다.
5. M2 화면과 그룹 캘린더 60fps: 디자인 시안(#189 등)과 계약이 나온 뒤 시작합니다. 실시간 주소는 `wss://rt.geunaleottae.com`입니다(알파 배포 #197).
6. 디자인 대조 후속(#174, 앱 PR 하나로)
   - 23: 시트를 내용 높이로 하고, 두 버튼은 아래에 고정하고 본문만 스크롤합니다(가장 급함).
   - 22: 삭제 확인 시트의 높이와 딤이 실제 앱에서도 끊기는지 봅니다.
   - 24: 둘째 문단을 회색 안내 카드에 넣고, "하린님 계정으로 가져오거나"로 바꿉니다.
   - 15: #217이 더한 기기 캘린더 묶음을 시안과 맞추고, 줄 설명에 일정 수·교대 템플릿 수를 넣습니다.
7. 결정 45(주 보기 겹침): 코어가 `calendar_week`를 바꾼 뒤 구현합니다. 시안은 `boards/WeekOverlap.dc.html`입니다.
8. M1에 남은 것
   - Glance 위젯
   - WorkManager: 매일 자동 백업, 복구 코드 복사 1분 뒤 지우기(QA와 약속), 알림 64개 창·미러 갱신(#217)
   - 계정이 끝날 때 위젯 스냅샷 지우기
   - "기기 바꾸기" 안내, "서버가 아는 것", 테스트 통계 스위치

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| API 26–30에서 keystore 키를 잃었을 때 시작 오류에서 빠져나갈 길 | 안드로이드 제안, 사용자 | 코어 #63 |
| 결정 45 구현 시점 | 코어 `calendar_week` 변경 | 디자인 #161 |
| 재부팅 알림용 일정 제목을 평문으로 두는 것의 암호화 여부, `USE_EXACT_ALARM` Play 선언 | QA, 사용자 | #217 |

## 알아 둘 것

- 이 환경에는 KVM이 없습니다. 계측 시험은 CI의 `android / instrumented`에서만 돕니다. 에뮬레이터가 부팅되지 않으면(시험 0개) 실패한 작업만 한 번 다시 돌립니다.
- 잠금 파일은 손으로 고치지 않습니다. 의존성을 더하면 모든 모듈에 `--write-locks`를 돌립니다(#202에서 `feature-account`를 빠뜨려 CI가 실패함). Gradle은 `core/out/android`가 있어야 설정되므로 `make build-android`를 먼저 돌립니다.
- 스크린숏이 실패하면 `screenshots/<이름>-error.txt`가 아티팩트에 남습니다. 시트는 뒤에 화면이 있어야 찍힙니다(빈 액티비티 창은 크기 0 오류, #174).
- 시트는 Espresso `inRoot(isDialog())`로 그립니다. 액티비티 창을 Espresso로 찾으면 다이얼로그가 초점을 쥐고 있어 끝없이 기다립니다.
- Compose 시험: 키보드가 시트 버튼을 밀어내므로 `performScrollTo` 뒤에 누릅니다. `clearAndSetSemantics` 뒤의 `testTag`는 사라집니다.
- 새 커밋을 올리면 이전 커밋의 `ci-ok`가 취소되며 "실패" 알림이 옵니다. head SHA를 보고 지나갑니다.
- Codex는 PR마다 P2를 몇 건 남깁니다. 실제 경로를 따라가 보고, 맞으면 고쳐 시험을 더한 뒤 답하고 해결합니다.
- 앱 PR은 QA 세션, 스크린숏은 디자인 세션에 직접 요청합니다. 두 세션은 PR을 구독하지 않습니다.
- 화면 사이 공유 상태는 `AppViewModel`이 쥡니다. 계정이 끝나면(`accountEnded`) 덮개를 닫고 보관된 초대도 지웁니다.
- 사용량을 아끼려고 로컬의 무거운 빌드는 줄이고 CI에 맡깁니다(조정 안내).
