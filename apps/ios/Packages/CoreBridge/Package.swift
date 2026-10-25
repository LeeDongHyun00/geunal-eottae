// swift-tools-version: 6.0
import PackageDescription

// What sits between the generated core API and the screens (docs/parts/ios.md §3): the keychain secret store, the
// store directory, and the change listener as an AsyncStream.
let package = Package(
    name: "CoreBridge",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "CoreBridge", targets: ["CoreBridge"])
    ],
    dependencies: [
        .package(path: "../GeunalCore")
    ],
    targets: [
        .target(
            name: "CoreBridge",
            dependencies: [
                .product(name: "GeunalCore", package: "GeunalCore")
            ]
        )
    ],
    swiftLanguageModes: [.v6]
)
