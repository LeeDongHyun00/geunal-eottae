# GeunalCore

Rust 코어의 Swift 패키지입니다. 이 폴더에서 git이 관리하는 것은 `Package.swift`와 이 문서만입니다.

| 파일 | 어디서 오나 |
| --- | --- |
| `GeunalCoreFFI.xcframework/` | `make build-ios`가 만든 `core/out/ios/GeunalCoreFFI.xcframework` |
| `Sources/GeunalCore/GeunalCore.swift` | 같은 빌드가 UniFFI로 만든 `core/out/ios/swift/GeunalCore.swift` |

`make project-ios`(`apps/ios/scripts/import-core.sh`)가 두 파일을 여기로 복사합니다. 코어를 고쳤으면 `make build-ios`를 다시 돌린 뒤 복사합니다.
