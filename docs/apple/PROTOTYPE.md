# Native Apple prototype

This is a deliberately small SwiftUI iPhone-first, iPad-compatible iOS 17+ prototype. It demonstrates a real Kotlin Multiplatform boundary over the existing `JumpMath.kt`; it does not contain a second hand-written math implementation. The displayed values use synthetic sample timestamps (start 100,000 µs, takeoff 400,000 µs, landing 900,000 µs), not video, camera input, or a clinical measurement. Calculation errors are shown as errors rather than replaced with sample output.

## Local build outline (macOS)

1. Use the repository's checksum-pinned Gradle 8.11.1 wrapper and JDK 21. No Android/root Gradle project is configured by these commands.
2. Run `./gradlew -p apple/spikes/jump-core jvmTest`.
3. With Xcode 16.0 selected, run `DEVELOPER_DIR=/Applications/Xcode_16.0.app/Contents/Developer ./gradlew -p apple/spikes/jump-core linkDebugFrameworkIosSimulatorArm64`.
4. With Xcode 26.0.1 selected, open `apple/OpenJumpApple.xcodeproj`, use the shared `OpenJumpApple` scheme and set `JUMP_FRAMEWORK_DIR` to an absolute path, for example `$(pwd)/apple/spikes/jump-core/build/bin/iosSimulatorArm64/debugFramework` from the repository root. Choose an installed iOS simulator and run the app/tests. Signing is disabled for simulator builds.

The Xcode 16.0 build-framework / Xcode 26.0.1 consumer pairing is an explicit compatibility hypothesis, not an assertion of official Kotlin/Xcode 26 support. It needs the CI compile and real simulator tests before it is accepted. No fallback Kotlin upgrade or replacement math is included.

## Viewing the simulator capture

The `Apple prototype screenshot` workflow is restricted to the public `cortig00/OpenJump` repository and `dev/apple`. On its macOS 15 ARM64 runner it verifies Xcode 26.0.1 and its exact iOS Simulator SDK 26.0, runs independent shared-math tests, builds the Kotlin simulator framework, builds/tests the native scheme, and runs a UI test that waits for the visible prototype and example height before attaching an app screenshot. Download the `iphone-prototype-<commit>` workflow artifact to inspect the PNG and concise gate summary. It contains screenshots/summary only (three-day retention), not an IPA, app binary, full test bundle, or video. This is a finite CI artifact, not an interactive cloud GUI or VNC session.

This prototype adds no video, camera/import, athlete history, clinical claims, signing, physical-device support, or distribution workflow. Kotlin 2.1.20 to Swift 6 / Xcode 26 compatibility remains a hypothesis until the native CI build and simulator tests pass; they have not been run locally. Viewing the screenshot requires GitHub login and repository read access to Actions → the workflow run → Artifacts; the artifact is a PNG/summary, not an interactive simulator or tunnel. The Android application and root Gradle build are untouched.
