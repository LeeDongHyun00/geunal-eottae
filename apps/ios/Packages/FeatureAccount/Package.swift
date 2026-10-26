// swift-tools-version: 6.0
import PackageDescription

// Onboarding, sign-in and the account screens (docs/parts/ios.md §3). The Kakao SDK is the one third-party dependency
// the app takes (ios.md §1); it is pinned exactly here and its own dependency through apps/ios/Package.resolved.
let package = Package(
    name: "FeatureAccount",
    defaultLocalization: "ko",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "FeatureAccount", targets: ["FeatureAccount"])
    ],
    dependencies: [
        .package(path: "../GeunalCore"),
        .package(path: "../CoreBridge"),
        .package(path: "../DesignSystem"),
        .package(url: "https://github.com/kakao/kakao-ios-sdk", exact: "2.29.0"),
    ],
    targets: [
        .target(
            name: "FeatureAccount",
            dependencies: [
                .product(name: "GeunalCore", package: "GeunalCore"),
                .product(name: "CoreBridge", package: "CoreBridge"),
                .product(name: "DesignSystem", package: "DesignSystem"),
                .product(name: "KakaoSDKCommon", package: "kakao-ios-sdk"),
                .product(name: "KakaoSDKAuth", package: "kakao-ios-sdk"),
                .product(name: "KakaoSDKUser", package: "kakao-ios-sdk"),
            ],
            resources: [.process("Resources")]
        )
    ],
    swiftLanguageModes: [.v6]
)
