# 0001. MLS 구현체: mls-rs

## 상태

채택, 2026-10-25. [개요](../kickoff.md) 10장의 미결정 사항 "MLS 구현체: M0에서 mls-rs와 OpenMLS를 비교해 확정(권고: mls-rs)"을 닫습니다.

## 배경

그날어때의 채팅·날짜 잡기 메시지와 상태 키는 MLS(RFC 9420) 그룹이 지킵니다([개요](../kickoff.md) 6장, [공통 계약](../parts/protocol.md) 2장). Rust 코어가 iOS·안드로이드·리눅스(종단 시험)에서 같은 라이브러리를 쓰므로, 구현체 선택은 저장소 구조, 빌드 크기, 서버가 할 수 있는 검증, 외부 리뷰 범위를 한꺼번에 정합니다.

구현체가 맞춰야 하는 조건은 다음입니다.

- 암호 조합 0x0003(`MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519`) 하나만 씁니다. 다른 조합은 키 패키지 검사에서 거부합니다.
- MLS 상태는 앱 저장소와 같은 SQLCipher 파일에 둡니다([Rust 코어](../parts/rust-core.md) 4장). `libsqlite3-sys`는 빌드에 하나만 들어가야 합니다.
- 커밋은 공개 메시지로 보내고, 매 커밋마다 다음 epoch의 GroupInfo를 함께 올려 같은 사람의 새 기기가 외부 커밋으로 다시 들어옵니다([공통 계약](../parts/protocol.md) 2장).
- API 서버의 Kotlin 파서가 메시지 헤더(wire format, group ID, epoch)를 읽습니다. 코어는 그 테스트 벡터를 만듭니다.
- 2단계 후보에 "서버의 MLS 커밋 검증"이 있습니다([개요](../kickoff.md) 8장).

## 선택지

후보는 둘 다 Rust로 된 RFC 9420 구현이고, 둘 다 상호 운용 시험을 통과합니다. 차이는 프로토콜이 아니라 주변부입니다.

| 항목 | mls-rs 0.56 (AWS) | OpenMLS (Phoenix R&D · Cryspen) |
| --- | --- | --- |
| 라이선스 | Apache-2.0 OR MIT | MIT |
| 암호 백엔드 | `mls-rs-crypto-rustcrypto`(RustCrypto). `RustCryptoProvider::with_enabled_cipher_suites([CURVE25519_CHACHA])`로 0x0003 하나만 켤 수 있음 | `openmls_rust_crypto`(RustCrypto) 또는 libcrux. 0x0003 지원 |
| 상태 저장 | 공식 `mls-rs-provider-sqlite`. 기본 기능 `sqlcipher-bundled`가 SQLCipher를 번들하고, 그룹 상태와 키 패키지를 앱 저장소와 같은 파일의 별도 테이블에 둠 | `StorageProvider` 트레이트와 메모리 기본 구현. SQLite·SQLCipher 공식 제공자는 없어서 키 자료의 영속화를 직접 만들고 유지해야 함 |
| 외부 커밋 · Welcome · ratchet tree 확장 | 지원. `CommitOptions::with_allow_external_commit(true)`를 켜면 커밋을 만들 때 다음 epoch의 GroupInfo가 함께 나옴(`CommitOutput::external_commit_group_info`) | 외부 커밋 지원. GroupInfo는 커밋 뒤에 따로 내보냄 |
| 서버 쪽 검증 | `external_client` 기능: 비밀 없이 공개 상태만으로 커밋을 따라가며 검증하는 클라이언트 | 해당하는 크레이트 없음 |
| 동기 · 비동기 | `maybe_async`로 둘 다 빌드. 코어는 동기 빌드를 씀 | 동기 API. 비동기는 같은 방식으로 제공되지 않음 |
| 헤더 접근 | `MlsMessage::wire_format()`, `epoch()`, `group_id()`. 헤더 벡터를 만드는 데 그대로 씀 | 들어온 메시지에서 그룹 ID와 epoch를 읽을 수 있음. 벡터는 만들어 보지 않음 |
| 전달 서비스 쪽 추상화 | 저장·암호·신원 제공자 트레이트. 전달 서비스는 앱 몫 | 최소한의 트레이트. 전달 서비스는 앱 몫 |
| FFI | `mls-rs-uniffi`가 있으나 쓰지 않음(코어가 자체 UniFFI 경계를 가짐) | 없음 |
| 보안 리뷰 | 제3자 감사 확인되지 않음 | 일부 구성 요소에 공개 리뷰 이력이 있으나, 지금 쓰는 판 전체를 덮는 감사로 확인된 것은 없음 |
| 의존성 트리 | RustCrypto 크레이트 중복 판이 여럿. P-256·P-384가 무조건 컴파일되어 `crypto-bigint 0.5`가 들어옴(RUSTSEC-2026-0387) | RustCrypto 또는 libcrux 트리 |

[개요](../kickoff.md) 부록 B에서 mls-rs 0.56으로 확인한 숫자입니다. OpenMLS는 같은 조건으로 재지 않았으므로 비교 숫자는 없습니다.

| 측정 | 값 |
| --- | --- |
| "토요일 7시 어때?" 엔트리 | CBOR 62바이트, MLS 메시지 254바이트 |
| 10명 그룹 | 9명 추가 커밋 2.7KB, 환영 메시지 3.1KB, 강퇴 커밋 1.2KB |
| 클라이언트 11개 전체 실행 최대 메모리 | 10.7MB(리눅스) |
| 헤더 | 파이썬 20줄 파서로 커밋(공개 메시지)·일반 메시지(비공개 메시지)의 그룹 ID와 epoch를 정확히 읽음 |

## 결정

mls-rs 0.56을 씁니다. 함께 쓰는 크레이트와 설정은 다음입니다.

| 크레이트 | 버전 | 설정 |
| --- | --- | --- |
| `mls-rs` | 0.56 | 기본 기능(동기 빌드) |
| `mls-rs-crypto-rustcrypto` | 0.22 | 켜는 암호 조합은 `CipherSuite::CURVE25519_CHACHA` 하나 |
| `mls-rs-provider-sqlite` | 0.23 | `sqlcipher-bundled`. `CipheredConnectionStrategy`로 앱 저장소 파일과 같은 raw key |

래퍼는 `core/geunal-mls`에 있습니다. `DefaultMlsRules`에 `with_ratchet_tree_extension(true)`와 `with_allow_external_commit(true)`를 두어 커밋마다 Welcome(있을 때)과 다음 epoch의 GroupInfo를 `CommitBundle`로 한꺼번에 내놓고, 키 패키지·Welcome·GroupInfo를 받을 때마다 암호 조합이 0x0003인지 확인합니다.

## 근거

순서대로 무게가 큽니다.

1. **SQLCipher 저장 제공자가 바로 있습니다.** MLS 상태와 앱 데이터가 한 파일, `libsqlite3-sys` 하나로 끝납니다. OpenMLS였다면 키 자료의 영속화를 직접 쓰고, 그 코드도 외부 리뷰 범위에 들어갑니다.
2. **`external_client`가 2단계 "서버의 MLS 커밋 검증"으로 이어집니다.** 커밋을 공개 메시지로 두는 계약은 이 기능을 염두에 둔 것입니다.
3. **부록 B의 숫자가 이미 있습니다.** 메시지 크기와 메모리가 [Rust 코어](../parts/rust-core.md) 8장 예산 안에 드는 것을 확인했고, 헤더 벡터를 API의 Kotlin 파서가 그대로 씁니다.
4. **제공자를 암호 조합 하나로 제한할 수 있습니다.** 다른 조합의 키 패키지는 코어에 들어오기 전에 거부됩니다.

## 영향

| 직군 | 영향 |
| --- | --- |
| Rust 코어 | 0.56 / 0.22 / 0.23을 고정합니다. 올릴 때는 기능 PR과 섞지 않고 따로 올립니다([CLAUDE.md](../../CLAUDE.md) 버전 고정). `rusqlite`는 `mls-rs-provider-sqlite`가 요구하는 0.37에 묶이고, `chacha20poly1305 0.10`·`rand_core 0.6`도 `mls-rs-crypto-rustcrypto`에 맞춥니다(`core/Cargo.toml` 주석). `core/deny.toml`에 RUSTSEC-2026-0387(P-256·P-384 아래의 `crypto-bigint`, 0x0003은 쓰지 않음) 예외를 이유와 함께 두고, `mls-rs-core`와 `chacha20poly1305`의 중복 판을 막습니다 |
| 백엔드 | Kotlin 헤더 파서는 `core/vectors/`의 mls-rs 생성 벡터를 기준으로 합니다. 2단계에서 커밋 검증을 하면 `external_client`를 쓰는 작은 Rust 서비스나 JNI 호출이 필요합니다 |
| iOS · 안드로이드 | 직접 영향은 없습니다. MLS는 코어 안에서만 보이고, 앱은 UniFFI 경계만 봅니다 |
| 인프라 | `make lint-core`의 cargo-deny 예외 목록이 늘어납니다 |
| 보안 | mls-rs의 제3자 감사는 확인되지 않았습니다([개요](../kickoff.md) 6장 한계). 공개 베타 전(M4 게이트)에 MLS 통합부 외부 리뷰를 받고, 유료 감사를 받지 않았다는 사실을 보안 안내 페이지에 적습니다 |

## 다시 볼 때

- mls-rs 또는 `mls-rs-provider-sqlite`가 저장 형식을 깨는 판을 내놓을 때. 기기의 MLS 상태는 백업하지 않으므로([공통 계약](../parts/protocol.md) 2장) 마이그레이션 대신 외부 가입으로 다시 들어가는 길을 확인합니다.
- 두 라이브러리 중 하나의 보안 감사가 공개될 때.
- 2단계의 서버 커밋 검증을 시작할 때. `external_client`가 기대대로 쓰이는지 그때 확인합니다.
- `mls-rs-crypto-rustcrypto`가 P-256·P-384를 선택 기능으로 돌려 RUSTSEC-2026-0387 예외를 지울 수 있을 때.
