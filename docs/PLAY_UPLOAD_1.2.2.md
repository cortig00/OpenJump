# Google Play upload handoff — OpenJump 1.2.2 (code 10)

Status: **blocked on credentials**. Do not upload the unsigned AAB.

Google Play already advertises 1.2.1 (prior handoff: versionCode 9), so this
release uses **1.2.2 / versionCode 10**. Confirm code 10 is unused in every Play
track/draft before uploading. If it is consumed, stop and choose a higher code.
The independent review also requires this confirmation before any Play upload.

## Build and verify

Provide the four `OPENJUMP_UPLOAD_*` Gradle properties (keystore path, store/key
passwords and alias) through a protected local file or `ORG_GRADLE_PROJECT_*`
environment variables. Never commit them. Then, from a clean checkout of the
published `v1.2.2` source:

```bash
OPENJUMP_REQUIRE_SIGNED_RELEASE=1 ./tools/validate.sh release
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
jarsigner -verify -strict app/build/outputs/bundle/release/app-release.aab
```

Record the signed AAB SHA-256 and verify the upload certificate fingerprint
against Play Console. Do not compare it with the Play App Signing certificate.
Strict `jarsigner` may report a PKIX/self-signed trust warning for a valid
upload signature; report it honestly and do not regenerate the key.

## Upload

Upload the exact signed AAB to the **production** track, use the eight-locale
notes in `PLAY_RELEASE_NOTES_1.2.2.txt`, complete the review/submission flow,
and record the Play release name, review state and publication date. A submitted
release pending review is not "available on Play" until the listing shows it.

Do not upload the unsigned AAB or the debug-key APK. Do not reuse the F-Droid
key. Do not claim 1.2.2 is published before Play accepts it.
