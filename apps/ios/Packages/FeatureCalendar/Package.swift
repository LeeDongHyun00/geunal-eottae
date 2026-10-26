// swift-tools-version: 6.0
import PackageDescription

// The calendar screens (docs/parts/ios.md §3): month, week and list views, quick entry, the event editor, shift
// templates, people and the next-up bar.
let package = Package(
    name: "FeatureCalendar",
    defaultLocalization: "ko",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "FeatureCalendar", targets: ["FeatureCalendar"])
    ],
    dependencies: [
        .package(path: "../GeunalCore"),
        .package(path: "../DesignSystem"),
    ],
    targets: [
        .target(
            name: "FeatureCalendar",
            dependencies: [
                .product(name: "GeunalCore", package: "GeunalCore"),
                .product(name: "DesignSystem", package: "DesignSystem"),
            ],
            resources: [.process("Resources")]
        )
    ],
    swiftLanguageModes: [.v6]
)
