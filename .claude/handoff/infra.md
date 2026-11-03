# 인프라·조정 인수인계

갱신: 2026-11-03, 이전 세션 https://claude.ai/code/session_011N4YrEay6bJDt87HveJLyz

## 지금 상태

- 사용자 지시로 보류 중입니다(#82, 2026-10-31부터). 재개하라는 말이 오기 전에는 새 일을 시작하지 않습니다.
- M0는 끝났고 M1 진행 중입니다. 최근 머지: 코어 #63·#71, 계약 #58, 디자인 #67·#72·#74, 보안 문서 #65.
- 주간 감사(`audit.yml`)는 2026-11-02에 OpenSSL 3.5.9 확인까지 녹색이었습니다.
- 운영 규칙 v2가 들어갔습니다: 담당 세션만 PR 구독, 자동 머지, `gate`(보류·문서 확인), 세션 교체(CLAUDE.md).

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| #73 안드로이드 편집기·교대 도장 | 커밋 18개, 보류 전 CI 진행 중 | 새 안드로이드 세션이 CI·리뷰 확인 뒤 자동 머지 |
| #75 iOS 나 탭 | Codex P2 3건, QA 요청 D7·D11 시험 2건 | 새 iOS 세션이 고치고 자동 머지 |
| 안드로이드 `pr4-local`, iOS `work/pr5b-account` | 각 세션 컨테이너에만 있음 | 인수인계 때 브랜치로 푸시 |
| #61, #62, #64, #69 | 세션 표시 없음 | 사용자에게 누구 것인지 확인 |
| #76~#81 Dependabot | 2026-11-01 생성 | 재개 뒤 조정 세션이 확인하고 자동 머지 |

## 다음 할 일

1. 사용자가 GitHub 설정 두 가지를 바꿨는지 확인합니다: 자동 머지 허용, 룰셋 `main`의 필수 상태 검사에 `gate`(출처 GitHub Actions).
2. 재개하면 #82를 닫고 CLAUDE.md "세션 교체" 순서대로 모든 세션을 교체합니다.
3. 이전 세션에 묶인 꺼진 예약 4개를 지웁니다: `trig_01YHXoQG92zgVR3EcZGQLBeU`(iOS), `trig_01B1A61YVeFHkEm3auEmthvL`(안드로이드), `trig_01A5oo2D38UeUdoENU2m7GeT`(디자인), `trig_01XVMb67LFV1hV5nnPyEoC4P`(iOS).
4. #74 결정 38~40을 앱 세션에 전달합니다(iOS: 삭제 실패 문구, 교대 실패 카드, `ViewThatFits` 줄).
5. 디자인: m1.md 결정 35를 확정으로 고치고, 월 칸 "+N"을 누르면 그날 목록을 보여 줄 방법을 정합니다.
6. M2 준비(m2.md 5장): 초대 미리보기, 10자 7일 코드, 떠나는 방장, LeadTransfer 알림.

## 기다리는 결정

| 결정 | 누가 | 어디에 |
| --- | --- | --- |
| DELETE /me에 기기 키 서명 추가(추천: 예, "새 키로 시작"에도) | 사용자 | `docs/security/threat-model.md` 3.7 |
| 앱이 우리 CA만 믿게 하기(추천: M2 전, 계획 문서부터) | 사용자 | 같은 곳 |
| 디자인 세션이 앱 PR 스크린숏을 시안과 대조하기 | 사용자 | 디자인 세션 6번 항목 |
| `docs/qa.md` 5장을 운영 규칙 v2에 맞추는 PR | 사용자 | 이 PR 뒤에 올린 문서 PR |

## 이전 세션

| 직군 | 세션 |
| --- | --- |
| 조정·인프라 | `session_011N4YrEay6bJDt87HveJLyz` |
| 코어 | `session_01QdPReBuCP4AFqFEaYXCiQx` |
| iOS | `session_01RfjiuYQGAn1o6QhASZ2Rry` |
| 안드로이드 | `session_018cHvJnb7AzZGk493KhFQM4` |
| 디자인 | `session_01NfNQYytRc1WA56NULSw6PM` |
| QA·보안 | `session_01GRmPm3J6S7gJxWpYESbzX6` |

## 알아 둘 것

- 머지 큐는 개인 계정 저장소에서 쓸 수 없어 자동 머지와 `gate`로 대신합니다. 저장소를 조직으로 옮기면 머지 큐와 `ci.yml`의 `merge_group` 트리거를 다시 봅니다.
- `docs/qa.md` 5.2 규칙 9가 `pull_request_target`을 막아서 `gate.yml`은 `pull_request`로 돕니다.
- 여섯 세션이 한 계정의 5시간 사용량 한도를 나눠 씁니다. 2026-10-31에 안드로이드 세션이 한도에 걸렸습니다.
- 클라우드 세션에는 브라우저 연결이 없어 App Store Connect와 Play Console을 볼 수 없습니다. 사용자 컴퓨터의 세션이 필요합니다.
- `audit.yml` 예약 실행은 몇 시간씩 늦게 시작하곤 합니다(cron `17 21 * * 0`).
- 안드로이드 arm64 라이브러리는 7.86MB로, 예산 8MB에 0.14MB 남았습니다.
- 손으로 머지할 때 `expectedHeadSha`는 40자 전체 SHA여야 합니다.
