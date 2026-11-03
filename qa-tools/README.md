# QA·보안 검사 스크립트

QA·보안 세션이 쓰던 검사 스크립트를 세션 교체 때 남겨 둔 것입니다. `main`에 넣지 않고 이 브랜치(`claude/laughing-bohr-iyq4lu`)에만 둡니다([인수인계](../.claude/handoff/qa.md)). 저장소 루트에서 `python3 -I`로 돌립니다.

| 스크립트 | 하는 일 | 예 |
| --- | --- | --- |
| `verify_recovery_vectors.py` | `recovery.json`·`login_nonce.json`을 코어와 다른 구현(Python `cryptography`, 손으로 쓴 BIP-39 묶기와 HChaCha20)으로 다시 계산 | `python3 -I qa-tools/verify_recovery_vectors.py core/vectors/recovery.json core/vectors/login_nonce.json core/geunal-crypto/src/recovery/korean.txt` |
| `verify_backup_vectors.py` | `backups.json`의 백업 키, STREAM 조각, CBOR을 다시 열어 봄 | `python3 -I qa-tools/verify_backup_vectors.py core/vectors/backups.json` |
| `check_korean.py` | BIP-39 한국어 목록의 주장(개수, NFKD, NFC 뒤 중복 없음, 음절 수) | `python3 -I qa-tools/check_korean.py core/geunal-crypto/src/recovery/korean.txt` |
| `check_tests.py` | `docs/qa/m1.md` 2장과 위협 모델 5장의 시험 이름이 코드에 있는지(Rust·Kotlin·Swift 이름 꼴로) | `python3 -I qa-tools/check_tests.py docs/qa/m1.md docs/security/threat-model.md . origin/main` |
| `check_md.py` | Markdown의 상대 링크와 표의 칸 수 | `python3 -I qa-tools/check_md.py docs/qa/m1.md` |

- 벡터 스크립트는 `pip install cryptography`가 필요합니다.
- `recovery.json`의 단어 목록 해시는 BIP39 원본(커밋 100ba872, NFKD)의 것입니다. 코어의 `korean.txt`는 NFC 사본이라, 스크립트가 NFKD로 되돌려 대조합니다. 원본 파일을 넘겨도 됩니다.
- 2026-11-03 `main`(54472c2)에서 세 벡터 모두 `failures: 0`였습니다.
- 계약(`docs/parts/protocol.md` 10·13장)이나 벡터가 바뀌면 다시 돌려 `failures: 0`인지 봅니다.
