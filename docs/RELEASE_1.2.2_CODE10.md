# OpenJump 1.2.2 — release record (versionCode 10)

## Why 1.2.2 / code 10

The live Google Play listing advertises **1.2.1**, and the prior release handoff
for that build used **versionCode 9**. The earlier 1.2.0 / code 9 checkpoint is
therefore a downgrade and reuses a consumed code; it is **superseded** and must
not be uploaded to Play. The next valid identity is **1.2.2 / versionCode 10**.

## Identity and source

- Application ID: `com.openjump.app`
- Version: `1.2.2` / `versionCode 10`
- Development source: `release/1.2.2-code10` based on dev `main` (includes
  11ee71c and the later F-V-P, locale and optional-movement work)
- Public release commit/tag: recorded after publication (`v1.2.2`)
- The historical `v1.0`, the pinned F-Droid commit `8cd1fea` and the superseded
  `v1.2.0` tag are preserved; no existing tag is moved.

## Functional changes

- **F–V–P hidden in production.** `FVP_ENABLED=false` in release, Home entry
  removed, all five routes guarded, no flow ViewModel/draft recovery created when
  OFF. Module, Room tables, formulas and saved rows retained.
- **Encoder cold-start preview fixed.** A SurfaceView created after the initial
  seek restarts the source instead of issuing a no-op seek.
- Includes the 1.2.1-era camera/viewfinder, adaptive landscape analysis, UI/UX,
  Encoder message and bilateral/empty-session improvements already on main,
  plus optional movement-start marking and es-419/pl/ja/ko locales.

## Validation (development build)

- Version policy: PASS, `1.2.2 (versionCode 10)`.
- Pre-push gate: PASS, exit 0, BUILD SUCCESSFUL.
- JVM: 1,200 debug + 1,200 release, 0 failures/errors/skips; `:fvp` 50;
  `:fvp-lab` 17.
- Lint debug/release: 0 errors.
- `validate.sh release`, `assembleRelease`, bundletool validate and 16 KiB
  zipalign: PASS.
- Instrumented on `emulator-5554`/API35: F–V–P navigation/recovery 10/10,
  DAO/migration/athlete 43/43, Encoder class 3 PASS + 1 fixture-dependent skip,
  skipped known-distance test 1/1 with a 540×960 fixture.
- Release smoke: version 1.2.2 (10), non-debuggable, Home shows Jumps and
  OpenJump Labs only; previous history preserved.

## Artifacts (development build)

- AAB unsigned: 19,307,400 bytes, SHA-256
  `9706aaa52c8e7fc44950c10297c7cb3979996a1b7213c2a0e198b781e0c699b7`
- APK unsigned: 13,702,620 bytes, SHA-256
  `19f065f8c6ee1752e0f34f12c47bf9ebd0bb2037e20b818b6891667960a7f3f5`
- AVD smoke APK (local debug key only): 13,771,583 bytes, SHA-256
  `3bb3ac4bf134e8bfb9d6c0553b560d8b8d9186ed37baf19e358d8b28bcf0354b`

## Status

- **Google Play:** prepared, not uploaded. The upload key/credentials are absent
  on this machine. The AAB is unsigned. See `PLAY_UPLOAD_1.2.2.md`.
- **F-Droid:** tag and automatic updater pending for this version; the official
  recipe remains in `fdroiddata` master and its Tag/version.properties updater
  covers stable `v1.2.2`.
