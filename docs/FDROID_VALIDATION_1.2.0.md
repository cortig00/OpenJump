# F-Droid validation — OpenJump 1.2.0 (9)

Local preparation record for the stable update after the initial 1.0 / code 7
inclusion (fdroiddata MR !51299, merged 2026-10-17). This is not an official
F-Droid approval or a claim that F-Droid has already built or published 1.2.0.

## Recipe and source

- Official recipe: `fdroiddata/metadata/com.openjump.app.yml`, unchanged for
  this update. `AutoUpdateMode: Version`, `UpdateCheckMode: Tags` and
  `UpdateCheckData` already cover stable `v1.2.0`.
- The historical build block (`1.0` / `7`, commit `8cd1fea`) is preserved.
- Development source commit: `f37f0ff5714ce696f323988036b497c3ac9ddc56`
  (same `app/`, `fvp/` and `fvp-lab/` trees).
- Public release commit / annotated tag: `42e59af662cf9fbc59c9b593c72425a605553281`
  / `v1.2.0`. The tag dereferences to `42e59af`.
- `version.properties`: `VERSION_CODE=9`, `VERSION_NAME=1.2.0`.
- Same application ID `com.openjump.app`, single release variant, minSdk 26,
  target/compileSdk 36. No F-Droid flavor, no proprietary dependency added.
- Eight Fastlane locales have `changelogs/9.txt`; the 7.txt files are retained.
- `python tools/check_store_metadata.py` → PASS, eight locales, version 1.2.0 (9).

## Build and code gates

| Check | Result |
| --- | --- |
| `tools/validate.sh pre-push d2030f9` | PASS |
| `:app:testDebugUnitTest` | 1200 tests, 0 failures/errors/skips |
| `:app:testReleaseUnitTest` | 1200 tests, 0 failures/errors/skips |
| `:fvp:test` | 50 tests, 0 failures/errors/skips |
| `:fvp-lab:test` | 17 tests, 0 failures/errors/skips |
| `:app:lintDebug` | 0 errors (463 warnings, 14 information) |
| `:app:lintRelease` | 0 errors (448 warnings, 14 information) |
| `tools/validate.sh release` | PASS; unsigned AAB produced |
| `:app:assembleRelease` | PASS |
| bundletool 1.17.2 `validate` | exit 0 |
| APK zipalign `-c -P 16 -v 4` | PASS |
| APK manifest | `com.openjump.app`, 1.2.0 (9), min 26, target 36 |

Instrumented checks executed on the authorized `OpenJump_API35` AVD
(`emulator-5554`, API 35, font scale 1.5):

- F-V-P guided navigation + recovery: 10/10 PASS.
- F-V-P DAO, Room migration and athlete DAO: 43/43 PASS.
- Encoder reference video class with the 720x1280 fixture (light, font 1.5):
  **3 PASS + 1 fixture-dependent SKIP**. The skipped known-distance test requires
  a 540x960 fixture and **passes 1/1** when run separately with a generated
  540x960 fixture. The first-surface non-black check and the manual-review/
  precision snapshot assertion pass.
- The first full Encoder class run had 2 failures while a "System UI isn't
  responding" overlay was present; the precision evidence screenshot shows that
  overlay. After dismissing it, the class passed 3 tests (the fourth still skipped
  for the fixture reason) and the two previously failing tests passed
  individually. The overlay is a compatible explanation, not a conclusively
  proven root cause for both failures; the decisive evidence is the clean reruns
  and the manual release screenshot.
- **Residual gap:** no release-mode instrumentation of a restored F-V-P back stack
  or direct route access. The release APK is non-debuggable and the five routes
  have no deep links. Code review + release Home smoke + `FVP_ENABLED=false`
  unit test cover the OFF path; no defect was observed.

The release build hides the F-V-P entry and does not create its ViewModel. The
debug build keeps it enabled for the existing instrumented F-V-P suites.

## Artifacts (public tag build)

Unsigned release AAB (no upload key on this machine):

- `app/build/outputs/bundle/release/app-release.aab`
- 19,307,400 bytes
- SHA-256 `0d5ac2c8b1aa1ba499018ffbc86479843f3b2755230d94365cbd46615eb456a2`
- Embedded version-control revision: `42e59af662cf9fbc59c9b593c72425a605553281`

Unsigned release APK (validation only):

- `app/build/outputs/apk/release/app-release-unsigned.apk`
- 13,702,620 bytes
- SHA-256 `b963ede86d37ea8c16f396f8b25e35080c66a7e6d0a41cd06e1565ca705f3f0d`

The official fdroiddata auto-updater generated the 1.2.0 build block from the
published tag. A fresh `fdroid build` of commit `42e59af` from GitHub produced
the same unsigned APK hash (`b963ede…`), and `fdroid lint` / `fdroid scanner`
exited 0 with no warnings or errors. F-Droid's own signing and publication
remain external.

## Pending external gates

- The Play upload key and Google Play API/Fastlane credentials are not available
  on this machine; the AAB is unsigned and has not been uploaded.
- F-Droid's own signing and publication timing are outside this repository.

See [the F-Droid preparation guide](FDROID.md) and the
[release record](RELEASE_1.2.0_CODE9.md).
