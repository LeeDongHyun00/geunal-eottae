# 0002. 코어의 HTTP·TLS 스택: rustls(ring) + webpki-roots

## 상태

채택, 2026-10-25.

## 배경

Rust 코어(`core/geunal-sync`)가 API 서버에는 HTTPS(reqwest)로, 게이트웨이에는 WebSocket(tokio-tungstenite)으로 붙습니다. 같은 라이브러리가 iOS, 안드로이드 3개 ABI, 리눅스(헤드리스 종단 시험)에서 빌드됩니다. 서버 쪽은 Caddy가 Let's Encrypt 인증서로 TLS를 끝냅니다([인프라](../parts/infra.md) 2장).

TLS 백엔드와 신뢰 저장소를 어떻게 잡는지에 따라 안드로이드 빌드에 C 툴체인이 필요한지, 앱이 첫 요청 전에 플랫폼 초기화를 해야 하는지, 기기에 설치된 사용자 CA를 믿는지가 갈립니다. PR #4의 인수인계에서 두 가지로 좁혔습니다.

## 선택지

| 항목 | (A) reqwest 0.13 기본 `rustls` 기능 | (B) rustls + `ring` + `webpki-roots` |
| --- | --- | --- |
| 암호 제공자 | aws-lc-rs | ring |
| 신뢰 저장소 | `rustls-platform-verifier`로 OS 신뢰 저장소 | Mozilla 루트 번들(`webpki-roots`) |
| 안드로이드 빌드 | aws-lc-rs가 cmake와 C 툴체인을 요구 | ring은 NDK의 clang만으로 빌드 |
| 안드로이드 런타임 | 첫 요청 전에 JNI 초기화 호출이 필요(앱이 Context를 넘겨야 함) | 플랫폼별 초기화 없음 |
| WebSocket | tokio-tungstenite가 자기 루트 기능을 켜면 `webpki-roots`가 다른 판으로 한 번 더 들어옴 | 같은 `rustls::ClientConfig`를 `Connector::Rustls`로 넘김 |
| 사용자 설치 CA | 믿음(기기 수준 프록시가 가로챌 수 있음) | 믿지 않음 |
| 루트 갱신 | OS 업데이트에 따라감 | 앱 업데이트에 따라감 |

## 결정

(B)를 씁니다.

- `reqwest 0.13`은 기본 기능을 끄고 `rustls-no-provider`, `json`, `http2`만 켭니다.
- `rustls 0.23`은 기본 기능을 끄고 `ring`, `std`, `tls12`, `logging`을 켭니다.
- `core/geunal-sync/src/tls.rs`의 `client_config()`가 `ring::default_provider()`와 `webpki_roots::TLS_SERVER_ROOTS`로 `ClientConfig` 하나를 만듭니다.
- HTTP는 `Client::builder().use_preconfigured_tls(...)`로, WebSocket은 `connect_async_tls_with_config(..., Some(Connector::Rustls(...)))`로 같은 설정을 받습니다.
- `tokio-tungstenite 0.30`은 `connect`와 `__rustls-tls`만 켭니다. 루트 기능을 켜면 `webpki-roots`가 두 판 들어오기 때문입니다.

## 근거

- **안드로이드가 단순해집니다.** JNI 초기화가 없으니 안드로이드 직군은 JNA로 코어를 부르는 라이브러리만 받고, 코어는 Context를 모릅니다. ring은 `cargo-ndk`가 주는 clang만으로 빌드되어 CI에 cmake를 더하지 않습니다.
- **TLS 설정이 하나입니다.** HTTPS와 WebSocket이 같은 `ClientConfig`를 쓰므로 루트와 프로토콜 버전이 어긋날 곳이 없습니다.
- **Let's Encrypt가 번들에 있습니다.** ISRG Root X1은 Mozilla 루트에 들어 있어 Caddy의 인증서가 그대로 검증됩니다.
- **루트가 앱과 함께 갱신됩니다.** 코어와 앱은 따로 배포하지 않으므로([Rust 코어](../parts/rust-core.md) 7장) `webpki-roots`를 올리면 다음 앱 빌드에 들어갑니다.
- **사용자 설치 CA를 믿지 않습니다.** 기기 수준 프록시가 끼어들면 연결이 거부됩니다. 종단 간 암호화 앱에서는 결점이 아니라 바라는 동작입니다.
- **aws-lc-rs가 잠금 파일에 없습니다.** `cargo tree -i aws-lc-rs`로 확인했습니다.

## 영향

| 직군 | 영향 |
| --- | --- |
| 안드로이드 | `rustls-platform-verifier` 초기화를 부르지 않습니다. 코어는 JNA 라이브러리일 뿐이고, 네트워크 관련 Context 전달이 없습니다 |
| iOS | 없습니다. 플랫폼 신뢰 저장소를 쓰지 않으니 ATS 예외도 없습니다 |
| 인프라 | 서버 인증서는 Mozilla가 믿는 루트로 이어져야 합니다. Let's Encrypt는 됩니다. 사설 CA나 자체 서명 인증서는 코어가 거부합니다 |
| 백엔드 · 게이트웨이 | 없습니다 |
| Rust 코어 | `webpki-roots`를 정기 의존성 PR에서 올립니다. 로컬 스택과 종단 시험은 루프백의 평문 `http://`·`ws://`를 쓰므로 이 설정을 거치지 않습니다 |

받아들인 비용입니다.

- reqwest의 `rustls-no-provider`는 호출되지 않는 `rustls-platform-verifier`(안드로이드에서는 `jni`까지)를 그대로 컴파일합니다. 크기는 `make build-android`가 남기는 `sizes.md`로 ABI당 8MB 예산 안에서 확인합니다.
- `webpki-roots`를 올리지 않으면 새 루트로 발급된 인증서를 믿지 못합니다.

## 다시 볼 때

- ABI당 8MB 예산을 넘고 그 원인에 쓰지 않는 플랫폼 검증기가 들어 있을 때.
- 스토어가 플랫폼 신뢰 저장소 사용이나 기업용 CA 지원을 요구할 때.
- reqwest가 `rustls-platform-verifier` 의존 없이 rustls를 쓰는 기능을 내놓을 때. 그때 `rustls-no-provider`를 바꿉니다.
