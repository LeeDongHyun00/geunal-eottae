# 디자인 인수인계

갱신: 2026-11-03, 이전 세션 https://claude.ai/code/session_01NfNQYytRc1WA56NULSw6PM

## 지금 상태

마지막으로 머지된 내 PR은 #74(dcf3528, 2026-10-31)입니다. 열린 디자인 PR은 없습니다.

M1 완료 조건 대조([plan.md](../../docs/plan.md) 3장 디자인 줄):

| 할 일 | 상태 | 근거 |
| --- | --- | --- |
| M1 화면 시안(온보딩, 월·주·목록, 빠른 입력, 편집, 교대, 사람·생일, 설정, 위젯) | main에 있음 | [m1.md](../../docs/design/m1.md) 1장의 화면 1–25, `docs/design/screens/m1` PNG 49장, `boards/` 50개(#13–#74) |
| 앱 아이콘 초안 | main에 있음(방향 A, 확정은 M4) | `docs/design/icon/`(#3), AppIcon 보드 |
| UX 문구 | main에 있음 | [tone.md](../../docs/design/tone.md)(#13 뒤 #27–#74로 보완) |
| M2 와이어프레임(그룹 캘린더, 날 테이블, 날짜 잡기, 투표, 그룹 설정) | main에 있음 | [m2.md](../../docs/design/m2.md)(#54), `screens/m2` 12장 |

[m1.md](../../docs/design/m1.md) 5장 결정 대조:

| 결정 | 상태 | 근거 |
| --- | --- | --- |
| 1–6 | main에 있음, 사용자 확정(2026-10-25) | #13, #14 |
| 7–24 | main에 있음 | #27(계약 #26, iOS #36에 맞춤) |
| 25 | main에 있음 | #40(계약 #39) |
| 26 기기 인증 | main에 있음, 사용자 결정(2026-10-26) | #43 |
| 27–29 | main에 있음 | #48(iOS #46) |
| 30–34 | main에 있음 | #51(안드로이드 #49, iOS 알림 문구) |
| 35 열 수 없던 캘린더(25번 화면) | main에 있음. "안"으로 적혀 있어 확정 표기는 안 함 | #67. 사용자가 #63 결정 1을 받아들임(#63 머지 9d73bf5) |
| 36 답을 잃은 삭제, 37 시작 오류 | main에 있음 | #70, #72(코어 #71의 같은 증명 재시도) |
| 38–41 쓰기 실패 문구, 교대 실패, 빠른 입력 줄, 월 칸 탭 | main에 있음. 앱 반영은 iOS·안드로이드 몫 | #74(안드로이드 #73) |

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| `claude/design-tools` | 보존용, PR 없음. 보드 생성기 `gen_m1_boards.py`, PNG 내보내기, 넘침·표·링크·Mermaid 검사 | 쓸 때 꺼내 씀. main에 넣으려면 `docs/` PR(`user-approved`) |
| `claude/design-m1-*`, `claude/design-m2-wireframes`, `claude/design-decisions-confirmed`, `claude/fervent-dirac-ax8u2f` | 모두 squash로 머지됨 | 지워도 됨 |

## 다음 할 일

1. **결정 35 확정.** m1.md의 "### 안 (코어 PR #63의 결정 1을 받아들일 때)"를 사용자 결정 절로 바꾸고, "받아들이지 않으면 … 거둡니다" 문장을 뺍니다. `docs/` PR이라 `user-approved`가 필요합니다. 근거: 조정 세션 2026-11-03, #63.
2. **"+N" 방안을 문서 PR로 제안.** 월 칸의 "+N"을 누르면 그날 일정 목록이 보이게 합니다(빠른 입력 시트 위 목록 또는 그 주 보기). Month·QuickEntry 보드, 4·5번 화면, 새 결정이 필요합니다. 근거: #74 설명의 "Not decided here".
3. **앱 PR 스크린숏 대조(요청이 올 때만).** CI 스크린숏(안드로이드 `android-screenshots` 아티팩트, iOS UI 테스트 첨부)을 `docs/design/screens/`와 대조해, 그 PR에 다른 점만 댓글로 남깁니다. 디자인 세션은 그 PR을 구독하지 않습니다. 근거: 사용자 허락(조정 세션 2026-11-03).
4. **M2 디자인([plan.md](../../docs/plan.md) 4장).** 조정 세션이 M2를 열면 그룹 캘린더 띠와 날 테이블, 날짜 잡기 시트와 카드, 투표, 그룹 설정, 커버 머리글, 초대와 카카오톡 공유 카드, M3 와이어프레임을 합니다.

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| m2.md의 다른 직군 결정(초대 미리보기, 초대 코드 10자·7일, 떠난 주도자, 주도자 넘김 알림) | 코어·백엔드(M2 준비 때 조정 세션이 넘김) | [m2.md](../../docs/design/m2.md) 5장, [infra.md](infra.md) 다음 할 일 6 |
| 결정 37–40의 앱 반영(iOS: 삭제 실패 문구, 교대 실패 카드, 빠른 입력 줄바꿈, 시작 오류) | iOS·안드로이드 세션(조정 세션이 전달) | m1.md 결정 37–40, [infra.md](infra.md) 다음 할 일 4 |
| "+N" 방안 | 디자인 제안, 사용자 확정 | 다음 할 일 2 |

## 알아 둘 것

- **캔버스:** https://claude.ai/artifact/3zAhdWP14DNVCW3dMDkhqY 의 `project/`가 `docs/design/boards/`와 같은 보드입니다(StoreReset까지). 다시 게시할 때는 파일 목록을 먼저 읽어야 거절되지 않고, `file_path`는 절대 경로로 씁니다.
- **보드 렌더:** 런타임 `support.js`는 저장소에 없습니다. 받는 법은 `claude/design-tools`의 `docs/design/tools/README.md`에 있습니다.
- **`canvas.json`:** 들여쓰기 1칸입니다. 다른 들여쓰기로 다시 쓰면 diff가 파일 전체가 됩니다.
- **결정 번호:** m1.md 끝에 절을 더해 번호를 잇습니다. 두 PR이 동시에 끝에 더하면 한 곳이 충돌하고, 번호 순서로 둘 다 살립니다(#67·#70 때 510cc12).
- **넘겨받은 주장은 확인 뒤 씀:** 다른 세션이 전한 내용은 PR·코드로 확인합니다. androidx.biometric의 `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`은 API 28·29에서만 안 됩니다(javap로 확인). 화면 번호를 잘못 전한 일도 있었습니다.
- **문구:** "~했어요"로 씁니다. 실패는 "{동작}하지 못했어요. 다시 시도해 주세요"(결정 38)이고, "~되었습니다" 꼴은 쓰지 않습니다(tone.md 4장).
- **검사:** 문서 PR의 CI는 `changes`·`lint`만 돕니다. 표·링크·PNG는 `mdcheck.py`, Mermaid는 `mermaid-check`로 로컬에서 확인합니다.
