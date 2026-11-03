// swift-tools-version: 6.0
import PackageDescription

// Scratch harness: runs the iOS models' unit tests on Linux against the Linux build of the Rust core.
let package = Package(
    name: "LinuxHarness",
    targets: [
        .systemLibrary(name: "GeunalCoreFFI", path: "Sources/GeunalCoreFFI"),
        .target(name: "GeunalCore", dependencies: ["GeunalCoreFFI"], swiftSettings: [.swiftLanguageMode(.v5)]),
        .target(name: "CoreBridge", dependencies: ["GeunalCore"]),
        .target(name: "DesignSystem"),
        .target(name: "FeatureAccount", dependencies: ["GeunalCore"]),
        .target(name: "FeatureCalendar", dependencies: ["GeunalCore", "DesignSystem"]),
        .target(name: "HomeWidget"),
        .target(
            name: "AppPreview", dependencies: ["GeunalCore", "FeatureCalendar"],
            swiftSettings: [.define("DEBUG")]),
        .testTarget(
            name: "GeunalAppTests", dependencies: ["GeunalCore", "CoreBridge", "DesignSystem", "FeatureAccount", "FeatureCalendar", "HomeWidget"]),
    ]
)
