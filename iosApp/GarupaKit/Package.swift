// swift-tools-version: 6.1
import PackageDescription

let package = Package(
    name: "GarupaKit",
    platforms: [.iOS("18.2"), .macOS(.v12)],
    products: [.library(name: "GarupaKit", targets: ["GarupaKit"])],
    dependencies: [
        .package(url: "https://github.com/apple/swift-nio.git", exact: "2.105.0"),
        .package(url: "https://github.com/apple/swift-nio-ssl.git", exact: "2.37.6"),
        .package(url: "https://github.com/apple/swift-certificates.git", exact: "1.21.1"),
    ],
    targets: [
        .target(name: "GarupaKit", dependencies: [
            .product(name: "NIOCore", package: "swift-nio"),
            .product(name: "NIOPosix", package: "swift-nio"),
            .product(name: "NIOHTTP1", package: "swift-nio"),
            .product(name: "NIOSSL", package: "swift-nio-ssl"),
            .product(name: "X509", package: "swift-certificates"),
        ]),
    ],
    swiftLanguageModes: [.v5]
)
