# F-Droid validation — OpenJump 1.0 (7)

Validated locally on 2026-10-05. This is **not** official F-Droid approval,
GitLab CI completion or publication. No Play upload or signing change occurred.

## Source and tooling

- Original public Play snapshot / immutable `v1.0`:
  `5c47e887dc06b294f9b9a1f6458df6b670f854aa`.
- First F-Droid build's exact pinned source:
  `8cd1fea192bdd5e984e5bd5a74f8f6463129912f`.
- Source preparation is on the public `fdroid/inclusion` branch. Keep that commit
  reachable; later documentation and the recipe do not alter its source tree.
- Official container image: `registry.gitlab.com/fdroid/fdroidserver:buildserver`,
  digest `sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8`.
- fdroidserver revision: `c21c177ff6d813697aaf9c988ca9fbb2b571b468`.
- Official fdroiddata category/template snapshot:
  `c635cfad907a3c92443f36550d45a4a949b14015`.
- Debian OpenJDK 21.0.12.1; Android platform 36 / build-tools 35.0.0;
  Gradle 8.11.1 / AGP 8.9.2 / Kotlin 2.1.20 / KSP 2.1.20-1.0.31.
- No host keystore, private Gradle properties or signing credentials were mounted.

## Results

| Check | Result |
| --- | --- |
| `python tools/check_store_metadata.py` | PASS, eight locales, shared icon and default screenshots |
| Gradle `:app:testDebugUnitTest` | NO-SOURCE, **not** a test-suite pass |
| Gradle `:app:assembleDebug` | PASS |
| Gradle `:app:assembleDebugAndroidTest` | PASS packaging; test sources NO-SOURCE, no instrumented suite executed |
| Gradle `:app:lintDebug :app:lintRelease` | PASS; existing warnings remain, no lint errors |
| `git diff --check` | PASS |
| `fdroid rewritemeta` and `fdroid lint com.openjump.app` | PASS with official fdroiddata categories |
| `fdroid scanner --refresh --exit-code --json com.openjump.app:7` | PASS; no warnings/errors |
| `fdroid build --verbose --scan-binary --refresh-scanner --stop com.openjump.app:7` | PASS from the pinned public commit; release APK produced |
| APK scanner | PASS; non-free class and extra-signing-block scans enabled |
| `fdroid checkupdates --allow-dirty com.openjump.app` | PASS; finds `v1.0` and extracts 1.0 / code7 from `version.properties` |
| Second `fdroid build --test --verbose --scan-binary --stop com.openjump.app:7` | PASS; APK byte-identical in the same environment |
| fdroidserver Fastlane/text/image importer | PASS; name/summary/description/code7 changelog in all eight locales, common icon and two screenshots each for EN/ES/DE/FR |

The official scanner automatically removes the Gradle wrapper scripts/JAR before
using its checksum-checked launcher. No `scanignore`, `scandelete`, `--skip-scan`,
`--force` or `novcheck` is used. Gradle's native symbol-stripping warning remains;
the three freely licensed AndroidX native libraries are packaged unchanged.

The public source distribution omits the development test corpus. Historical
private-repository unit/instrumentation counts are not results from this public
checkout and must not be represented as such.

## Built artifact

Unsigned APK (local evidence only, not committed or an official download):

- File: `com.openjump.app_7.apk`.
- Size: **12,800,626 bytes**.
- SHA-256: `7b6954b1be5da52114e896023e745f7e773e0c49d9044610476e3c946c78ef30`.
- Identity: `com.openjump.app`, versionName `1.0`, versionCode `7`, minSdk 26,
  target/compileSdk 36.
- APK verification intentionally fails because it is **unsigned**. Official
  F-Droid operators sign their distribution; no new key is created here.
- All **13 DEX/native entries** match the Linux baseline F-Droid build from the
  original public source byte for byte. Changes are license assets and the
  generic coffee pictogram, plus source-only metadata/documentation.
- Eight license/inventory assets are present; no ML model is bundled.
- Merged permissions: CAMERA, WRITE_EXTERNAL_STORAGE (maxSdk 28),
  ACCESS_NETWORK_STATE, WAKE_LOCK, and the package's signature-level
  DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. No INTERNET permission.

The original archived Play AAB is unchanged (SHA-256
`d15bd9be44a622cf73926c32a1c9504fd41f1c8da3a0146a47cacac2362ee28e`).
The first F-Droid source is intentionally later than the Play snapshot; no claim
is made that its APK equals the signed Play APK.

## Reproducibility and ABI assessment

A repeated local F-Droid build produced the exact same APK hash. This is useful
**same-environment repeatability evidence**, not independent reproducible-build
verification or compatibility with Play App Signing. Both builds could reuse
Gradle's cache; no separate clean-room/toolchain reproduction was completed.
`Binaries` and `AllowedAPKSigningKeys` are absent by design.

All four ABIs' native entries together occupy only **202,140 compressed bytes**
(arm64 47,520; armeabi-v7a 35,160; x86 54,372; x86_64 65,088). ABI splitting would
save less than 0.2 MiB per APK while complicating the recipe/version codes. No
flavor, split APK recipe or native rebuild is justified for this first inclusion.

## Focal Android smoke

The candidate **debug** APK was installed and launched on the dedicated
`OpenJump_SmallPhone_API35` AVD, explicit serial `emulator-5556`, API35. Onboarding
could be skipped; Settings → About displayed **Version 1.0 (7)**, the preserved
donation action and the generic coffee icon. About → GPL license opened the
bundled offline-license screen. Light/dark About states were captured; candidate
app logs contained no FATAL EXCEPTION/ANR.

The prior AVD application certificate was incompatible with the debug candidate.
A named pre-test snapshot was taken before uninstalling **only on this AVD**;
the snapshot-load command returned OK afterward. A transient adb offline/reconnect
prevented an immediate post-restore package-version check. The AVD reconnected
with boot complete and was closed without snapshot-save. No physical phone was
connected or changed.

This is not instrumentation, a release-APK smoke, a full measurement/backup
roundtrip or hardware-camera/OEM/accuracy validation. No calculation, Room,
backup schema, permissions, dependency version or build logic changed here.

## Evidence and remaining external gates

Local evidence is retained outside Git under `artifacts/fdroid-preparation/`:
`final-fdroid-build.log`, `repeat-fdroid-build.log`,
`candidate-deterministic-gates.log`, `final-fdroid-checkupdates.log`,
`final-listing-import.json`, `final-artifact-evidence.json`, dependency audit
inputs, APK/source tarball and AVD captures/logs. They are not required to build
this source and contain no F-Droid production signing material.

Remaining: submit the one-file fdroiddata MR, run its actual GitLab pipeline,
answer maintainer review, await signing/publication. [Submission instructions](FDROID_SUBMISSION.md).
No listing badge or statement of official inclusion is added before acceptance.
