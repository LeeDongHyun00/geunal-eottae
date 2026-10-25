// swift-tools-version: 5.10
import PackageDescription

// The Rust core as the app sees it (docs/parts/rust-core.md §7): the XCFramework and the Swift that UniFFI generates.
// Both come out of `make build-ios` (core/out/ios) and `make project-ios` copies them in here, so git ignores them;
// only this manifest is source. The package stays in Swift 5 language mode because the generated code is not ours to
// make Swift 6 clean.
let package = Package(
    name: "GeunalCore",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "GeunalCore", targets: ["GeunalCore"])
    ],
    targets: [
        .binaryTarget(name: "GeunalCoreFFI", path: "GeunalCoreFFI.xcframework"),
        .target(
            name: "GeunalCore",
            dependencies: ["GeunalCoreFFI"],
            // SQLCipher inside the core is built on CommonCrypto and takes its random bytes from Security; a static
            // library cannot pull those frameworks in by itself, so whoever links the core links them too.
            linkerSettings: [
                .linkedFramework("Security"),
                .linkedFramework("CoreFoundation"),
            ]
        ),
    ],
    swiftLanguageVersions: [.v5]
)
