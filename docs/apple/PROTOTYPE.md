# Native Apple prototype

This is a deliberately small SwiftUI iPhone-first, iPad-compatible iOS 17+ prototype. It demonstrates a real Kotlin Multiplatform boundary over the existing `JumpMath.kt`; it does not contain a second hand-written math implementation. The displayed values use synthetic sample timestamps (start 100,000 µs, takeoff 400,000 µs, landing 900,000 µs), not video, camera input, or a clinical measurement. Calculation errors are shown as errors rather than replaced with sample output.

## Local build outline (macOS)

1. Use the repository's checksum-pinned Gradle 8.11.1 wrapper and JDK 21. No Android/root Gradle project is configured by these commands.
2. Run `./gradlew -p apple/spikes/jump-core jvmTest`.
3. With Xcode 16.0 selected, run `DEVELOPER_DIR=/Applications/Xcode_16.0.app/Contents/Developer ./gradlew -p apple/spikes/jump-core linkDebugFrameworkIosSimulatorArm64`.
4. With Xcode 26.0.1 selected, open `apple/OpenJumpApple.xcodeproj`, use the shared `OpenJumpApple` scheme and set `JUMP_FRAMEWORK_DIR` to an absolute path, for example `$(pwd)/apple/spikes/jump-core/build/bin/iosSimulatorArm64/debugFramework` from the repository root. Choose an ARM64 iOS simulator and run the app/tests with `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, matching this spike's `iosSimulatorArm64` framework. Intel simulators and physical devices are not built by this spike. Signing is disabled for simulator builds.

The Xcode 16.0 build-framework / Xcode 26.0.1 consumer pairing is an explicit compatibility hypothesis, not an assertion of official Kotlin/Xcode 26 support. It needs the CI compile and real simulator tests before it is accepted. No fallback Kotlin upgrade or replacement math is included.

## Viewing the simulator capture

The `Apple prototype screenshot` workflow is restricted to the public `cortig00/OpenJump` repository and `dev/apple`. On its macOS 15 ARM64 runner it verifies Xcode 26.0.1 and its exact iOS Simulator SDK 26.0, runs independent shared-math tests, builds the Kotlin simulator framework, builds/tests the native scheme, and runs a UI test that waits for the visible prototype and example height before attaching an app screenshot. Download the `iphone-prototype-<commit>` workflow artifact to inspect the PNG and concise gate summary. It contains screenshots/summary only (three-day retention), not an IPA, app binary, full test bundle, or video. This is a finite CI artifact, not an interactive cloud GUI or VNC session.

This prototype adds no video, camera/import, athlete history, clinical claims, signing, physical-device support, or distribution workflow. Kotlin 2.1.20 to Swift 6 / Xcode 26 compatibility remains a hypothesis until the native CI build and simulator tests pass; they have not been run locally. Viewing the screenshot requires GitHub login and repository read access to Actions → the workflow run → Artifacts; the artifact is a PNG/summary, not an interactive simulator or tunnel. The Android application and root Gradle build are untouched.

## First native CI execution

Commit `bf4d797a80259e3d0c7608d0b2ebccf28244eda5`, [run37427990958](https://github.com/cortig00/OpenJump/actions/runs/37427990958): **FAILURE** after7m18s. Hosted JVM tests and the Kotlin2.1.20/Xcode16.0 static ARM64 framework passed; the iOS26 simulator was discovered and booted. Xcode26.0.1 then attempted to link an additional `x86_64` app slice against the ARM64-only framework. The archive was ignored for that architecture and `_OBJC_CLASS_$_JCSJumpCore` remained undefined. Swift/UI tests were cancelled before execution; no screenshot artifact was uploaded.

The bounded correction verifies the framework architecture with `lipo` and requests `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES` for the native simulator test. No compiler upgrade, replacement calculation, signing or test skip. **The ARM64 correction has not yet been qualified by native runtime tests.**

## Second execution: simulator bootstrap blocked

Commit `4b618f38cb8dad5b5aabc203f7d0314ec1e9e168`, [run37429119170](https://github.com/cortig00/OpenJump/actions/runs/37429119170): final API conclusion **CANCELLED** after25m27s because the job exceeded its25-minute limit. Host checks, JVM tests, Kotlin/Native framework linking, its `lipo` ARM64 assertion and simulator discovery passed. The boot step (`simctl boot` followed by `bootstatus -b`) did not complete; its log contains no command-level readiness output, so it does not establish which subcommand stalled or the underlying cause. Cleanup terminated the orphan `simctl` process.

The corrected Swift consumer build, Swift tests and UI test were **SKIPPED**, not PASS or a new linker failure. No screenshot artifact exists. This is not evidence that the Xcode16-produced framework is incompatible with the Xcode26 consumer. It leaves that compatibility and runtime hypothesis unqualified.

The implementation remains a small, unqualified prototype. The next bounded task is simulator-bootstrap diagnostics with explicit per-command/step deadlines and build/runtime evidence separated. Do not blindly rerun the same job, raise the global timeout, replace shared math, switch compiler versions or enable a paid runner. No interactive simulator, real-camera/PTS validation, physical install or distribution has been delivered.

## Bounded simulator diagnosis (not yet run)

The workflow now first runs Xcode 26.0.1 `build-for-testing` against the generic iOS Simulator destination, using the existing Kotlin ARM64 framework, shared derived-data directory, and signing-disabled ARM64 settings. A successful build proves the Swift consumer and test bundles compiled; it does **not** mean any test ran or passed. Only after that gate does discovery select and record an available, shut-down iOS 26.0 iPhone UUID/name/runtime using the existing exact-runtime criteria. The existing Xcode/JDK/Kotlin versions and device-selection rule remain unchanged.

Each boot command prints a flushed UTC start and finish/exit record. `simctl boot` is bounded at120 seconds and `bootstatus -b` at240 seconds inside a seven-minute step. The small standard-library Python runner kills and waits for only its direct child on timeout (exit124); it does not claim to clean up process trees or system services. A failed boot stops the step before readiness or runtime tests. There are no retries, simulator erase/delete, host-service kills, or global job-timeout increase. Runtime then uses `test-without-building` with the same scheme, derived data, explicit discovered UUID, ARM64/framework settings and parallel testing disabled; it has a separate eight-minute step and result bundle. Existing Swift bridge and UI assertions remain the actual runtime gate. Screenshot export occurs only after that command succeeds, requires at least one PNG attachment, and keeps at most three small PNGs plus summary in the three-day artifact. An always-run job summary records individual gate conclusions, including skipped/not-run states; compile success is never reported as runtime test success.

Local standard-library tests cover helper success, exit-code propagation, timeout/reap behavior, stopping before readiness after a boot failure, and simulator-picker valid/no-match/deadline behavior. They exercise the command wrapper and selection logic only, not CoreSimulator. **This diagnostic change is not evidence of an additional CI run:** the prior cancellation has no logged command-level cause, so the particular stalled subcommand/root cause remains unknown. The `4b618f3` ARM64 correction and Xcode16-built framework compatibility still need qualification. Further CI execution requires parent authorization to publish; until that authorized real run completes, native XCTest and screenshot capture remain NOT RUN.
