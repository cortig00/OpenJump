# Submit OpenJump to official F-Droid

Status: **prepared and locally validated, not submitted or accepted**. The
unsigned APK is evidence, not an official release to upload. GitHub and GitLab
are separate accounts; GitHub access does not authorize a GitLab submission.

## Exact contribution

Copy **only** [`metadata/com.openjump.app.yml`](../metadata/com.openjump.app.yml)
into a current fork of [fdroiddata](https://gitlab.com/fdroid/fdroiddata).
The MR must not add this documentation, APKs, source tarballs, dependency reports,
Fastlane texts, icons or screenshots. F-Droid imports graphics/text from the pinned
upstream source; the real fdroidserver importer was checked for all eight locales.

Suggested branch: `com.openjump.app`. Commit/MR title: **New app: OpenJump**.
The fork must be public and the branch unprotected. Do not modify existing apps.

1. Check for an existing OpenJump RFP/packaging issue and reference it if found.
2. Create a public GitLab fork of `fdroid/fdroiddata` (not a self-hosted app repo).
3. Create the branch and add the one YAML file, with LF endings.
4. Run `fdroid lint com.openjump.app` and the fork's actual GitLab pipeline.
   Local build results below do **not** mean that CI already passed.
5. Open an MR to `fdroid/fdroiddata`, normally its `master` branch, using the
   **App inclusion** template and the text below. Follow the current template if
   it has changed. Leave CI-related boxes unchecked until the pipeline runs.
6. Answer reviewer questions and wait for official build/signing/publication.

If GitLab asks for payment/credit-card/phone verification to enable shared CI,
do not provide payment information merely to run F-Droid CI. Note it in the MR
so maintainers can trigger their FOSS runners, as their template instructs.

Local alternative (substitute your own fork URL; no credentials in commands):

```bash
git clone https://gitlab.com/YOUR_USERNAME/fdroiddata.git
cd fdroiddata
git switch -c com.openjump.app
# Copy OpenJump's metadata/com.openjump.app.yml into this checkout's metadata/.
fdroid rewritemeta com.openjump.app
fdroid lint com.openjump.app
fdroid scanner --refresh --exit-code --json com.openjump.app:7
fdroid build --verbose --scan-binary --refresh-scanner --stop com.openjump.app:7
git add -- metadata/com.openjump.app.yml
git diff --cached --check
git diff --cached --stat  # exactly one new app metadata file
git commit -m "New app: OpenJump"
git push -u origin com.openjump.app
```

Use the checkout's official `config/` categories and a supported Linux build
environment. Do not mount your Play keystore or user Gradle signing properties.

## Ready-to-paste MR description

The checklist below records source/local preparation, not an already-run GitLab
pipeline. Recheck it against the template when submitting.

---

## New app: OpenJump

Offline Android video analysis for jumps and athletic performance. Six jump
protocols, local athlete/history storage, user-triggered export/manual backup and
experimental video-based velocity tracking. Not clinically validated.

- Application ID: `com.openjump.app`.
- Version: **1.0 / versionCode 7**; minSdk 26, targetSdk 36.
- License: **GPL-3.0-or-later**.
- Source: https://github.com/cortig00/OpenJump
- Issue tracker: https://github.com/cortig00/OpenJump/issues
- Contact: `openjump.app@gmail.com`; upstream maintainer: `cortig00`.
- Original Play-source tag: `v1.0`, commit
  `5c47e887dc06b294f9b9a1f6458df6b670f854aa`.
- Build source: `8cd1fea192bdd5e984e5bd5a74f8f6463129912f`, publicly reachable on
  `fdroid/inclusion`; full hash pinned in the recipe. This later source adds
  licenses, Fastlane metadata and an original generic coffee pictogram. Same
  app logic, build configuration and code7; the Play release is not replaced.
- Eight upstream Fastlane locales: en-US, es-ES, de-DE, fr-FR, it-IT, pt-BR,
  pt-PT, tr-TR. Default icon; two protocol screenshots each for EN/ES/DE/FR,
  other locales use default graphics. No real athlete records/video frames.
- No Anti-Features proposed. No Play Services/Firebase/proprietary SDK/ML model,
  ads or analytics/backend. INTERNET is removed from the merged manifest.
  Optional source/donation/DOI/browser links and user-initiated sharing are not
  required for the offline features. Maintainers decide the final labeling.
- Runtime/build dependency licenses, assets and native sources audited:
  [notices](https://github.com/cortig00/OpenJump/blob/fdroid/inclusion/THIRD_PARTY_NOTICES.md),
  [assets](https://github.com/cortig00/OpenJump/blob/fdroid/inclusion/ASSET_LICENSES.md),
  [validation](https://github.com/cortig00/OpenJump/blob/fdroid/inclusion/docs/FDROID_VALIDATION.md).
- Regular stable-tag auto-updates enabled; version extraction from
  `version.properties`. Initial build intentionally uses the later preparation
  commit instead of moving the immutable original Play tag.

### Signing / reproducibility exception

Please use normal F-Droid signing. The maintainer explicitly accepts a different
certificate for the same application ID. Switching Play/F-Droid requires manual
backup, uninstall and restore; installations cannot update across certificates.
No Play upload/App Signing key is supplied or requested.

The already-published Play artifact remains immutable. This first F-Droid source
adds license assets and replaces a donation-service logo with original artwork;
it is not claimed to match that signed Play artifact. Two local builds of the
pinned source were byte-identical, but both ran in the same container with cache
reuse, not independent clean-room/Play-signature reproducibility. `Binaries` and
`AllowedAPKSigningKeys` are deliberately absent. We are not deferring a required
signature check or claiming that changing the signing certificate later is free
of migration consequences.

### ABI split exception

The unsigned universal APK is 12,800,626 bytes. All four ABIs' native libraries
together are just 202,140 compressed bytes. Splitting would save less than
0.2 MiB per APK, so no ABI/version-code recipe complexity is introduced.

### Local validation (not GitLab CI)

Official `fdroidserver:buildserver` container; fdroidserver source revision
`c21c177ff6d813697aaf9c988ca9fbb2b571b468`; OpenJDK21, platform36,
build-tools35.0.0, AGP8.9.2, Gradle8.11.1. No host signing configuration/keys.

- `fdroid rewritemeta`, `fdroid lint`: PASS with official categories.
- Source scanner: zero warnings/errors; default wrapper cleanup only.
- Real `fdroid build --scan-binary`: PASS from the exact public source hash;
  binary class/signing-block scanner PASS, no scan suppression.
- `fdroid checkupdates`: PASS, finds 1.0 / code7 via `v1.0`.
- Repeated local build: same APK SHA-256
  `7b6954b1be5da52114e896023e745f7e773e0c49d9044610476e3c946c78ef30`.
- All 13 DEX/native entries equal the original-source Linux baseline build;
  license assets present. APK intentionally unsigned.
- Real fdroidserver text/image importer: eight locales and expected graphics PASS.
- App debug and AndroidTest packaging, debug/release lint and diff checks PASS.
  JVM/test source tasks are NO-SOURCE in this minimal public distribution; no
  instrumented suite or independent accuracy qualification is claimed.
- Focal debug AVD smoke: launch, About 1.0(7), generic donation icon and offline GPL
  screen; no app FATAL/ANR. No personal device touched.

### Checklist

- [x] Audited against inclusion policy; GPL source and freely licensed dependencies.
- [x] Upstream maintainer supports this inclusion and independent F-Droid signing.
- [x] Upstream Fastlane metadata includes en-US plus seven other locales.
- [x] Read contribution guide, metadata reference and quick-start guide.
- [x] Exactly one `metadata/com.openjump.app.yml` file, valid YAML and LF.
- [x] Author/contact/issue tracker included; full build commit hash pinned.
- [x] Stable tags and automatic updates enabled.
- [x] Only latest version included; no disabled versions, srclibs or external subrepos.
- [x] Reproducibility/signing and ABI-split exceptions explained above.
- [ ] Fork confirmed public and submission branch unprotected.
- [ ] Existing RFP/packaging issues checked and referenced if any.
- [ ] Actual GitLab pipeline passed; Reports warnings fixed or explained.

---

## Upstream release state

Keep `v1.0` immutable at the original Play source. The GitHub Release can be
reviewed/published separately from F-Droid approval; it must not advertise an
accepted F-Droid download yet. The preparation branch remains separate from main.
Before future releases, integrate its files without orphaning the pinned source
commit (prefer fast-forward or a merge retaining history; do not discard/squash
and delete its only reachable branch while the first recipe pins that commit).
Future versions must increase code and tag the exact shared release source.
