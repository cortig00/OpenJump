# OpenJump 1.2.2 — final release report

Date: 2026-10-25 (Europe/Madrid)

## Version and source identity

- Application ID: `com.openjump.app`
- Version: **1.2.2** / `versionCode 10`
- Public release commit: `c9e4177c6ad4d3e6c8c1e9eb14796e714fb61bc2`
- Public annotated tag: **`v1.2.2`** → `c9e4177c6ad4d3e6c8c1e9eb14796e714fb61bc2`
- Dev tag `v1.2.2` → `ad3effafa6463a8921d38e2a566b71d5be2a7bdd`
- Public source: <https://github.com/cortig00/OpenJump>
- GitHub Release: <https://github.com/cortig00/OpenJump/releases/tag/v1.2.2>

### Why 1.2.2 / code 10

Google Play advertises **1.2.1**, and the prior handoff for that build used
**versionCode 9**. The earlier 1.2.0/code9 checkpoint therefore reused a consumed
code and was a downgrade; it is marked **SUPERSEDED** (tag and release retained
as history, not moved). Code 10 is the next valid code and 1.2.2 the next
version name.

## Functional changes

- **F–V–P hidden in production.** `FVP_ENABLED=false` in release; the Home entry
  is removed, all five routes are guarded and redirect to Home, and the flow
  ViewModel/draft recovery is not created when OFF. The module, Room tables,
  formulas and saved rows are retained.
- **Encoder cold-start preview fixed.** A SurfaceView created after the initial
  seek restarts the source instead of issuing a no-op seek; the first frame is
  visible without pressing Next.
- Includes all 1.2.1-era main work (camera/viewfinder, adaptive landscape
  analysis, UI/UX remediation, clearer Encoder messages, bilateral saving and
  empty-Encoder deletion), optional movement-start marking, and es-419/pl/ja/ko.

## Local validation

| Check | Result |
| --- | --- |
| `tools/validate.sh pre-push d2030f9` | PASS, exit 0, BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 1,200 tests, 0 failures/errors/skips |
| `:app:testReleaseUnitTest` | 1,200 tests, 0 failures/errors/skips |
| `:fvp:test` / `:fvp-lab:test` | 50 / 17 tests, 0 failures |
| `lintDebug` / `lintRelease` | 0 errors |
| `validate.sh release`, `assembleRelease` | PASS |
| bundletool 1.17.2 `validate` | exit 0 |
| APK zipalign 16 KiB | PASS |
| APK/AAB manifest | `com.openjump.app`, 1.2.2 (10), min 26, target 36 |

Instrumented on the authorized `OpenJump_API35` AVD (`emulator-5554`):

- F–V–P navigation/recovery: **10/10 PASS**.
- DAO + Room migration + athlete DAO: **43/43 PASS**.
- Encoder reference class with 720×1280 fixture: **3 PASS + 1 fixture-dependent
  SKIP**; the skipped known-distance test passes **1/1** with a 540×960 fixture.
  First-surface and manual-review/precision tests pass.
- Release smoke: 1.2.2 (10), non-debuggable, Home shows Jumps and OpenJump Labs
  only; previous history retained; no F–V–P row.
- Residual gap: no release-mode instrumentation of a restored F–V–P back stack
  in a non-debuggable build; no deep links exist. Covered by Home smoke, release
  flag unit test and code review; no defect observed.

## Independent GPT 6.1 High review

The independent reviewer audited the corrected 1.2.2/code10 release:

- **A — source + tag + F-Droid preparation: GO with notes (OK_WITH_NOTES).**
  No P0/P1 observed. The identity 1.2.2/code10 is above the published 1.2.1/code9
  evidence; `app`, `fvp`, `fvp-lab` and build settings are identical to the
  reviewed tree; the F-Droid tag, auto-generated build block and fresh build are
  consistent.
- **B — Google Play upload: NO-GO.** The AAB is unsigned and the upload key/API
  access is missing. Code 10 must also be confirmed unused in every Play
  track/draft before uploading.

Reviewer notes incorporated: the temporary fdroiddata checkout logged
`serverwebroot` configuration errors during the first checkupdates/build run;
they did not prevent detection or the build. After configuring a dummy local
`serverwebroot` and `repo/status`, `checkupdates --auto` ran cleanly. The scanner
itself reported 0 warnings / 0 errors. The 1.2.0/code9 tag/release remains marked
SUPERSEDED; no historical tag was moved.

## Artifacts

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| Public unsigned AAB | 19,307,399 | `db15cf998f097cc8b3b4117ddc32bdc814e1fe70efe70359e0d281d115ed2bfa` |
| Public unsigned APK | 13,702,620 | `2cfffe7f85bd1c0fc5fcab04d620a8bb60ccf84ea0827c3be9ddfc54e25b49cc` |
| F-Droid unsigned APK | 13,702,620 | `2cfffe7f85bd1c0fc5fcab04d620a8bb60ccf84ea0827c3be9ddfc54e25b49cc` |
| AVD smoke APK (debug key only) | 13,771,583 | `3bb3ac4bf134e8bfb9d6c0553b560d8b8d9186ed37baf19e358d8b28bcf0354b` |

The AAB is **unsigned**. The debug-key APK is only for AVD smoke.

## Google Play status

**Prepared, not uploaded.** The upload key and Play API/Fastlane credentials are
absent on this machine; the AAB is unsigned. Play currently advertises 1.2.1;
1.2.2 is not available on Play. Exact signing/upload steps:
[PLAY_UPLOAD_1.2.2.md](PLAY_UPLOAD_1.2.2.md).

## F-Droid status

**Tag published; update detected; official build passed; F-Droid publication
pending.** `fdroid checkupdates --auto` detected 1.2.2/10 and generated the build
block with `c9e4177`; a fresh `fdroid build` from the GitHub tag succeeded and
`fdroid lint`/`fdroid scanner` were clean (scanner 0 warnings / 0 errors). The
first run in the temporary checkout logged non-blocking `serverwebroot`
configuration errors; after configuring a dummy local repo, the checkupdates
rerun was clean. F-Droid signs and publishes its own APK; that cycle is
independent and still pending.

## Incidents and pending actions

1. **Play credentials missing (blocking for Play).** Confirm code 10 is unused
   in every Play track/draft, provide the upload key and Play API/Fastlane
   access, build and verify the signed AAB from `c9e4177`, upload to production,
   add the eight-locale code-10 notes and complete review.
2. **1.2.0/code9 superseded.** It reused the code of the published 1.2.1 and was
   a downgrade. Its tag/release are retained but marked superseded; no historical
   tag was moved.
3. **Encoder class skip.** One test requires a 540×960 fixture and is skipped in
   the 720×1280 class run; it passes separately. Do not claim "4/4 without skips".
4. **Release OFF restoration not instrumented.** Documented residual gap.
5. **No Play or F-Droid publication receipt** exists yet.

## Claims that must not be made

- "Published/available on Google Play" for 1.2.2.
- "Signed AAB" or "Play-ready AAB" — it is unsigned.
- "F-Droid available" — only tag, detection and local build are verified.
- "Encoder 4/4 without skips" — 3 pass + 1 fixture-dependent skip, plus 1/1
  separately with 540×960.
- "Release OFF restoration tested" — documented residual gap.
- "Play and F-Droid builds are interchangeable" — different signing keys.
