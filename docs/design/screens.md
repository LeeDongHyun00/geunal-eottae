# 화면 시안

시안 캔버스에서 내보낸 화면입니다(마지막 2026-10-25). 화면별 구성과 상태는 [M1 화면](m1.md)에 있습니다. 휴대폰 화면은 `@2x` PNG(780×1688, 390×844pt), 기초 보드는 1배 PNG입니다. 캔버스에서는 화면을 눌러 다음 화면으로 넘어가 볼 수 있고, 여기에는 주요 상태를 따로 내보냈습니다. 색·글꼴·간격 값은 [README](README.md)와 [`tokens.json`](tokens.json)을, 정확한 간격과 문구는 [보드 원본](boards/)을 봅니다.

## 디자인 기초

| 보드 | 내용 |
| --- | --- |
| [기초 원칙](screens/basis/01-principles.png) | 원칙 6가지, Apple 기초에서 가져온 것과 바꾼 것, 문구 톤, 아이콘 |
| [색](screens/basis/02-color.png) | 중립색, 그룹 색 12×5와 검증 결과, 브랜드 색, 사람 수를 단계로 바꾸는 규칙 |
| [글꼴·간격·컴포넌트](screens/basis/03-type.png) | 글자 단계, 간격, 모서리, 층, 버튼·칩·세그먼트·토스트, 내비게이션 사양 |
| [작동과정](screens/basis/04-flow.png) | 날짜 잡기가 캘린더 일정이 되기까지 7단계(투표·독재 갈래), 응답 변경·주도자 규칙, 탭별 화면 구조 |
| [앱 아이콘](screens/basis/05-app-icon.png) | A 띠 달력(선정), iOS 26의 네 가지 모습, 안드로이드 적응형·테마 아이콘 |

## M1 내 캘린더

### 처음 시작: 로그인 → 만 14세 확인 → 권한

| 시작 | 로그인 | 권한 |
| --- | --- | --- |
| <img src="screens/m1/01-welcome@2x.png" width="240" alt="시작"> | <img src="screens/m1/02-sign-in@2x.png" width="240" alt="로그인"> | <img src="screens/m1/03-permissions@2x.png" width="240" alt="권한"> |
| 그룹의 비어 있는 시간 띠를 미리 보여 줍니다. 초대 코드로 바로 들어갈 수도 있습니다. | 만 14세 확인을 체크해야 카카오·Apple 로그인이 켜집니다. 게스트는 "복구 코드로 돌아오기"로 다른 기기에서 돌아옵니다. | 기기 캘린더 가져오기(읽기만)와 알림. 알파 빌드는 테스트 통계(숫자 네 개) 동의 카드가 하나 더 있고([동의한 뒤](screens/m1/03-permissions--stats-on@2x.png)), 모두 나중에 바꿀 수 있습니다. |

### 빠른 입력: 날짜 탭 → 추천 칩 → 바로 저장

| 월 보기(홈) | 빠른 입력 시트 | 추천 칩을 누른 뒤 |
| --- | --- | --- |
| <img src="screens/m1/04-month@2x.png" width="240" alt="월 보기"> | <img src="screens/m1/05-quick-entry@2x.png" width="240" alt="빠른 입력 시트"> | <img src="screens/m1/06-added@2x.png" width="240" alt="추가됨"> |
| 일정 막대, 공휴일, 다음 할 일 막대와 탭 막대. | 날짜를 누르면 뜹니다. 추천 칩은 누르는 즉시 저장되고, 직접 입력은 끌어서 시간을 고릅니다. | "추가했어요 · 되돌리기"와 반복 제안이 다음 할 일 막대 자리에 뜹니다. |

### 월·주·목록과 일정 편집

| 주 보기 | 목록 보기 | 일정 편집 |
| --- | --- | --- |
| <img src="screens/m1/07-week@2x.png" width="240" alt="주 보기"> | <img src="screens/m1/08-list@2x.png" width="240" alt="목록 보기"> | <img src="screens/m1/09-editor@2x.png" width="240" alt="일정 편집"> |
| 시간 축 위의 일정 블록. | 다가오는 일정을 날짜별로. | 캘린더, 종일, 시간, 반복, 알림, 사진·파일 첨부. 그룹에 어떻게 보이는지 한 줄로 알립니다. |

### 교대 템플릿 · 사람·생일 · 나 · 위젯

| 교대 템플릿 찍기 | 사람·생일 | 나 |
| --- | --- | --- |
| <img src="screens/m1/10-shift@2x.png" width="240" alt="교대 템플릿"> | <img src="screens/m1/11-people@2x.png" width="240" alt="사람·생일"> | <img src="screens/m1/12-me@2x.png" width="240" alt="나"> |
| 템플릿을 고르고 날짜를 누르면 근무가 찍힙니다. 같은 날을 한 번 더 누르면 빠집니다. | 양력·음력 생일과 D-day, 그룹 공개 여부. 연락처에서 가져오기는 선택입니다. | 계정, 플러스, 캘린더 관리, 복구 코드, 암호화 백업, 기기 바꾸기. |

| 복구 코드 | 위젯 | 나 · 계정 묶음 |
| --- | --- | --- |
| <img src="screens/m1/13-recovery@2x.png" width="240" alt="복구 코드"> | <img src="screens/m1/14-widgets@2x.png" width="240" alt="위젯"> <img src="screens/m1/12-me--account@2x.png" width="240" alt="나 화면 아래쪽"> |
| 12단어 복구 코드(BIP39 한국어 목록). 종이에 적어 두라고 안내합니다. | 작은·중간 위젯, 밝은·어두운 모드. | 아래로 내리면 알림, 보안 안내, 알파 빌드의 테스트 통계 스위치, 계정 삭제. |

### 설정: 캘린더 관리 · 사람 추가 · 템플릿 편집

| 캘린더 관리 | 캘린더 편집 | 사람 추가 | 사람 추가 · 음력 |
| --- | --- | --- | --- |
| <img src="screens/m1/15-calendars@2x.png" width="200" alt="캘린더 관리"> | <img src="screens/m1/16-calendar-edit@2x.png" width="200" alt="캘린더 편집"> | <img src="screens/m1/17-person-edit@2x.png" width="200" alt="사람 추가"> | <img src="screens/m1/17-person-edit--lunar@2x.png" width="200" alt="사람 추가, 음력"> |
| 내 캘린더의 색·표시와 기기 캘린더 가져오기(읽기만, 캘린더별 선택). | 이름, 12색, 소프트 캘린더, 기본 알림. 삭제는 지워질 일정 수와 함께. | 이름, 양력·음력, 생일, 알림, 날짜 잡기 추천에 쓸지. | 음력을 고르면 올해의 양력 날짜를 보여 줍니다. |

| 교대 템플릿 편집 | 알림 설정 |
| --- | --- |
| <img src="screens/m1/18-template-edit@2x.png" width="240" alt="교대 템플릿 편집"> | <img src="screens/m1/19-notifications@2x.png" width="240" alt="알림 설정"> |
| 미리보기, 시작·끝, 캘린더, 알림. 자정을 넘는 근무 안내와 플러스의 근무 집계. | 기본 알림, 종일 일정, 다음 할 일 막대, 생일·연휴 제안. 그룹 알림은 M2. |

### 백업 · 기기 바꾸기 · 계정 삭제

| 암호화 백업 | 백업하는 중 | 기기 바꾸기 | 12단어를 넣은 뒤 |
| --- | --- | --- | --- |
| <img src="screens/m1/20-backup@2x.png" width="200" alt="암호화 백업"> | <img src="screens/m1/20-backup--running@2x.png" width="200" alt="백업하는 중"> | <img src="screens/m1/21-restore@2x.png" width="200" alt="기기 바꾸기"> | <img src="screens/m1/21-restore--ready@2x.png" width="200" alt="12단어 입력 완료"> |
| 마지막 백업, 자동 백업, 최근 3개, 들어가는 것과 안 들어가는 것. | 버튼이 꺼지고 상태 카드가 진행을 보여 줍니다. | 두 번째 기기에서 카카오·Apple로 로그인하면 뜹니다. 12단어 격자(BIP39 한국어 목록)와 붙여넣기. | 다 넣으면 "백업 가져오기"가 켜집니다. |

| 복구 코드로 돌아오기(게스트) | 단어가 목록에 없음 | 체크섬이 틀림 | 백업이 안 풀림 |
| --- | --- | --- | --- |
| <img src="screens/m1/21-restore--guest@2x.png" width="200" alt="게스트 복귀"> | <img src="screens/m1/21-restore--error-word@2x.png" width="200" alt="단어 없음 오류"> | <img src="screens/m1/21-restore--error-checksum@2x.png" width="200" alt="체크섬 오류"> | <img src="screens/m1/21-restore--error-locked@2x.png" width="200" alt="백업이 열리지 않음"> |
| 로그인 화면의 "복구 코드로 돌아오기"에서. 제목·설명·버튼만 다릅니다. | 그 칸이 빨갛게, 버튼은 "8번째 단어를 고쳐 주세요". | 기기에서 판정. 버튼 "코드를 다시 확인해 주세요". | 서버의 래핑된 키가 안 풀림. "다시 시도". |

| 게스트: 계정을 못 찾음 | 가져올 백업 없음 | 게스트: 백업 없음 | 게스트: 코드 없음 |
| --- | --- | --- | --- |
| <img src="screens/m1/21-restore--guest-error-not-found@2x.png" width="200" alt="게스트 계정 못 찾음"> | <img src="screens/m1/21-restore--no-backup@2x.png" width="200" alt="백업 없음"> | <img src="screens/m1/21-restore--guest-no-backup@2x.png" width="200" alt="게스트 백업 없음"> | <img src="screens/m1/21-restore--guest-no-code@2x.png" width="200" alt="게스트 코드 없음"> |
| 401 `recovery_invalid`. 연결된 계정도 같은 문구. | 오류가 아니라 "돌아왔어요"의 변형. | 계정은 찾았고 일정은 새로 시작. | 새 게스트 계정으로 시작을 빨간 글자로. |

| 돌아왔어요 | 복구 코드가 없을 때 | 계정 삭제 | 삭제 확인 |
| --- | --- | --- | --- |
| <img src="screens/m1/21-restore--done@2x.png" width="200" alt="복원 완료"> | <img src="screens/m1/21-restore--no-code@2x.png" width="200" alt="복구 코드 없음"> | <img src="screens/m1/22-delete-account@2x.png" width="200" alt="계정 삭제"> | <img src="screens/m1/22-delete-account--confirm@2x.png" width="200" alt="계정 삭제 확인"> |
| 가져온 수와 이전 기기 로그아웃, 그룹 재참여 안내. | 되찾을 수 없음을 설명하고 새로 시작을 빨간 글자로. | 서버에서 지워지는 것과 이 기기에 남는 것을 먼저 보여 줍니다. | 되돌릴 수 없는 유일한 동작이라 '삭제'를 적고, 카카오·Apple 계정은 그 로그인을 한 번 더 거칩니다. |

| 기기 로그아웃됨 | 복구 코드로 들어왔을 때 | 다른 계정의 일정이 남았을 때 | 계정 삭제 · 재인증 실패 |
| --- | --- | --- | --- |
| <img src="screens/m1/23-revoked@2x.png" width="200" alt="기기 로그아웃됨"> | <img src="screens/m1/23-revoked--recovery@2x.png" width="200" alt="복구 코드로 들어옴"> | <img src="screens/m1/24-keep-local@2x.png" width="200" alt="다른 계정의 일정"> | <img src="screens/m1/22-delete-account--reauth-failed@2x.png" width="200" alt="삭제 재인증 실패"> |
| 다른 기기에서 로그인한 뒤 이전 기기가 받는 시트. 남는 것과 지워지는 것을 나란히 보여 주고, 캘린더는 계속 쓰게 둡니다. | `replaced_via`가 복구 코드면 종이가 노출됐을 수 있다고 말하고 카카오·Apple 연결을 권합니다. | 로그인·복원 뒤 저장소가 다른 계정의 것이면 가져오기와 지우기 중 하나를 고릅니다. | 다른 카카오·Apple 계정으로 로그인하면 403 `reauth_required`. 시트에서 바로 다시 확인합니다. 게스트는 [추가 단계 없이](screens/m1/22-delete-account--guest@2x.png) 지웁니다. |

## M2 그룹·날짜 잡기

### 그룹 만들고 초대하기

| 그룹 목록 | 초대 보내기 | 초대 받은 사람 |
| --- | --- | --- |
| <img src="screens/m2/01-groups@2x.png" width="240" alt="그룹 목록"> | <img src="screens/m2/02-invite@2x.png" width="240" alt="초대 보내기"> | <img src="screens/m2/03-join@2x.png" width="240" alt="초대 받은 사람"> |
| 그룹마다 다음 약속이나 최근 소식, 응답할 일 배지. | 카카오톡 초대, 링크 복사, 초대 코드, 카카오톡 미리보기 카드. | 참여하면 그룹에 무엇이 보이는지, 공유 기간과 포함할 캘린더를 먼저 보여 줍니다. |

### 날짜 잡기: 날 탭 → 30분 테이블 → 신청

| 그룹 캘린더 | 날 테이블(30분) | 날짜 잡기 시트 | 약속 상세(주도자) |
| --- | --- | --- | --- |
| <img src="screens/m2/04-group-calendar@2x.png" width="200" alt="그룹 캘린더"> | <img src="screens/m2/05-day-table@2x.png" width="200" alt="날 테이블"> | <img src="screens/m2/06-date-sheet@2x.png" width="200" alt="날짜 잡기 시트"> | <img src="screens/m2/07-appointment@2x.png" width="200" alt="약속 상세"> |
| 날마다 0–24시 띠. 진할수록 그 시간에 비어 있는 사람이 많습니다. | 멤버별 줄과 합친 줄. 끌어서 시간을 고르고 그 날로 날짜를 잡습니다. | 결정 방식(투표·독재)과 한마디. | 응답 현황, 시간 투표, 활동 투표, 주도자 넘기기, 약속 취소. |

### 응답과 투표: 수락하면 바로 내 캘린더에

| 응답(멤버) | 시간 투표 | 정해짐 |
| --- | --- | --- |
| <img src="screens/m2/08-respond@2x.png" width="240" alt="응답"> | <img src="screens/m2/09-vote@2x.png" width="240" alt="시간 투표"> | <img src="screens/m2/10-decided@2x.png" width="240" alt="정해짐"> |
| 그날 내 일정과 지금까지의 응답을 보고 수락·다음에. | 추천 시간대에 투표하고 선택지를 더합니다. | 내 캘린더에 들어간 모습과 동네 이름으로 카카오맵 검색. |

### 그룹 설정 · 어두운 모드

| 그룹 설정(긴 화면) | 그룹 캘린더 · 어두운 모드 |
| --- | --- |
| <img src="screens/m2/11-group-settings@2x.png" width="240" alt="그룹 설정"> | <img src="screens/m2/12-group-calendar-dark@2x.png" width="240" alt="그룹 캘린더 어두운 모드"> |
| 그룹 공통(이름·커버·색, 날짜 잡기 기본값), 나에게만(프로필·공유·알림·표시), 관리. 마지막으로 바꾼 사람을 보여 줍니다. | 어두운 모드에서는 띠가 어두운 쪽에서 밝아지고, 탭 막대는 어두운 글라스입니다. |

## M3·M4 채팅·플러스

### 채팅 · 알림 · 플러스

| 그룹 채팅 | 사진·파일 모아보기 | 알림 문구 | 플러스 구독 |
| --- | --- | --- | --- |
| <img src="screens/m3/01-chat@2x.png" width="200" alt="그룹 채팅"> | <img src="screens/m3/02-gallery@2x.png" width="200" alt="모아보기"> | <img src="screens/m3/03-notifications@2x.png" width="200" alt="알림 문구"> | <img src="screens/m3/04-plus@2x.png" width="200" alt="플러스"> |
| 날짜 잡기 카드에서 바로 투표, 답장, 사진. + 버튼이 사진·파일 보내기를 엽니다. | 기기에 받아 둔 것만 보입니다. 보관 기간을 함께 알립니다. | 제목은 그룹 이름, 본문은 "보낸 사람: 내용". | 혜택 4가지와 연·월 가격, 자동 갱신 안내. |

### 채팅 탭 (방 목록)

| 기본 | 1:1만 보기 | 기기 안 검색 | 1:1 시작 |
| --- | --- | --- | --- |
| <img src="screens/m3/05-chat-list@2x.png" width="200" alt="채팅 탭"> | <img src="screens/m3/05-chat-list--dm-filter@2x.png" width="200" alt="1:1 필터"> | <img src="screens/m3/05-chat-list--search@2x.png" width="200" alt="검색"> | <img src="screens/m3/05-chat-list--start-dm@2x.png" width="200" alt="1:1 시작"> |
| 그룹 방과 1:1을 최근 순으로. 알림을 끈 방은 회색 개수로 보이고 탭 배지에서 빠집니다. | 그룹을 나가도 1:1 대화는 남습니다. | 이 기기에 저장된 대화에서만 찾습니다. | 같은 그룹 멤버와만, 한 사람과 방 하나. |

### 사진·파일 보내기

| 사진 | 4번째를 고를 때 | 파일 |
| --- | --- | --- |
| <img src="screens/m3/06-attach@2x.png" width="240" alt="사진 보내기"> | <img src="screens/m3/06-attach--limit@2x.png" width="240" alt="3개 제한"> | <img src="screens/m3/06-attach--files@2x.png" width="240" alt="파일 보내기"> |
| 고른 순서대로 번호. 원본은 끄면 긴 변 2048px로 줄여 보냅니다. | 한 번에 3개까지. 10MB가 넘는 사진이 있으면 원본을 고를 수 없습니다. | 10MB를 넘는 파일은 이유와 함께 빨갛게 표시하고 보내지 않습니다. |

### 신고·차단

| 길게 누르기 | 메시지 고르기 | 이유와 차단 |
| --- | --- | --- |
| <img src="screens/m3/07-report@2x.png" width="240" alt="메시지 메뉴"> | <img src="screens/m3/07-report--2-pick@2x.png" width="240" alt="메시지 고르기"> | <img src="screens/m3/07-report--3-reason@2x.png" width="240" alt="신고 이유"> |
| 답장·복사·차단·신고. | 고른 메시지만 운영자에게 갑니다. iOS의 민감 이미지는 가린 채 보입니다. | 이유를 골라야 보낼 수 있습니다. 고른 메시지만 기기에서 풀어 보낸다고 알립니다. |

| 신고 완료 | 차단 뒤 | 바로 차단할 때 |
| --- | --- | --- |
| <img src="screens/m3/07-report--4-done@2x.png" width="240" alt="신고 완료"> | <img src="screens/m3/07-report--5-blocked@2x.png" width="240" alt="차단 뒤"> | <img src="screens/m3/07-report--block-sheet@2x.png" width="240" alt="차단 확인"> |
| 24시간 안에 검토, 상대에게 알리지 않음. | 그룹 채팅에서는 내 화면에서만 가리고 되돌릴 수 있습니다. | 그룹 채팅과 1:1에서 달라지는 점, 그대로인 것을 한 번에 보여 줍니다. |
