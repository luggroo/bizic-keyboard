// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TujiaInput",
    platforms: [.iOS(.v13), .macOS(.v10_15)],
    products: [.library(name: "TujiaInput", targets: ["TujiaInput"])],
    targets: [
        .target(
            name: "CTujiaCore", path: "shared",
            exclude: ["bundle", "tests", "CMakeLists.txt"],
            sources: ["src/lexicon.cpp", "src/strokes.cpp", "src/edit.cpp", "src/tujia_core.cpp"],
            publicHeadersPath: "include"
        ),
        .target(
            name: "TujiaInput", dependencies: ["CTujiaCore"], path: "shared/bundle",
            resources: [.copy("Assets")]
        ),
        .testTarget(name: "TujiaInputTests", dependencies: ["TujiaInput"], path: "apple/Tests")
    ],
    cxxLanguageStandard: .cxx17
)
