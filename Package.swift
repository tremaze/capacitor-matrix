// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TageaCapacitorMatrix",
    platforms: [.iOS(.v16)],
    products: [
        .library(
            name: "TageaCapacitorMatrix",
            targets: ["CapMatrixPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"),
        .package(url: "https://github.com/matrix-org/matrix-rust-components-swift.git", exact: "26.01.04")
    ],
    targets: [
        .target(
            name: "CapMatrixPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "MatrixRustSDK", package: "matrix-rust-components-swift")
            ],
            path: "ios/Sources/CapMatrixPlugin"),
        .testTarget(
            name: "CapMatrixPluginTests",
            dependencies: ["CapMatrixPlugin"],
            path: "ios/Tests/CapMatrixPluginTests")
    ]
)
