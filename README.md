# OpenJump

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_openjump_logo.png" alt="OpenJump logo" width="120">
</p>

**Offline video analysis for jumps and athletic performance on Android.**

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-Support-FFDD00?logo=buymeacoffee&logoColor=000000)](https://buymeacoffee.com/cortig00)

OpenJump lets you record or import video, mark jump events frame by frame and
save results locally. Real video presentation timestamps—not nominal frame
rates—provide the timing used in calculations.

## Features

- Six jump protocols: countermovement jump (CMJ), squat jump (SJ), Abalakov,
  unilateral jump, drop jump and standing horizontal jump.
- Jump height, flight time, takeoff velocity and protocol-specific metrics;
  calibrated horizontal distance.
- Athlete profiles, teams, local history, progress and comparisons.
- Camera recording, video import and precise frame-by-frame review.
- **Experimental video Encoder:** scale calibration, visual tracking, repetition
  analysis, velocity/trajectory charts and trajectory-video export.
- User-initiated JSON/CSV exports and restorable manual backups.
- Spanish, English, French, German, Italian, Turkish, Brazilian Portuguese and
  European Portuguese; configurable units and light/dark appearance.

## Install

The official Android package is **`com.openjump.app`**. Google Play is the current official
user distribution channel. Official F-Droid inclusion is being prepared; do not
confuse this preparation with an accepted F-Droid listing.

[OpenJump on Google Play](https://play.google.com/store/apps/details?id=com.openjump.app)

This repository contains the source for **OpenJump 1.0 (`versionCode 7`)**. It does
not contain signed APKs, bundles or signing credentials. You can build a debug APK
locally using the steps below.

## Build from source

Requirements:

- JDK 21 (the app targets Java 17 bytecode).
- Android SDK with platform 36 and the required build tools.
- Android 8.0 / API 26 or newer to run the app.

```bash
git clone https://github.com/cortig00/OpenJump.git
cd OpenJump
```

Set `ANDROID_HOME` to your SDK, or create an untracked `local.properties` with
`sdk.dir` pointing to it. Then use the included, checksum-pinned Gradle wrapper:

```bash
# macOS / Linux
./gradlew :app:assembleDebug :app:lintDebug

# Windows
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Dependencies are resolved from Google Maven, Maven Central and the Gradle plugin
portal. No LaTeX installation is needed for a normal app build: generated formula
vectors are checked in. Their editable sources and optional generator are in
[`tools/encoder-formulas`](tools/encoder-formulas/README.md).

### Release builds

```bash
./gradlew :app:bundleRelease
```

Without upload-key properties this creates an **unsigned** bundle, not a Play-ready
release. To sign your own distribution, supply all four `OPENJUMP_UPLOAD_*` Gradle
project properties outside this repository: `STORE_FILE`, `STORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`. For example, use user-level Gradle properties or
`ORG_GRADLE_PROJECT_OPENJUMP_UPLOAD_*` environment variables. Never commit keys or
passwords. Only the maintainer can sign updates for the official Play application;
your own key cannot update that installation.

## Privacy

Measurement and analysis happen on your device. OpenJump has no accounts, backend,
ads or automatic analytics, and does not request Internet, microphone or location
permission. Local storage uses Android's app sandbox; automatic Android backup is
disabled.

Recorded videos are saved to shared device storage. User-initiated sharing,
exports, manual backups and support email can send copies to other applications
or providers. Backup JSON is **not encrypted**. Read the full
[privacy policy](PRIVACY.md) for details.

## Limitations

OpenJump is a sports-performance tool, **not a medical or clinically validated
device**. Results depend on video quality, viewpoint, calibration and event
selection. Encoder is experimental and has not been validated against a physical
encoder; do not treat its measurements as certified accuracy. Save an analysis
before leaving it: unsaved work does not survive process death.

## Contributing and support

Bug reports and focused pull requests are welcome. Include your Android version,
app version and reproducible steps. Do not post private athlete information,
identifiable videos, backups, credentials or signing files in issues or commits.
Check that the app compiles and lint completes before submitting a change. This
minimal source distribution does not include the development test corpus.

- [Report an issue](https://github.com/cortig00/OpenJump/issues)
- Support email: **openjump.app@gmail.com**
- [Buy Me a Coffee](https://buymeacoffee.com/cortig00) — optional support for development.

## License

OpenJump is free software under **GNU GPL-3.0-or-later**. See [`LICENSE`](LICENSE).
Third-party dependencies retain their respective licenses. See
[third-party notices](THIRD_PARTY_NOTICES.md) and [artwork licensing](ASSET_LICENSES.md).
License texts and runtime attributions are bundled in the APK.

## F-Droid

See [F-Droid preparation](docs/FDROID.md) for the audited dependencies, shared
Fastlane metadata, build recipe, signing differences and future release workflow.
There is no special flavor and no removal of the analysis features. F-Droid and
Play use different signing certificates: switching channels requires a manual
backup and reinstall; neither channel can install an update over the other.

Validate the eight-language store metadata with:

```bash
python tools/check_store_metadata.py
```
