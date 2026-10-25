# OpenJump 1.2.2 — publication record (versionCode 10)

Date: 2026-10-25

This is the current publication record for 1.2.2 / code 10. It supersedes the
development history in [release record](RELEASE_1.2.2_CODE10.md) and
[final report](RELEASE_1.2.2_FINAL_REPORT.md), which remain as historical
evidence only.

## Identity and source

- Application ID: `com.openjump.app`
- Version: **1.2.2** / `versionCode 10`
- Public release commit: `c9e4177c6ad4d3e6c8c1e9eb14796e714fb61bc2`
- Public annotated tag: **`v1.2.2`** → `c9e4177c6ad4d3e6c8c1e9eb14796e714fb61bc2`
- Tag and commit are preserved; no existing tag was moved.
- Historical 1.2.0 / code 9 is **superseded** (tag and release retained as
  history, not moved). Do not claim code 9 was consumed or reused.

## Production baseline correction

- Production on Google Play is **1.2.1 / versionCode 8**, not 9.
- Consumption of code 9 is **unknown**. The earlier assertion that the 1.2.1
  handoff used code 9 was mistaken.
- The already-prepared 1.2.2 / code 10 identity is retained; no new identity or
  tag is needed.

## Google Play submission — user-reported

- The maintainer confirmed code 10 was free across tracks/drafts/library, then
  reported submitting the signed AAB manually to Play.
- Record as **USER-REPORTED MANUAL SUBMISSION** on 2026-10-25. Review and
  publication are **not verified**.
- The prior local blocker (missing upload key, unsigned AAB) is now
  **superseded** for this submission path. No key was transferred and no
  artifact was rebuilt for this record.
- No fresh runtime tests were run for this publication slice.
- Do not describe 1.2.2 as reviewed, approved, or available on Play until the
  listing shows it.

## Artifacts and signing (no rebuild)

- Unsigned AAB: 19307399 bytes, SHA-256
  `db15cf998f097cc8b3b4117ddc32bdc814e1fe70efe70359e0d281d115ed2bfa`.
- Signed AAB: 19380463 bytes, SHA-256
  `5733703662a6dce2d9733b8ee8c35216af353102123d5d71b4378b445b61e1ec`.
- Upload certificate SHA-256:
  `DD:F6:7B:59:03:96:16:C4:F8:ED:C2:2E:00:CB:0F:F0:9F:AB:F2:F4:9D:72:E9:42:79:EA:F3:C9:B8:06:76:EB`.
  This matches local prior 1.2.1 / code 8 records, **not** a fresh Console
  check.
- `jarsigner -verify`: exit 0, `jar verified`, 789 original entries unchanged
  plus signed entries.
- `jarsigner -verify -strict`: exit 4, **NOT PASS** (PKIX/self-signed trust
  warning), no timestamp. Report honestly; do not regenerate the key.
- Signing certificate expires 2054-01-27.
- No rebuild and no key transfer were performed for this record.

## Store notes (canonical)

- The eight `fastlane/metadata/android/*/changelogs/10.txt` files and
  [combined Play notes](PLAY_RELEASE_NOTES_1.2.2.txt) match the canonical
  eight-locale notes **exactly**.
- The 1.2.2 delta is: Encoder black-first-frame fix, optional movement-start
  marking, added Latin American Spanish/Polish/Japanese/Korean, and F-V-P
  remains disabled. Older camera work is not marketed as new in these notes.
- Each locale note is under 500 characters. Validate with:
  `python tools/check_store_metadata.py`.

## F-Droid official status (2026-10-25)

- Official repository (`https://f-droid.org/api/v1/packages/com.openjump.app`):
  `suggestedVersionCode` 7, only 1.0 / 7 listed. Official 1.2.2 publication is
  **pending**.
- Official recipe
  (`https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/com.openjump.app.yml`)
  already contains 1.2.2 / code 10 at `c9e4177...` with
  `CurrentVersion: 1.2.2 / 10`. Do **not** propose a duplicate
  build/version/recipe MR.
- The monitor's code10 build-log URL returned 404: no log was available there.
  This is not proof that the official build failed or succeeded.
- Prior `checkupdates` / `build` / `scanner` PASS results were **local** runs in
  a temporary checkout, not official F-Droid acceptance.

## Post-tag metadata exception

- Per <https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/>,
  F-Droid takes descriptions/graphics/screenshots from the latest known release
  checkout, not from arbitrary `main`, and `fdroiddata` overrides take
  precedence.
- Because `v1.2.2` is immutable, updating `main` notes does not guarantee new
  code-10 notes on F-Droid. A **metadata-only** `fdroiddata` override MR
  carrying the eight canonical `changelogs/10.txt` (no recipe/source/tag
  change) is prepared separately; GitLab is not authenticated and the MR is
  **not submitted**.
- Do not promise that official F-Droid has imported the new notes. For future
  releases, commit store notes **before** the stable tag.

## What was not done

- No Gradle build, no instrumented run, and no fresh runtime tests in this
  docs/metadata-only slice.
- Only documentation and store metadata are being updated; application source,
  version, release tag and binary artifacts are unchanged. No keys are transferred.
- No fresh Play Console certificate check.
- No duplicate F-Droid MR and no submitted metadata MR.

## Claims that must not be made

- “Published/available on Google Play” for 1.2.2 — only a user-reported manual
  submission exists.
- “Signed AAB verified by Play” — only local `jarsigner` results above.
- “Strict signature PASS” — strict exits 4, not PASS.
- “F-Droid available” — only tag detection and local build are verified.
- “Code 9 was consumed/reused” — consumption of code 9 is unknown.
- “Official F-Droid imported the new notes” — metadata MR is not submitted.
