# 복구 코드 단어 목록의 출처와 고지

`korean.txt`는 BIP-39의 한국어 단어 목록 2048개를 순서 그대로 NFC(완성형 음절)로 바꾼 것입니다([공통 계약](../../../../docs/parts/protocol.md) 10장).

| 원본 | 커밋 | 원본 파일의 SHA-256 |
| --- | --- | --- |
| [bitcoin/bips `bip-0039/korean.txt`](https://github.com/bitcoin/bips/blob/100ba872c808946155b1eeb8c4568058f2eea0a7/bip-0039/korean.txt) | `100ba872c808946155b1eeb8c4568058f2eea0a7` | `9e95f86c167de88f450f0aaf89e87f6624a57f973c67b516e338e8e8b8897f60` |

- 원본은 NFKD(자모를 나눈 형태)입니다. 이 파일을 한글 음절 규칙으로 다시 나누면 원본과 바이트까지 같아지고, `recovery` 모듈의 시험이 그 해시를 확인합니다.
- 바이너리에는 NFC만 넣습니다. 화면에 보이고 입력과 비교하는 형태가 NFC이고, NFKD보다 22KB 작습니다.

## BIP-39

BIP-39 문서는 "This BIP falls under the MIT License."라고 밝힙니다. 저자는 Marek Palatinus, Pavol Rusnak, Aaron Voisine, Sean Bowe입니다.

```
MIT License

Copyright (c) Marek Palatinus, Pavol Rusnak, Aaron Voisine, Sean Bowe

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
