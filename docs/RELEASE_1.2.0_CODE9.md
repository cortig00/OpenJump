# OpenJump 1.2.0 — release record (versionCode 9)

## Identity and source

- Application ID: `com.openjump.app`
- Version: `1.2.0` / `versionCode 9`
- Development source commit: `f37f0ff5714ce696f323988036b497c3ac9ddc56`
- Public release commit / annotated tag: `42e59af662cf9fbc59c9b593c72425a605553281`
  / `v1.2.0` (same `app/`, `fvp/` and `fvp-lab/` trees)
- Public source: <https://github.com/cortig00/OpenJump>
- F-Droid recipe: <https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.openjump.app.yml>

Code 9 was selected because it is above the published Play code 7, the
historical code 8 candidate and the highest tagged development code 5. The
version-name transition from the beta tags to stable 1.2.0 is valid under
`tools/version_policy.py`.

## Functional changes

- **F-V-P hidden in production.** A compile-time `FVP_ENABLED` flag is `false`
  for release and `true` for debug/demo. The Home entry is removed from release,
  the five F-V-P routes are guarded and redirect to Home, and the flow
  `ViewModel`/draft recovery is not created when the flag is off. The module,
  Room tables, formulas, repositories and existing saved rows are retained.
- **Encoder cold-start preview fixed.** When a new `SurfaceView` is attached
  after the initial seek, the viewer restarts the source instead of issuing a
  no-op seek. The first frame is visible without pressing Next; a regression
  test samples the rendered viewport.
- Includes the accumulated 1.1/1.2 work: camera viewfinder/quality controls,
  frame-selector and Encoder UX, optional movement-start marking, and Latin
  American Spanish, Polish, Japanese and Korean locales.

## Validation

The full evidence summary, test counts, AVD runs and artifact hashes are in
[F-Droid validation 1.2.0](FDROID_VALIDATION_1.2.0.md). Highlights:

- 1200 debug + 1200 release JVM tests, 0 failures/errors/skips.
- `:fvp` 50 and `:fvp-lab` 17 tests, 0 failures.
- Debug/release lint: 0 errors.
- 10/10 F-V-P navigation/recovery and 43/43 data/migration instrumented tests.
- Encoder reference: first-surface and precision tests pass; the class run is
  3 PASS + 1 fixture-dependent skip, and the skipped known-distance test passes
  1/1 with a 540x960 fixture.
- Residual gap: the OFF release path was verified by Home smoke, release flag
  unit test and code review, but not by a restored F-V-P back stack in a
  non-debuggable release build.
- Final release APK cold-start Encoder preview measured at mean luminance
  186.66 (pre-fix cold start: 1.14) and the manual-review/precision snapshot
  regression passed at font scale 1.5.
- Version policy, license check, `git diff --check`, bundletool validation and
  16 KiB zip alignment pass.

## Artifacts

- AAB (unsigned): 19,307,400 bytes, SHA-256
  `0d5ac2c8b1aa1ba499018ffbc86479843f3b2755230d94365cbd46615eb456a2`
- APK (unsigned): 13,702,620 bytes, SHA-256
  `b963ede86d37ea8c16f396f8b25e35080c66a7e6d0a41cd06e1565ca705f3f0d`
- F-Droid's fresh build from the published tag produced the same APK hash.

## Publication status

- **Google Play:** prepared, **not uploaded**. The upload keystore and Play
  API/Fastlane credentials are not present on this machine, so no signed AAB
  could be produced. The release AAB is unsigned and must not be uploaded.
- **F-Droid:** annotated tag published; the official `fdroiddata` updater
  detected 1.2.0 / code 9 and generated the build block automatically. A local
  official build, lint and scanner pass. F-Droid's own signing and publication
  are independent and still pending.
- The historical `v1.0` tag and the recipe's pinned `8cd1fea` commit were kept.
- No existing historical tag was moved and no signing identity was changed or
  generated.
