# F-Droid validation — OpenJump 1.2.2 (10)

Completed against the published tag:

- Public release commit/tag: `c9e4177c6ad4d3e6c8c1e9eb14796e714fb61bc2` / `v1.2.2`.
- `fdroid checkupdates --auto` detected **1.2.2 / code 10** and generated the
  build block with commit `c9e4177`.
- The first run in a temporary checkout logged `serverwebroot` configuration
  errors (the local fdroiddata config had no repository target). After setting a
  dummy local `serverwebroot` and `repo/status`, the checkupdates rerun completed
  without errors. This was a local configuration issue, not a recipe or source
  failure; the detection/build result was unchanged.
- A fresh `fdroid build` from the GitHub tag succeeded; `fdroid lint` and
  `fdroid scanner` exited 0 with no warnings/errors.
- F-Droid unsigned APK: 13,702,620 bytes, SHA-256
  `2cfffe7f85bd1c0fc5fcab04d620a8bb60ccf84ea0827c3be9ddfc54e25b49cc`,
  identical to the public unsigned APK.
- F-Droid signs and publishes its own APK; its signing/build/publication cycle is
  independent and still pending.

Development tree validation:

- `python tools/check_store_metadata.py` → PASS, eight locales, 1.2.2 (10).
- `tools/validate.sh pre-push d2030f9` → exit 0.
- 1,200 debug + 1,200 release JVM tests, 0 failures/errors/skips.
- `:fvp` 50 and `:fvp-lab` 17 tests, 0 failures.
- lint debug/release: 0 errors.
- `validate.sh release`, `assembleRelease`, bundletool and 16 KiB zipalign PASS.
- Instrumented: F–V–P 10/10, data/migration 43/43, Encoder 3 PASS + 1 skip;
  skipped known-distance test 1/1 with its 540×960 fixture.
