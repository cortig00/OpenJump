# OpenJump 1.3 / code 12 — F-Droid source handoff

- Package: `com.openjump.app`. Version metadata comes from `version.properties`.
- This source candidate includes 14 localized `changelogs/12.txt` files under
  `fastlane/metadata/android/`. Existing descriptions, icon and screenshots are
  unchanged; the six additional locales have a changelog only.
- The [official F-Droid catalog](https://f-droid.org/api/v1/packages/com.openjump.app)
  listed 1.2.2/code 10 on 2026-11-19. The
  [official recipe](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/com.openjump.app.yml)
  uses `AutoUpdateMode: Version` and `UpdateCheckMode: Tags` with a stable-tag
  pattern that matches `v1.3`. It also reads the code/name from
  `version.properties`. A separate recipe MR is not expected for an ordinary
  update with the same build settings. The recipe in `metadata/` is historical.
- Code 12 supersedes the unpublished code-11 candidate. The app still prefers
  portrait in its main activity, but the manifest no longer requires portrait
  hardware for installation.
- No `v1.3` tag or GitHub Release was created by this preparation. Before
  authorizing a stable tag, finish source review, Android and F-Droid validation,
  and check that the tag will point at the reviewed commit with store notes
  already present. Do not move any published tags. F-Droid runs its own build and
  signs its own APK. Never supply the Play AAB or its upload key to F-Droid.
- Review the [1.3 privacy change](PRIVACY_1.3.md) for saved angle images and manual
  backups, and synchronize the public privacy page and store disclosures as
  applicable. This source handoff does not show Play submission or approval.

Local source checks: `python tools/version_policy.py`,
`python tools/check_store_metadata.py`, `python tools/check_license.py`, and
`git diff --check`. A source/metadata check is not an F-Droid build or approval.
