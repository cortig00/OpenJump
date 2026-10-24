# F-Droid validation — OpenJump 1.2.2 (10)

Pending after tag publication: `fdroid checkupdates --auto` must detect 1.2.2 /
code 10, generate the build block with the v1.2.2 commit, and a fresh
`fdroid build` from the public tag must produce the unsigned APK. `fdroid lint`
and `fdroid scanner` must exit 0. The official F-Droid signing/publication is
independent and remains external.

Development tree validation already completed:

- `python tools/check_store_metadata.py` → PASS, eight locales, 1.2.2 (10).
- `tools/validate.sh pre-push d2030f9` → exit 0.
- 1,200 debug + 1,200 release JVM tests, 0 failures/errors/skips.
- `:fvp` 50 and `:fvp-lab` 17 tests, 0 failures.
- lint debug/release: 0 errors.
- `validate.sh release`, `assembleRelease`, bundletool and 16 KiB zipalign PASS.
- Instrumented: F–V–P 10/10, data/migration 43/43, Encoder 3 PASS + 1 skip;
  skipped known-distance test 1/1 with its 540×960 fixture.
