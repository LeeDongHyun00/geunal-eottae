# 디자인 도구

디자인 세션이 클라우드 컨테이너의 임시 디렉터리에서 쓰던 스크립트입니다. 세션을 바꾸면 그 디렉터리가 사라지므로 보존용 브랜치 `claude/design-tools`에 둡니다. `main`에 넣으려면 `docs/`를 바꾸는 PR이라 `user-approved` 라벨이 필요합니다.

| 파일 | 하는 일 | 실행 |
| --- | --- | --- |
| `gen_m1_boards.py` | M1 시안 보드(`.dc.html`)를 만듦. 마지막으로 더한 것은 `StoreReset`(25번) | `python3 gen_m1_boards.py <출력 디렉터리>` |
| `export_screens.js` | 보드를 Playwright로 열어 PNG로 내보냄. 작업의 `clicks`로 상태를 바꾼 뒤 찍음 | `node export_screens.js http://127.0.0.1:8765 jobs/<작업>.json <출력 루트>` |
| `overflow.js` | 390pt 틀 밖으로 나간 요소와 페이지 오류를 찾음 | `node overflow.js http://127.0.0.1:8765 <보드>...` |
| `measure.js` | 보드 내용이 어디까지 내려가는지 재서 아트보드 높이를 정함 | `node measure.js http://127.0.0.1:8765 <보드:폭:높이>...` |
| `interact.js` | 클릭을 차례로 하고 상태마다 찍음 | `node interact.js <주소> <출력> <보드:폭:높이> click:<선택자> shot:<이름>...` |
| `boxes.js`, `shot.js`, `shot2x.js` | 클릭 뒤 선택자의 위치·크기 출력, 1배·2배 캡처 | 각 파일 첫 줄의 사용법 |
| `scrollprobe.js`, `scrollprobe2.js` | 보드 안 스크롤 영역이 끝까지 내려가는지 확인. 주소와 보드 이름(`Me.dc.html` 등)이 코드에 들어 있음 | 서버를 띄운 뒤 `node scrollprobe.js` |
| `strip.py` | PNG 여러 장을 한 줄로 이어 비교용 띠를 만듦 | `python3 strip.py <출력.png> <png>...` |
| `gen_appicon.py`, `export_icon.py`, `icon_png.js` | 앱 아이콘 방향 보드, 플랫폼별 SVG·PNG 내보내기 | `python3 export_icon.py <출력 디렉터리>` |
| `add_chat_badge.py` | M3 보드의 탭 막대에 채팅 배지를 더함 | 파일 첫 줄 참고 |
| `mdcheck.py` | `docs/design/*.md`의 표 칸 수, 파일 링크와 제목 앵커, 쓰이지 않는 PNG를 검사 | `python3 -I mdcheck.py <저장소 루트>` |
| `mermaid-check/` | 마크다운의 Mermaid 블록을 실제 파서로 검사(mermaid 11.4.1) | `npm ci && node check.mjs <md 파일>` |
| `mock-data/group_data.py` | M2 그룹 보드(주말모임, 5명)의 가짜 일정과, 코어가 넘길 날짜별 빈 시간 수 | 실행해 나온 값을 보드에 옮김 |
| `jobs/*.json` | 내보내기 작업의 예(보드 파일, 폭·높이, 배율, 출력 경로, 클릭) | `export_screens.js`에 넘김 |

## 로컬에서 보드 그리기

1. 보드를 한 디렉터리에 모읍니다(`docs/design/boards/*.dc.html`, 새로 만든 보드).
2. 같은 디렉터리에 Claude Design 런타임 `support.js`를 둡니다. 보드가 이 이름으로 불러옵니다. 캔버스 아티팩트(https://claude.ai/artifact/3zAhdWP14DNVCW3dMDkhqY)의 `artifact-type/dc-runtime.js`를 Artifact 도구의 read로 받아 이름만 바꾼 것입니다. React가 든 서드파티 번들이라 저장소에는 넣지 않았습니다.
3. `python3 -m http.server 8765 --bind 127.0.0.1`로 띄우고, 위 스크립트에 `http://127.0.0.1:8765`를 넘깁니다. Playwright와 Chromium은 클라우드 컨테이너에 미리 깔려 있습니다.
4. 내보낸 PNG는 `docs/design/screens/<마일스톤>/`에, 보드는 `docs/design/boards/`에 두고 `canvas.json`(들여쓰기 1칸)에 자리를 더합니다.
