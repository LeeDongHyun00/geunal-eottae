# 안드로이드 크기 분석 도구(보관용 브랜치)

인수인계 때 이전 코어 세션의 임시 디렉터리에서 옮긴 도구입니다. `main`에는 넣지 않았습니다. arm64-v8a의 8MB 예산(`core/scripts/build-android.sh`)을 넘을 때, 무엇이 큰지 찾는 데 씁니다. 결과는 `core/target/size/`(git이 무시함)에 남습니다. `SCRATCH`로 위치를 바꿀 수 있습니다.

| 파일 | 하는 일 |
| --- | --- |
| `build-arm64.sh` | arm64-v8a만 `build-android.sh`처럼 빌드하고 strip한 크기를 냅니다. ABI 셋을 다 빌드하는 것보다 빠릅니다. |
| `try-size.sh <abi> <이름> [cargo 인자]` | 설정 하나를 바꿔 빌드하고 크기와 큰 섹션을 냅니다. 예: `--config 'profile.release.opt-level="s"'` |
| `map-build.sh <abi>` | 링크 맵(`-Map`)을 남기며 빌드합니다. |
| `mapsize.py <map>` | 링크 맵을 라이브러리·오브젝트별로 더합니다. |
| `crates.py <nm 출력> [N] [M]` | `nm --size-sort` 출력을 크레이트별로 더하고 큰 심벌을 보입니다. |
| `size-notes.md` | 10월 26일 측정 메모: 큰 항목과 줄일 후보 |

이 브랜치의 스크립트는 저장소 루트에서 실행합니다(`scripts/tool.sh`로 NDK와 cargo-ndk를 받음).
