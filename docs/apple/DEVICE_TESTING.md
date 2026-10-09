# iPhone device testing with the unsigned IPA (practical stages)

## Current permission-lifetime repair candidate — 2026-10-09

The user confirmed their usual device is **iPhone XR with iOS 16** and reported
that the camera permission dialog appeared but the camera did not open. Do not
ask them for the device model/base OS again. iOS **16.0 remains the minimum**,
not a hardware allowlist or a claim that SDK-26 builds prove iOS-16 behaviour.
The user explicitly approved the bounded correction and a NEW manual-test IPA.
Use the standalone `apple-device-experimental.yml` route on `dev/apple`, not the
disabled `device` job below. No TestFlight/App Store publication, signing
credentials, physical automation, uninstall, data clear, or permission reset.
Both previously delivered IPAs remain unchanged.

- Functional repair source `e56dd7f`: transient foreground `.inactive` no longer
  invalidates the originating camera permission ticket; actual background,
  route exit, and tab hiding still invalidate. Camera preparation and recording
  are separate explicit taps: **Open camera → preview ready → Record**. Grant,
  resume, or ready must never start recording automatically.
- Engine, leases, analysis/PTS/formulas, transactional Use, SQLite schema,
  deployment target, version, and identity are unchanged. No video/URI
  persistence, microphone recording, or gallery save.
- Three additive regressions bring authored XCTest to **200 (193 unit + 7 UI)**.
  The device-only route does NOT execute XCTest. Native suite qualification is
  tracked separately; do not infer test PASS from a green device package job.
  Prior `51b56f6` had 190 unit PASS and 6/7 UI PASS; later Save-row compilation
  passed but simulator readiness prevented execution. This IPA is experimental,
  not a qualified release; physical acceptance of this repair is still pending.
- Version/build remain **0.0.3 (3)** and App ID `org.openjump.apple`; distinguish
  this candidate using manifest source SHA, run and SHA256, not version alone.
  Save it in a new run-specific directory; never overwrite previous IPAs.
- CI builds ARM64 with minimum iOS 16.0 and packages an **unsigned** IPA. Install
  using the user's own trusted signing/sideload setup and the same account/App ID
  as before, updating without uninstalling if possible. Data retention is not
  guaranteed if the resigner rewrites identity. No passwords/keys are requested.
- Back up the iPhone; use a disposable profile and non-sensitive clip. Do not
  delete existing data. JSON export is NOT a supported restoration mechanism.
- First check: **Open camera**; if a permission dialog appears, accept only if
  desired and expect the live preview/ready state. Existing authorization should
  also open directly. Then explicitly **Record → Stop → review → Use → exact
  frame marks → calculate → Save → History**. No reinstall/reset is needed to
  force a fresh permission dialog; denial must show a recoverable status.
- Check Repeat/Cancel and preservation of the old analysis on cancellation or
  error; real background/tab/back must stop/park safely without automatic record
  on return. Check Settings JSON preview counts and reachable Save. Interruption,
  VoiceOver/iPad and hardware/codec details remain separate pending checks.
- Return candidate run/hash and per-step PASS/FAIL/SKIP; a screenshot of the
  status text is sufficient for an opening failure. Do not send private media.
  Physical smoke does not replace simulator gates or establish accuracy.

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
