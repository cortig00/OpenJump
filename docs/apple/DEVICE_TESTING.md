# iPhone device testing with the unsigned IPA (practical stages)

## Current manual-camera candidate — 2026-10-09

The user requested a NEW IPA to test on their iPhone. Use the standalone
`apple-device-experimental.yml` route on `dev/apple`, not the disabled `device`
job below. No TestFlight/App Store publication, signing credentials, or physical
automation is authorized. The historical build-3 IPA stays unchanged.

- Current source includes camera capture, transactional Use, and SQLite 3→4
  preservation. No video/URI persistence, microphone recording, or gallery save.
- Prior source `51b56f6`: 190/190 unit PASS, 6/7 UI PASS. Final source `b929e1d`
  changes Save-button grouping; it compiles, but simulator boot prevented XCTest.
  The new IPA is for manual testing, NOT a fully qualified release.
- Version/build remain **0.0.3 (3)** and App ID `org.openjump.apple`; distinguish
  this new candidate using its manifest source SHA and SHA256, not version alone.
  Save it in a new run-specific directory; never overwrite the previous IPA.
- CI builds ARM64 for iOS 16+ and packages an **unsigned** IPA. Install using the
  user's own trusted signing/sideload setup and the same account/App ID as before,
  updating without uninstalling if possible. Data retention is not guaranteed
  if the signing tool rewrites app identity. No passwords/keys are requested.
- Back up the iPhone; use a disposable profile and non-sensitive clip. Do not
  delete existing data. JSON export is NOT a supported restoration mechanism.
- Manual checks: record→Stop→review→Use→exact frame marks→calculate→Save→History;
  Repeat and Cancel; interrupt/background during recording and finalizing;
  preserve old analysis when a new capture is cancelled or fails; verify Settings
  export preview counts and that Save is reachable. Do not transmit private media.
- Return model/iOS, manifest source SHA, IPA hash, and individual PASS/FAIL/SKIP.
  Physical smoke does not replace pending simulator gates or establish accuracy.

The sections below describe the historical route/snapshot, not current coverage.

Scope: this note covers only the unsigned developer artifact produced by the
`device` job in `.github/workflows/apple-prototype.yml`. It is not a signed
release, not a TestFlight upload, not an App Store qualification, and not a
clinical validation. Authored native tests are 45 unit plus one UI method (46
cases); simulator PASS does not prove physical-device behavior.

## 1. What the artifact is (and is not)

- The CI `device` job builds Release for `generic/platform=iOS` (ARM64,
  deployment target 16.0 inherited from the project, no override) with
  `CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO`, then validates the real
  app binary (Info/Mach-O platform IOS, ARM64, minimum 16.0, no encryption, no
  `LC_CODE_SIGNATURE`, no `_CodeSignature`, no `embedded.mobileprovision`,
  no symlinks or nested Mach-O payloads). The binary must carry explicit
  `LC_BUILD_VERSION` platform IOS(2) and exactly minimum 16.0.0; plist
  `DTPlatformName`/supported platforms must also declare iPhoneOS. The job zips `Payload/OpenJumpApple.app` into an **unsigned** IPA.
- The artifact contains exactly three files: the unsigned IPA, `manifest.json`
  (basename, SHA256, source SHA, bundle id, min OS, arch, platform,
  signing `none`), and the `.sha256` file. It is public to repository readers
  once uploaded, but it is **NOT installable as-is**: iOS refuses unsigned
  app bundles before any user-side step.
- Current binary status: no device IPA has been produced yet from this source
  slice. A binary exists only after the new SHA runs the hosted `device` job
  green; this snapshot is source-only until that CI completes.

## 2. Supported target, untested device

- The deployment target is iOS 16.0, so an iPhone XR-class device on iOS 16 is
  the supported floor. **Physical test status: NOT_RUN.** No physical iPhone
  has executed this snapshot; simulator-26 PASS (prior runs) does not qualify
  iOS 16 runtime or any physical device.

## 3. Route to a personal device (user-performed, outside this repo)

No paid Apple Developer membership is required for the own-device free path;
that is distinct from TestFlight/App Store distribution, which is paid and is
explicitly out of scope here. All signing steps happen on the user's own
machine and Apple account; never share passwords, App Store credentials, or
signing keys with anyone, and this project never collects them.

1. Download the three artifact files from the green `device` CI run and verify
   the SHA256 (`manifest.json` source SHA must match the commit tested).
2. On the user's own Mac/PC, apply their own local signing to a copy of the
   app (for example with their free Apple Account in Xcode, or a community
   sideloading tool they already trust). A free-account signature typically
   expires after about 7 days and needs re-provisioning; that renewal is the
   user's own action.
3. One Windows candidate some testers use is AltStore Classic; see its
   official site for the current instructions. This repo gives no automatic
   installer, no password guide, and no prompt to collect credentials.

## 4. Safe smoke checklist (new test profile, no deletions)

- Back up the iPhone first. Use a **new test athlete profile**, never a real
  athlete record; do not delete anything to "clean up" after testing.
- Do not use personal or sensitive media. Import only a disposable clip via
  Files/Photos, then check: frame stepping, PTS readout, confirmation,
  history entry, reopen, cancel, background/foreground, locale/units display,
  and avatar rendering.
- Camera capture is **not implemented** in this snapshot, so do not expect an
  in-app recording path.
- Exact timestamps plus the user's real-time declaration do not prove
  physical-clock or clinical accuracy; no timing-accuracy claim is made here.

## 5. What to record back

Device model, iOS version, artifact/CI run id, manifest source SHA and SHA256,
resigned-vs-unsigned state, and each smoke item PASS/FAIL/SKIP. Keep the report
factual: an unsigned-artifact download is not an install PASS, and a single
device smoke pass is not a release qualification.
