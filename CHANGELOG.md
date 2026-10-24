# Changelog

## 1.2.2 (versionCode 10) — Production release candidate

- Supersedes the 1.2.0 / code 9 checkpoint. Google Play already advertises
  **1.2.1**, whose handoff used **versionCode 9**; reusing that code would be
  rejected and publishing 1.2.0 would be a downgrade. Code 10 is the next valid
  code and 1.2.2 is the next version name.
- Keeps the F-V-P profile hidden in production: `FVP_ENABLED=false` for release,
  the Home entry removed, all five routes guarded, and no flow ViewModel or draft
  recovery created when OFF. Module, tables and calculations are retained.
- Keeps the Encoder cold-start first-frame fix: a SurfaceView created after the
  initial seek restarts the source instead of issuing a no-op seek.
- Includes all 1.2.1-era work present on main (camera/viewfinder, adaptive
  landscape analysis, UI/UX remediation, clearer Encoder messages, bilateral
  saving and empty-Encoder deletion) plus optional movement-start marking and
  the es-419/pl/ja/ko locales.

## 1.2.0 (versionCode 9) — Production release candidate

- Feature flag `FVP_ENABLED` hides the experimental F-V-P profile from the
  production UI. The implementation, Room tables, calculations and saved rows are
  retained; debug/demo builds keep the existing flow for development and tests.
- Release builds do not create the F-V-P flow ViewModel or run its draft recovery,
  so hiding the entry cannot mutate user drafts. All five F-V-P routes remain
  registered behind a guard to keep restored navigation stacks safe, and redirect
  to Home when the flag is off.
- Fixes the Encoder reference viewer staying black on a cold start: when a new
  SurfaceView is attached after the initial seek, the source is restarted so
  ExoPlayer produces the requested frame instead of treating the seek as a no-op.
- Includes the accumulated 1.1/1.2 development work: camera viewfinder and
  quality controls, frame-selector and Encoder UX, optional movement-start
  marking, Latin American Spanish, Polish, Japanese and Korean locales.
- Version selected as 1.2.0 / code 9: above published Play code 7 and the
  historical code 8 candidate, and above the highest tagged development code 5.

Release notes are kept in repository English and identify one release identity:
version name, Android `versionCode`, annotated tag, verified AAB hash, and the
exact artifact promoted without rebuilding.

## 1.2.0 (versionCode 8) — Signed Play candidate (PROMOTION BLOCKED)

- Subsequent source checkpoint on `release/1.2.0-code8`: user-approved oblique-plate advisory with explicit manual confirmation; invalid/provisional references remain guarded. Eleven new JVM policy tests and four executed editor no-op regressions pass. Temporary black-frame trace code removed before the authorized branch commit/push. This source change is **not in the archived AAB/APK below**; black preview remains unresolved and no Play publication, main merge or tag is authorized.

- Candidate identity: VERSION_NAME 1.2.0, versionCode 8; branch `release/1.2.0-code8`, source base `d124050`; applicationId `com.openjump.app`, minSdk 26, targetSdk/compileSdk 36, existing upload key retained.
- Scope carries forward the Encoder first-use and A/B zoom source-integration notes below (explicit blank-by-default manual distance with visible units, scale/target separation, green A/B points/line with enlarged FIT1x zoom/pan, local Cancel/Return, contextual result help); no engine, metric, PTS, Room or build change in this metadata slice.
- Ancestry: 1.2.0/8 chosen because 1.0.1 would decrease by ancestry against tagged `v1.2.0-beta.2`; policy is never relaxed. Code 8 exceeds greatest tagged code 5.
- Signed AAB **18,710,377 bytes**, SHA-256 `1cbd909ede6371cafdb5d1e803fd67cfcbdd7c9d19ff90c847d6573f0a5c0852`, archived in `artifacts/release-1.2.0-code8/`; companion APK/mapping/eight-locale notes retained. Build from d124050 + local version.properties delta; no new commit/tag/push/upload. Normal signature/same upload cert/R8/lint/policy/bundletool/16KiB packaging PASS; strict PKIX exit4 NOT PASS. Runtime native upgrade/Encoder/SJ/Save/History/backup-restore/JSON-CSV passed after explicit NextFrame recovery, but **first native Encoder preview and AB snapshot black0/400 remain a promotion blocker**. No fix claimed. Exact evidence/limits: `docs/RELEASE_1.2.0.md`.
- Known blocker carried forward: small/font150% light **15/16 with one black-snapshot failure** (snapshot 0/400), unresolved; no root/phone proof. Do not promote before resolution or explicit risk acceptance.
- Privacy: public-page review remains accepted per `docs/RELEASE_1.0.md`; this slice does not reopen it. No bodyweight, accuracy, physical-device or F-Droid claims.

## Source-integration checkpoint — Encoder first-use and A/B zoom (2026-10-09, prior to candidate packaging)

- User-approved source integration, not a new version/tag/artifact publication.
- Show both scale methods up front; require an explicit blank-by-default manual
  distance with visible units. Separate scale reference from tracking target.
- Clearer preparation/tracking/loss recovery and progressive export controls;
  accessible result help, critical warnings and confidence. Preserve four tabs,
  shared selection and save/reset confirmations.
- Encoder-only green A/B points/line, enlargement from an empty manual reference,
  full-frame FIT1x, zoom/pan, local Cancel/Return and original-source coordinates.
- Eight locales and new JVM/Compose/real-video regression coverage; no engine,
  metric/calibration math, PTS, shared mapper, ViewModel, Room or build changes.
- Debug gates PASS with677 JVM cases0failures/errors/skips retained. Previous
  focused AVD72/72PASS, dark150%/light130% subsets16/16 each; **light150%15/16,
  one black-snapshot failure**, unresolved. Not minified-release qualification.
- Positive maintainer UX feedback is not a physical-accuracy/TalkBack assertion.
- Source integration and proposed release gates: [record](docs/ENCODER_INTEGRATION_20261009.md).
  Identity remains1.0/code7; the archived code7 AAB below does not contain this feature.

## 1.0 (versionCode 7) — Replacement public production candidate

- Replace the rejected code900000018: Play reported its excessively large jump.
  The maintainer confirms closed-testing 1.1.0 / code3 active. Code7 exceeds the
  normal local identities through code6; diagnostic AVD codes are excluded.
- Only release versionCode/documentation change; keep the same upload key,
  public name 1.0, product and narrowly scoped R8 reflection fix.
- Signed code7 AAB, source `5aeec74`, SHA-256:
  `d15bd9be44a622cf73926c32a1c9504fd41f1c8da3a0146a47cacac2362ee28e`.
  `artifacts/release-1.0-code7/OpenJump-1.0-code7.aab`.
- Build/gates, normal signature, bundletool and scoped SmallPhone API35 AVD
  upgrade 3→7/cold start/About PASS. Strict signature exit4 PKIX, NOT PASS.
  No agent upload or Play acceptance claim.
- New exact AAB/checksum/validation and manual upload steps:
  [current production handoff](docs/RELEASE_1.0.md).
- Same [eight-language release notes](docs/PLAY_RELEASE_NOTES_1.0.txt).

## 1.0 (versionCode 900000018) — REJECTED / superseded; do not upload

- Public Google Play naming is **OpenJump 1.0**; historical versions/tags and
  signed artifacts remain unchanged. Code 900000018 exceeds normal codes 1–6
  and the AVD-only diagnostic codes through 900000017 (never uploaded, recorded
  in VALIDATION.md). Confirm the greatest uploaded code in Play before upload.
- Offline video analysis of six jump protocols, athlete profiles, teams, history,
  progress/comparisons, manual backup and analytical exports; eight languages.
- Includes the integrated Encoder UI, security hardening and optimized avatar
  catalog. Encoder stays experimental, without physical-encoder accuracy claims.
- Release smoke exposed a blocking R8 issue in Encoder KLT initialization:
  BoofCV reflects two normalized pyramid convolution entry points. Preserve
  those exact methods; no broad keep, R8 disablement or new runtime logging.
- Release metadata, bounded public-name policy exception and this minimal R8 fix;
  no new feature, architecture, schema or UI change.
- Signed AAB SHA-256:
  `9f0d2bf2d79bb5b3e25465f862f6bbc7eb2395c4ff4fda25c4968ea6063e06cf`.
  Binary source `eb69796`, archived at
  `artifacts/release-1.0-code900000018/OpenJump-1.0-code900000018.aab`.
- Build/signature/bundletool/debug+release JVM gates and scoped minified AVD smoke
  PASS; strict jarsigner exits4 (self-signed/PKIX), not PASS. No Play upload.
  Public privacy policy accepted by maintainer, independent review omitted.
- Artifact, validation, signature and manual Play steps:
  [historical rejected-candidate handoff](docs/RELEASE_1.0_REJECTED_CODE900000018.md).
- Store release notes: [eight-language copy](docs/PLAY_RELEASE_NOTES_1.0.txt).

## 1.2.0-beta.3 (versionCode 6) — Historical development identity (no release tag or Play upload)

### Integrated development changes — October 1, 2026

`08029e0` is committed/pushed and fast-forward merged to `main`, including
`1a4bab5`. User-approved source integration, **not a release**.

- Guided Encoder reference/calibration/review flow with adaptive fine controls,
  precise enlargement and a fixed primary action; processed tracking preview
  paired atomically with its frame/source PTS and guarded across lifecycle/cancellation.
- Encoder results split into Summary / Repetitions / Analysis / Video, tap tabs
  without a global pager, shared repetition selection and lazy accessible MCV/PV/ROM rows.
- Primary temporal curves, progressive disclosure for trajectory/details/notes/context,
  critical warnings retained and fixed unsaved Save footer with original confirmations/errors.
- Canonical source-PTS video entry, latest-only UI intent, paused first-frame readiness,
  FIT capped viewport and explicit player disposal on leaving Video.
- Calculation, detection, calibration math, tracking engine, Room, dependencies and
  coordinator/mapper algorithms unchanged by the UI redesign.
- Latest development validation: **566 JVM** and four debug gates/diff PASS;
  **29/29 focal result AVD**, repeated native small/font150% and wide/font130%
  subsets **5/5 each**. Earlier preanalysis checkpoint: 553 JVM and 23/23 AVD.
  Not additive, not release/physical/accuracy qualification. Final independent
  correctness review remains incomplete after timeout/quota, not a technical PASS.
- Other recent `main` improvements: comparable athlete progress/personal records,
  compact profiles/avatars, guided bilateral/multi-jump series, adaptive camera HUD
  and team comparison/mode consistency fixes.

Reports: [reference/preanalysis](docs/ENCODER_REFERENCE_UX_VALIDATION.md),
[result UX](docs/ENCODER_RESULTS_UX_VALIDATION.md) and [validation](VALIDATION.md).

### Historical Motion-removal draft artifacts — September 27, 2026

The following signed local outputs predate the current source changes. They are
not builds of `08029e0` and cannot qualify or be promoted as the current UI.

Development identity `1.2.0-beta.3` / code `6` in `version.properties`,
after Motion Lab removal (not a release candidate; no tag `v1.2.0-beta.3`
exists, no AAB has been archived or promoted under this identity, nothing
authorizes a tag, release, or Play upload). Signed LOCAL outputs exist but are
ignored/not archived: `app/build/outputs/bundle/release/app-release.aab`
(18,126,519 bytes, SHA-256 `4b127fca69b0c119db9d440926c8e7bdaf055d2cfa39deb7ea5428a5be095618`) and `app/build/outputs/apk/release/app-release.apk`
(12,754,483 bytes, SHA-256 `ee4219a152d45483dabcb29e000b0d084f78017056dc4c6c614289ec8e144ed0`); that release was installed on the AVD at the historical checkpoint, not the latest debug validation.

- Draft `version.properties` is `1.2.0-beta.3` / code `6` so a NEW release
  APK/AAB can be built and compared with prior artifacts without reusing the
  blocked code 5. Nothing here authorizes a tag, release, or Play upload.

- App scope is Saltos (core) + Encoder by video V1 (experimental, no real
  physical-encoder validation and no accuracy claim; the single in-app notice
  remains in the Encoder setup).
- Motion Lab Body Tracking removed from the app: no screens, MediaPipe engine,
  pose model, Room tables, or usage instructions. The historical implementation
  is preserved only in the archive ref `origin/archive/motion-lab` (`fb1ba0e`).
- Room v10 (`openjump.db`, 15 tables for jumps, Encoder, athletes, groups and
  Testing); migration chain through v10 with no destructive migration.
- Manual backup contract `openjump-manual-backup` v1 emitted from Room 10,
  compatible with v1 backups from Room 9 and Room 10.
- Analytical export contract schema v2 for jumps and Encoder.
- The public Notion policy page must be synchronized with `PRIVACY.md` (dated
  September 27, 2026) before any new release; parity is not claimed here.
- Historical code6 local validation (Motion-removal snapshot, not current source qualification): 4 Gradle gates PASS with
  521/521 JVM, `lintDebug`/`lintRelease` 0 errors, `git diff --check` PASS,
  `assembleRelease`+`bundleRelease`+`lintRelease` R8/shrink PASS; AAB normal
  `jarsigner -verify` PASS but `-strict` exits 4 PKIX (NOT PASS); APK `apksigner`
  PASS with the same upload certificate as beta.2. AVD `emulator-5554`
  (`OpenJump_Pixel6_API35`, `qemu=1`): focused 53/53 and broad 92/92 (focused is
  a subset, NOT additive), broad excluded `TrajectoryVideoExporterInstrumentedTest`,
  zero skips; physical Pixel 9 enumerated, never tested. Minified release smoke on
  that AVD: Home/Jumps/Encoder setup notice/History/Settings/About; Saltos fixture
  `export_cfr_30.mp4` (12 frames @30fps) → Squat Jump 6.67cm/233ms → Save →
  Home/History/detail (History initially empty until Filters → Show results); SAF
  backup v1 Room 10 at `/sdcard/Download/openjump_backup_20260927T180823Z.ojbackup.json`
  (1575B: 1 athlete, 1 SJ assessment). Single-device SAF restore PASS on the same
  AVD (`emulator-5554`): second SJ marker (6:30 PM) added (two rows), then
  Settings→Data→Restore backup→SAF Downloads exact file→explicit Replace and
  restore; History/Home now exactly original SJ 6:05 PM (marker disappeared),
  detail metrics 6.67cm/233ms/1.14m/s, no app fatal; not a cross-device proof.
  Encoder followup only reached Calibrate 1/3 with synthetic 125-frame
  test-pattern after entering load 60/import; no valid full Encoder analysis/save
  or physical-encoder accuracy. Physical Pixel 9 never targeted. No Play/public-Notion parity.
- Size measured vs beta.2: code5 AAB 50,247,842 vs code6 18,126,519 (save
  32,121,323, 63.93%); code5 APK 71,533,234 vs code6 12,754,483 (save 58,778,751,
  82.17%). Historical model bytes 8,355,567 and JNI 20,965,701 are absent in code6;
  do not equate that category sum to net savings. Beta.2 AAB SHA
  `02299614b414737bb29779eaacc386c7264537d15e0239eaf79cf87f4e7428ad`. Play
  download size not measured.

## 1.2.0-beta.2 (versionCode 5) — signed internal-testing candidate (BLOCKED: do not upload or promote; not uploaded to Play)

**Tag:** annotated `v1.2.0-beta.2` at source commit `b10202dea7999ffdcaa863dab9a1b9d119fc3d8d`

**AAB:** `artifacts/pre-release-v1.2.0-beta.2/OpenJump-1.2.0-beta.2-code5.aab`

**AAB SHA-256:** `02299614b414737bb29779eaacc386c7264537d15e0239eaf79cf87f4e7428ad` (do not rebuild before upload)

Changes since `v1.2.0-beta.1` (integrated on `main`):

- Persisted Motion Lab measurements in Room (v11) and removed body orientation in Room v12 while retaining measurements, samples and PTS; current formats are manual backup v3 and analytical export schema v4 (v1/v2 backups remain restorable with the orientation field dropped).
- Added a bounded Motion recent-activity feed and compact history rows; full angle ranges remain in the measurement detail.
- Stabilization fixes: preserve finalizing camera takes and break exported trajectory paths at PTS/index gaps; repair Motion video replacement, JSON partial-document cleanup and privacy confirmation; guard unsaved exits and selector lifecycle/navigation; announce the Motion angle scale low-to-high for TalkBack.

**Data considerations:** Room stays at v12; migration 11→12 deliberately drops legacy body orientation while retaining measurements, samples and PTS. Processing remains offline. Saved Motion rows contain no video or URI; camera recordings are separately published to shared `Movies/OpenJump`. Backup/export/share are user-initiated and may leave the device through the selected provider.

**Known limitations:** Motion Lab is experimental and not clinically validated. The signed AAB and exact source tag are verified locally; no Play upload or physical-device validation is claimed. Normal `jarsigner -verify` passes but `-strict` exits 4 for the self-signed certificate's PKIX trust warning; strict jarsigner is not a PASS. Supported HLG / PTS-offset exporter paths are not validated in this beta (phase-1 HLG and PTS-offset failures reproduced on baseline `bfd3de5`; see `VALIDATION.md`). Invalid-video FrameSelector has no inline recovery; Back to select another video is required. Both limitations were accepted for this internal beta.2 scope; they are not claims of exporter support or validation. Synthetic zero-pose import exercises save/history plumbing only and does not prove pose accuracy, cross-device restore, or near-64MiB / long-video resource safety. The public Notion policy was manually updated September 25, 2026 with the 8-language text from `PRIVACY.md`.

## 1.2.0-beta.1 (versionCode 4)

**Tag:** `v1.2.0-beta.1`

**AAB SHA-256:** `83ea0af988c9721f1c9fa353248c9acb122d9c56b1d101f039fea8c7408879c5`

Changes since `v1.1.0`:

- Added experimental offline Motion Lab Body Tracking for reviewing pose estimates from imported video.
- Added a bounded preview of analyzed samples during processing, with PTS-based play/step/scrub controls.

**Data considerations:** pose processing and preview remain on-device and offline; no Room schema or account/sync behavior is added. Preview contains only analyzed samples and is bounded to a rolling window.

**Known limitations:** Motion Lab is experimental and not clinically validated. The analyzing preview is a low-cadence window of up to 48 analyzed samples, not continuous playback of the full video.

## 1.1.0 (versionCode 3)

**Tag:** `v1.1.0`

**AAB SHA-256:** `b69b5965c416af19f73289657bafb6743add5abeb88ecbbb605f98818673558`

Changes since `v1.0.0`:

- Added manually restorable Room backups with strict validation and rollback-safe replacement.
- Added streaming exports and release-size improvements.
- Added a localized About screen with offline GPL text, canonical privacy and project links, and privacy-safe diagnostics.
- Added custom unit preferences while retaining metric canonical storage.
- Added Brazilian Portuguese, European Portuguese, Italian and Turkish translations.
- Refined the dark-mode color hierarchy, navigation states, timeline data colors and jump-flow progress visuals.
- Improved dark-mode illustration presentation while preserving the original artwork.

**Data considerations:** existing Room data is preserved; backup/restore and
export behavior remain local and offline. No new Room schema migration is
introduced by this release.

**Known limitations:** OpenJump remains a technical proof of concept and is not
clinically validated. A consumed Play `versionCode` cannot be reused, and users
cannot be downgraded to an older Android artifact.

## 1.1.0-beta.1 (versionCode 2)

**Tag:** `v1.1.0-beta.1`

**AAB SHA-256:** `fda2edac2edf044f3369f0fa0dccde45087dcbb0dc77e247589f237fea983268`

Verified changes since `v1.0.0`:

- Added a manually restorable Room backup with strict validation and rollback-safe replacement.
- Added streaming exports and release-size improvements.
- Added a localized About screen with offline GPL text, canonical privacy and project links, and privacy-safe diagnostics.
- Added custom unit preferences while retaining metric canonical storage.

**Data considerations:** existing Room data is preserved; backup/restore and
export behavior remain local and offline. Review the migration and backup notes
in `README.md` before release.

**Known limitations:** OpenJump remains a technical proof of concept and is not
clinically validated. A consumed Play `versionCode` cannot be reused, and users
cannot be downgraded to an older Android artifact.

## 1.0.0 (versionCode 1)

**Tag:** `v1.0.0`

**AAB SHA-256:** `6bb880870a4471c9204eeabca11a75d2b807c53ad42dc14daa230280d55cc9c8`

First public release.
