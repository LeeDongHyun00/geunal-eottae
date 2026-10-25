# 음력 표의 출처와 고지

`table.rs`는 `core/scripts/gen-lunar-table.py`가 아래 두 npm 패키지에서 만듭니다. 두 패키지 모두 한국천문연구원 자료를 바탕으로 하고, 1900–2049년의 모든 해에서 윤달 위치와 달마다의 대소가 같습니다. 스크립트는 패키지를 고정한 버전과 npm 무결성 해시로 확인한 뒤 데이터로만 읽습니다.

| 패키지 | 버전 | 무결성 | 쓴 파일 |
| --- | --- | --- | --- |
| [manseryeok](https://www.npmjs.com/package/manseryeok) | 2.0.0 | `sha512-h4YeuKP+vsAa01K72v/H+5MwxEZd1Sv7H2hC8YU7r05JjNaCDvVhGaIot0zxrktyZVU6SGN5Ed9d1coTWW8rpQ==` | `dist/calendar/lunar-data.js`(1391–2049년은 KASI 음양력 API) |
| [korean-lunar-calendar](https://www.npmjs.com/package/korean-lunar-calendar) | 0.4.0 | `sha512-kJXyH3sI/butJxCBtsLXlMJsl5qqNKNjduf1Na0s7XXwP+D/Wdlx0R0/S5ygxPVFSXf/JZ3bBKaCP6bGWhgZ0w==` | 소스 맵의 `src/korean-lunar-data.ts` |

## manseryeok

```
MIT License

Copyright (c) 2025 Yoohyojun

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## korean-lunar-calendar

```
MIT License

Copyright (c) 2022 Jinil Lee

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
