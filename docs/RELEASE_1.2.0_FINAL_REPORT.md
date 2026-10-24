# OpenJump 1.2.0 — final release report

Date: 2026-10-25 (Europe/Madrid)

## Version and source identity

- Application ID: `com.openjump.app`
- Version: **1.2.0** / `versionCode 9`
- Development source commit: `f37f0ff5714ce696f323988036b497c3ac9ddc56`
- Public release commit: `42e59af662cf9fbc59c9b593c72425a605553281`
- Public annotated tag: **`v1.2.0`** → `42e59af662cf9fbc59c9b593c72425a605553281`
- Public source: <https://github.com/cortig00/OpenJump>
- GitHub Release: <https://github.com/cortig00/OpenJump/releases/tag/v1.2.0>
- The public `main` branch advanced after the tag with a documentation-only commit
  (`3a76e6d`); the tag still points to the release source commit.

Code 9 was chosen above the published Play code 7, the historical code 8
candidate and the highest tagged development code 5. `tools/version_policy.py
--strict-tag` passed at the dev tagged commit.

## Functional changes

- **F–V–P profile hidden in production.** Compile-time `FVP_ENABLED` is false in
  release and true in debug/demo. The Home row is absent, all five routes are
  guarded and redirect to Home when OFF, and the flow ViewModel/draft recovery is
  not created. The module, Room tables, formulas, repositories and saved rows are
  retained. No jump protocol or user data was changed.
- **Encoder cold-start black preview fixed.** When a new `SurfaceView` is
  attached after the initial seek, `FrameSelectorViewModel.refreshCurrentFrame()`
  restarts the source rather than issuing a no-op seek. The requested frame is
  rendered without pressing Next; the fix is bounded by the existing
  playing/export guards.
- Includes the accumulated 1.1/1.2 work: camera viewfinder/quality controls,
  frame-selector and Encoder UX, optional movement-start marking, and Latin
  American Spanish, Polish, Japanese and Korean locales.

## Local validation

| Check | Result |
| --- | --- |
| `tools/validate.sh pre-push d2030f9` | PASS, exit 0, BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 1,200 tests, 0 failures/errors/skips |
| `:app:testReleaseUnitTest` | 1,200 tests, 0 failures/errors/skips |
| `:fvp:test` | 50 tests, 0 failures/errors/skips |
| `:fvp-lab:test` | 17 tests, 0 failures/errors/skips |
| `lintDebug` / `lintRelease` | 0 errors (463/448 warnings, 14 information) |
| `tools/validate.sh release` | PASS, unsigned AAB produced |
| `:app:assembleRelease` | PASS |
| bundletool 1.17.2 `validate` | exit 0 |
| APK zipalign 16 KiB | PASS |
| APK manifest | `com.openjump.app`, 1.2.0 (9), min 26, target 36 |

Instrumented checks on the authorized `OpenJump_API35` AVD (`emulator-5554`,
API 35):

- F–V–P guided navigation + recovery: **10/10 PASS**.
- F–V–P DAO + Room migration + athlete DAO: **43/43 PASS**.
- Encoder reference: **3 PASS + 1 fixture-dependent SKIP** in the 720x1280 class
  run; the skipped known-distance test passes **1/1** with a 540x960 fixture.
  The first-surface non-black regression and the manual-review/precision
  snapshot test pass.
- The first full Encoder run showed two failures while a System UI ANR overlay
  was present. After dismissing it, the clean reruns passed; the manual release
  screenshot measured preview mean luminance **186.66** versus **1.14** before
  the fix (same crop). Causal attribution to the overlay is compatible, not
  conclusively proven; the clean reruns and release smoke are the decisive
  evidence.
- Residual verification gap: no release-mode instrumentation of a restored
  F–V–P back stack/direct access. The release APK is non-debuggable and the
  routes have no deep links; the OFF path is covered by Home smoke,
  `FVP_ENABLED=false` release unit test and code review. No defect was observed.

## Independent GPT 6.1 High review

The independent reviewer inspected the public tree, tag, diff, test receipts and
F-Droid build evidence. Verdict:

- **A — source + tag + F-Droid preparation: GO with notes.** No P0/P1 code
  defect found; public code/build settings match the development tree; the
  F-Droid build from the published tag succeeds and the scanner is clean.
- **B — Google Play upload: NO-GO / BLOCKED.** The AAB is unsigned; the upload
  key and Play API/Fastlane credentials are absent, and there is no upload or
  Play acceptance receipt.

Reviewer notes incorporated: Encoder count corrected (3 pass + 1 skip; skipped
test passes with its required fixture), the System UI causal explanation is not
overstated, and the release OFF restoration gap is disclosed.

## Artifacts and hashes

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/bundle/release/app-release.aab` (unsigned) | 19,307,400 | `0d5ac2c8b1aa1ba499018ffbc86479843f3b2755230d94365cbd46615eb456a2` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 13,702,620 | `b963ede86d37ea8c16f396f8b25e35080c66a7e6d0a41cd06e1565ca705f3f0d` |
| F-Droid `unsigned/com.openjump.app_9.apk` | 13,702,620 | `b963ede86d37ea8c16f396f8b25e35080c66a7e6d0a41cd06e1565ca705f3f0d` |
| AVD smoke APK, signed with local **debug** key | 13,771,583 | `a25e193235d0a031b34bec10d936c687d357ba25307b61af5fc2932e8d438c2b` |

The AAB is **unsigned**. The debug-key APK is only for AVD smoke and is not a
Play or F-Droid distribution artifact.

## Google Play status

**Prepared, not uploaded.** See [the Play upload handoff](PLAY_UPLOAD_1.2.0.md) for the exact signing/upload steps. The Play listing is live
(<https://play.google.com/store/apps/details?id=com.openjump.app>) and the last
publicly confirmed Play version was 1.0 / code 7. Version 1.2.0 / code 9 could
not be signed or submitted because the upload keystore and Play API/Fastlane
credentials are not available on this machine. No Play Console state was
verified beyond the public listing. Do not describe 1.2.0 as published or
available on Play.

## F-Droid status

**Tag published; update detected; local official build passed; F-Droid
publication pending.**

- Official recipe in `fdroiddata` master:
  <https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.openjump.app.yml>
- Initial inclusion MR !51299 merged on 2026-10-17.
- `fdroid checkupdates --auto` detected 1.2.0 / code 9 and generated the build
  block with commit `42e59af`.
- A fresh `fdroid build` from the published GitHub tag succeeded; `fdroid lint`
  and `fdroid scanner` exited 0 with no warnings/errors.
- F-Droid signs and publishes its own APK; its build queue and publication are
  independent. Do not claim F-Droid availability until its own repository shows
  it.

## Incidents, blockers and pending actions

1. **Play credentials missing (blocking for Play).** Obtain the upload key and
   Play API/Fastlane access, build a signed AAB from commit `42e59af`, verify the
   upload certificate, upload to production, add the eight-locale notes and
   complete the Play review.
2. **Initial public tag mispointed and corrected.** The first public push of the
   new `v1.2.0` tag inherited the dev tag object and pointed to the dev commit
   `3546cd3`. It was deleted and recreated on `42e59af` before any F-Droid build
   was published. Current refs confirm `42e59af`; historical `v1.0` and pinned
   `8cd1fea` were not moved. Disclose this traceability incident.
3. **Encoder test isolation.** The full class run is 3 pass + 1 skip because one
   test requires a 540x960 fixture; that test passes alone. A System UI ANR
   overlay caused a transient full-class failure; clean reruns pass. Do not
   claim "4/4 without skips".
4. **Release OFF restoration not instrumented.** Documented residual gap, not an
   observed defect.
5. **No Play or F-Droid publication receipt** exists yet. The goal is not
   complete until Play accepts the signed AAB and F-Droid independently builds
   and publishes its APK.

## Claims that must not be made

- "Published/available on Google Play" for 1.2.0.
- "Signed AAB" or "Play-ready AAB" — the AAB is unsigned.
- "F-Droid available" — only the tag and local build are verified.
- "Encoder 4/4 with no skips" — it is 3 pass + 1 fixture-dependent skip in the
  class run; the skipped test passes separately.
- "Release OFF restoration tested" — it is a documented residual gap.
- "Zero ANRs" — a System UI ANR was observed on the emulator.
- "Play and F-Droid builds are interchangeable" — they use different signing
  keys and cannot update each other.
