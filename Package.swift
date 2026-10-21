// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "GirevoyPulse",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "GirevoyPulse",
            targets: ["GirevoyPulse"]
        )
    ],
    targets: [
        .target(
            name: "GirevoyPulse",
            path: "GirevoyPulse",
            exclude: [
                "Info.plist",
                "App/GirevoyPulseApp.swift"
            ]
        ),
        .testTarget(
            name: "GirevoyPulseTests",
            dependencies: ["GirevoyPulse"],
            path: "GirevoyPulseTests"
        )
    ]
)
