# iOS 세션 메모 (머지하지 않는 브랜치)

이전 iOS 세션이 컨테이너에만 두었던 메모입니다. 인수인계 파일은 `.claude/handoff/ios.md`입니다. 이 브랜치는 PR로 올리지 않습니다.

- `drafts/`: 아직 올리지 않은 PR(5b, 6, 7)의 설명과 squash 커밋 메시지 초안입니다. 시험 수와 CI 줄은 올리기 전에 다시 셉니다.
- `pr5-review-todo.md`: #75에 남은 Codex 지적 3건과 QA가 요청한 시험(D7, D11)입니다.
- `linux-harness/`: 리눅스에서 iOS 모델 시험을 실제 코어에 붙여 돌린 방법입니다. 화면(SwiftUI)은 빠지므로 CI를 대신하지 않습니다.

## 리눅스 시험 하네스

1. Swift 6.3.1 리눅스 툴체인(ubuntu24.04)을 받아 `PATH`에 둡니다.
2. 코어를 빌드합니다: `cd core && cargo build --locked -p geunal-ffi`.
3. 바인딩을 만듭니다: `cargo run --locked -q -p uniffi-bindgen -- generate --no-format --language swift --library target/debug/libgeunal_ffi.so --out-dir <bindings>`.
4. 하네스 디렉터리에 `Package.swift`를 두고, `files.txt`의 줄마다 `<대상 디렉터리> <저장소 경로>`로 심볼릭 링크를 만듭니다. `<bindings>/GeunalCore.swift`는 3번의 결과이고, 헤더 `GeunalCoreFFI.h`는 복사합니다. `shims/`의 파일은 이름 앞부분의 대상 디렉터리에 복사합니다(`String(localized:bundle:)`, 팔레트, 토스트 타이머의 리눅스 대역).
5. 돌립니다: `LD_LIBRARY_PATH=core/target/debug swift build --build-tests -Xlinker -Lcore/target/debug`, 이어서 `swift test --skip-build`.

함정: 모든 시험이 동기인 `@MainActor` XCTest 클래스는 리눅스에서 시험을 찾다가 크래시합니다. `@MainActor`를 빼거나 비동기 시험을 둡니다.
