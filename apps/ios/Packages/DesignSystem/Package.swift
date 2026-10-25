// swift-tools-version: 6.0
import PackageDescription

// Design system v0 (docs/design/README.md) as Swift: colors, the twelve group colors with five tints, type scale,
// spacing and radii. Values mirror docs/design/tokens.json.
let package = Package(
    name: "DesignSystem",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "DesignSystem", targets: ["DesignSystem"])
    ],
    targets: [
        .target(name: "DesignSystem")
    ],
    swiftLanguageModes: [.v6]
)
