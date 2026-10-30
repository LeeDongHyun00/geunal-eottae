// swift-tools-version: 6.0
import PackageDescription

// The home screen widgets (docs/design/m1.md screen 14): the snapshot the app writes, which day it shows, and the
// views. The widget extension and the app's tests share it; nothing here opens the core (docs/parts/ios.md §2).
let package = Package(
    name: "HomeWidget",
    defaultLocalization: "ko",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "HomeWidget", targets: ["HomeWidget"])
    ],
    dependencies: [
        .package(path: "../DesignSystem")
    ],
    targets: [
        .target(
            name: "HomeWidget",
            dependencies: [
                .product(name: "DesignSystem", package: "DesignSystem")
            ],
            resources: [.process("Resources")]
        )
    ],
    swiftLanguageModes: [.v6]
)
