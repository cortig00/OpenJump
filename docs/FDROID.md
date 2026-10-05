# Official F-Droid preparation

OpenJump uses a **single application and release variant**, not an `fdroid` flavor.
Its identity stays `com.openjump.app`, **1.0 / versionCode 7**. The Google Play AAB
and upload key are not modified or rebuilt by this work. See the measured
[validation results](FDROID_VALIDATION.md) and [submission package](FDROID_SUBMISSION.md).
Local validation is complete; official F-Droid inclusion and GitLab CI remain pending.

## Audited eligibility

- Project license: GPL-3.0-or-later; dependency licenses retained. See
  [third-party notices](../THIRD_PARTY_NOTICES.md), [asset provenance](../ASSET_LICENSES.md)
  and [resolved dependency audit](DEPENDENCY_AUDIT.tsv).
- No Play Services, Firebase, advertising, automatic tracking, analytics backend,
  MediaPipe, BlazePose or ML models in this source. Historic Motion Lab is not part
  of this public repository or this build.
- Dependencies resolve from Google Maven, Maven Central and the Gradle plugin
  portal. Their concrete POM licenses were inspected, including transitive
  dependencies and build-only tools. AndroidX native code has public source and
  Apache/BSD licenses; it is not proprietary merely because it is precompiled.
- The only checked-in JAR is the Gradle wrapper. F-Droid automatically removes it
  and invokes its own checksum-checked Gradle launcher. No `scanignore`, vendored
  runtime binary, non-free source replacement or proprietary compiler is required.
- The actual merged release requests CAMERA, WRITE_EXTERNAL_STORAGE limited to
  API <=28, ACCESS_NETWORK_STATE, WAKE_LOCK and the app's signature-level dynamic-
  receiver permission. The two normal network-state/wake permissions come from
  media integration; they do not grant Internet access or introduce a backend.
  INTERNET is explicitly removed. No microphone, location or broad media-read permission.
- Analysis, Room history and calculation are local. Optional browser links to
  source/issues/privacy/donations/DOI and user-triggered sharing/email are not an
  in-app backend. The complete privacy information is available offline.
- The donation logo was replaced with a generic original pictogram because the
  brand kit did not identify a FLOSS license. The donation feature is preserved.
- No Anti-Feature is proposed for this preparation. F-Droid maintainers make the
  final inclusion and labeling decision; a scanner pass is not legal certification
  or a guarantee against every vulnerability.

## Store metadata

`fastlane/metadata/android/` is the single reusable listing source for Play/F-Droid:

- `en-US`, `es-ES`, `de-DE`, `fr-FR`, `it-IT`, `pt-BR`, `pt-PT`, `tr-TR`;
- localized name, short/full descriptions and `changelogs/7.txt`;
- the existing eight-language Play release notes, reused verbatim;
- one common 512x512 launcher icon in the default `en-US` listing;
- two existing protocol screenshots each in English, Spanish, German and French.
  Other locales use the default graphics, not falsely labeled translated images.
  There are no real athlete records or identifiable video frames in these images.

No Fastlane/Ruby plugin or Play Publisher SDK is required. Validate with:

```bash
python tools/check_store_metadata.py
```

Do not upload F-Droid-specific channel warnings to Play automatically.

## Validate the recipe with fdroidserver

Use the current official `fdroiddata` checkout, including its `config/` directory:
its category definitions are needed by the linter. Copy only
`metadata/com.openjump.app.yml` from the preparation branch into that checkout.
Do **not** copy screenshots or Fastlane folders into fdroiddata.

The environment used for the local build is the official image
`registry.gitlab.com/fdroid/fdroidserver:buildserver`, with Debian OpenJDK 21,
Android platform 36 and build-tools 35.0.0. fdroidserver is installed from its
public source. The SDK installer and Gradle launcher are the image's tools.
No host Gradle signing configuration, private SDK mirror or keys are mounted.

```bash
fdroid rewritemeta com.openjump.app
fdroid lint com.openjump.app
fdroid scanner --refresh --exit-code --json com.openjump.app:7
fdroid build --verbose --scan-binary --refresh-scanner --stop com.openjump.app:7
```

The build produces `unsigned/com.openjump.app_7.apk` and a source tarball. It must
remain unsigned: **F-Droid's operators sign the official distribution**. This is
not a self-hosted F-Droid repository and no production F-Droid key is created here.
Do not use `--force`, `--skip-scan` or `novcheck` to hide failures.

## Version provenance and first inclusion

The stable `v1.0` tag identifies the original public Play source snapshot
`5c47e887dc06b294f9b9a1f6458df6b670f854aa` (1.0 / code7). The original Play binary
stays immutable. The first F-Droid recipe pins the later preparation source commit
which adds licenses, store metadata and the generic donation icon, still code7.
It does not pretend to be byte-identical to the previously signed Play APK.

The pinned preparation source is
`8cd1fea192bdd5e984e5bd5a74f8f6463129912f` on the public `fdroid/inclusion` branch.
Keep this commit publicly reachable. The recipe's full source hash is intentionally
a separate earlier source commit:
a recipe cannot embed the hash of the commit containing itself. Never replace a
published stable tag or reuse a version code for a subsequent Play/F-Droid update.

## Signing and changing channel

The maintainer accepted F-Droid's independent signing key for initial inclusion.
With the same application ID but different certificates, Play and F-Droid builds
**cannot update each other or coexist** as separate installations.

To switch: create a manual backup, keep the backup and videos safely outside the
app, uninstall the old channel, install the other channel and restore the backup.
Uninstall deletes the private database. Manual backups exclude video files, video
URI permissions, preferences and unsaved analyses; restoring measurements does
not restore video links. Existing videos in shared storage must be retained or
imported again where supported. Updates within one channel preserve its normal
Android signing/update behavior.

Do not request, expose or reuse the Google Play upload keystore. That certificate
is not necessarily the Play App Signing certificate.

## Reproducible builds

Initial inclusion uses ordinary F-Droid signing; `Binaries` and
`AllowedAPKSigningKeys` are deliberately absent. Reproducible builds are not an
inclusion requirement. Java/Kotlin, fixed tool versions and Maven-provided native
libraries make a future investigation reasonable, but reproducibility must be
measured, not inferred from matching source. Two local builds of the exact pinned
source produced identical unsigned APK hashes; same-container/cache repeatability
is not independent reproducibility or equivalence to the signed Play artifact.

Potential differences include AGP's version-control metadata, R8/toolchain versions,
ZIP/alignment/ordering and signing metadata. The public source snapshot and original
Play source revision are different Git commits. A Play-delivered APK also uses the
Play App Signing certificate, not necessarily the local upload certificate.
Switching existing F-Droid users to another certificate later needs migration
planning; independent signing is a deliberate first-release decision.

## Future simultaneous releases

1. Merge this preparation into the main development/publication base; keep one
   release variant and the license/metadata files. Preserve the recipe's pinned
   source commit when merging (fast-forward/history-preserving merge, not an
   orphaning squash-and-delete); don't leave future releases based on the old tree.
2. Increase `VERSION_CODE` monotonically, update `VERSION_NAME` and add
   `changelogs/<new-code>.txt` in all eight locales. Stable names after 1.0 currently
   follow the existing MAJOR.MINOR.PATCH validation. Choose a name/tag not already
   used in any release repository, including historical development tags.
3. Run metadata checks, normal build/lint/tests and the F-Droid scanner. Commit the
   exact release source and metadata together.
4. Tag that commit `v<versionName>`. Publish the existing Play-signed
   AAB manually and prepare the matching GitHub Release. Do not move tags afterward.
5. F-Droid detects stable tags using `UpdateCheckData` in `version.properties` and
   generates a new build block. Beta/alpha tags do not match the stable-tag filter.
   New versions do not need an ordinary manual fdroiddata MR unless recipe changes
   are needed. F-Droid approval/build/publication timing is independent of Play.

There is **no automatic coupling to Play Console approval**. If F-Droid must never
start before Play is live, publish the stable tag only after Play's acceptance,
while keeping the exact source commit fixed. A release tag is what the updater
watches, not the existence or draft status of a GitHub Release.
