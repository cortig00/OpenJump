# iPhone device testing with the unsigned IPA (practical stages)

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
