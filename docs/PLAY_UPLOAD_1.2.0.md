> **SUPERSEDED by 1.2.2 / code 10.** Play already advertises 1.2.1 and the prior
> handoff used versionCode 9. Use `PLAY_UPLOAD_1.2.2.md`; do not upload this
> 1.2.0/code9 checkpoint.

# Google Play upload handoff — OpenJump 1.2.0 (code 9)

Status: **blocked on credentials**. The release source/tag and unsigned AAB are
ready; the upload key and Play API/Fastlane credentials are not present on the
release machine. Do not upload the unsigned AAB.

## Exact source and artifact

- Public tag: `v1.2.0` → `42e59af662cf9fbc59c9b593c72425a605553281`
- Unsigned AAB (local/CI evidence only):
  `app/build/outputs/bundle/release/app-release.aab`
- Size 19,307,400 bytes; SHA-256
  `0d5ac2c8b1aa1ba499018ffbc86479843f3b2755230d94365cbd46615eb456a2`
- Application ID `com.openjump.app`, versionName `1.2.0`, versionCode `9`,
  minSdk 26, target/compileSdk 36.

## Required credentials (never commit)

Provide all four as Gradle project properties, for example through
`ORG_GRADLE_PROJECT_*` environment variables or a protected local
`~/.gradle/gradle.properties`:

```text
OPENJUMP_UPLOAD_STORE_FILE
OPENJUMP_UPLOAD_STORE_PASSWORD
OPENJUMP_UPLOAD_KEY_ALIAS
OPENJUMP_UPLOAD_KEY_PASSWORD
```

For API/Fastlane upload, provide the Play service-account JSON and access to the
`com.openjump.app` Play Console listing. Do not reuse the F-Droid key.

## Build and verify the signed AAB

From a clean checkout of `42e59af`:

```bash
export ORG_GRADLE_PROJECT_OPENJUMP_UPLOAD_STORE_FILE="/secure/path/openjump-upload.jks"
export ORG_GRADLE_PROJECT_OPENJUMP_UPLOAD_STORE_PASSWORD="..."
export ORG_GRADLE_PROJECT_OPENJUMP_UPLOAD_KEY_ALIAS="..."
export ORG_GRADLE_PROJECT_OPENJUMP_UPLOAD_KEY_PASSWORD="..."

OPENJUMP_REQUIRE_SIGNED_RELEASE=1 ./tools/validate.sh release
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
jarsigner -verify -strict app/build/outputs/bundle/release/app-release.aab
```

Record the signed AAB SHA-256 and the upload certificate fingerprint. Compare it
with the upload certificate registered in Play Console; do not compare it with
the Play App Signing certificate shown to users. Strict `jarsigner` can report a
PKIX/self-signed trust warning even for a valid upload signature; report it
honestly and do not regenerate the key.

## Upload

1. Confirm versionCode `9` is unused in every Play track/draft. If any track
   reports a conflict, stop and choose a new higher code; do not reuse a consumed
   code.
2. Upload the exact signed AAB, without rebuilding, to the production track.
3. Use the eight-locale notes in
   [`PLAY_RELEASE_NOTES_1.2.0.txt`](PLAY_RELEASE_NOTES_1.2.0.txt).
4. Complete the Play review/submission flow. A submitted release pending review
   is not "available on Play" until the Console/listing shows it.
5. Record the final signed AAB SHA-256, Play release name, review state and
   publication date in the release report.

## What must not be done

- Do not upload the unsigned AAB or the debug-key APK.
- Do not change the application ID, versionCode 9 or the signing identity.
- Do not hand the Play key to F-Droid or upload F-Droid's APK to Play.
- Do not claim 1.2.0 is published before Play accepts it.
