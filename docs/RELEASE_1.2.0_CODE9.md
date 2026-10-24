> **SUPERSEDED by 1.2.2 / code 10.** Google Play already advertised 1.2.1
> (handoff proposed versionCode 9), so this 1.2.0/code9 checkpoint must not be
> used for Play or as a newer F-Droid release. The source and validation remain
> as historical evidence; see `RELEASE_1.2.2_CODE10.md`.

# OpenJump 1.2.0 — release record (versionCode 9)

## Identity and source

- Application ID: `com.openjump.app`
- Version: `1.2.0` / `versionCode 9`
- Development source commit: `f37f0ff5714ce696f323988036b497c3ac9ddc56`
- Public annotated tag: `v1.2.0` (the curated public source commit; same
  `app/`, `fvp/` and `fvp-lab/` trees as the development commit)
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
- 10/10 F-V-P navigation/recovery, 43/43 data/migration and 4/4 Encoder
  reference instrumented tests on the authorized AVD.
- Final release APK cold-start Encoder preview measured at mean luminance
  186.66 (pre-fix cold start: 1.14) and the manual-review/precision snapshot
  regression passed at font scale 1.5.
- Version policy, license check, `git diff --check`, bundletool validation and
  16 KiB zip alignment pass.

## Publication status

- **Google Play:** prepared, **not uploaded**. The upload keystore and Play
  API/Fastlane credentials are not present on this machine, so no signed AAB
  could be produced. The release AAB is unsigned and must not be uploaded.
- **F-Droid:** source/tag preparation only. The official recipe is already in
  `fdroiddata` master and will detect the stable tag automatically. F-Droid's
  own build/signing/publication are independent.
- No existing tag was moved, no force-push was used and no signing identity was
  changed or generated.
