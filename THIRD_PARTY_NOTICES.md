# Third-party notices

OpenJump is GPL-3.0-or-later. Dependencies retain their own licenses; including
Google-hosted software does not mean including proprietary Google services.

The exact resolved dependency versions and publisher-declared licenses are in
[`docs/DEPENDENCY_AUDIT.tsv`](docs/DEPENDENCY_AUDIT.tsv). That inventory separates
release/runtime, debug/runtime, Room's KSP processor and the build-tool classpath.
The audited baseline is OpenJump 1.0 / versionCode 7, Gradle 8.11.1, AGP 8.9.2,
Kotlin 2.1.20 and KSP 2.1.20-1.0.31. Re-audit when changing dependencies.

## Runtime libraries

| Component | Version | License / source |
| --- | --- | --- |
| CameraX, including camera-core/camera2/video/view and their transitive modules | 1.6.2; viewfinder-core 1.5.1 | Apache-2.0; camera-core also declares BSD-3-Clause for libyuv. [AndroidX source](https://android.googlesource.com/platform/frameworks/support/) |
| Media3 common/exoplayer/ui/transformer and transitive modules | 1.9.4 | Apache-2.0; [source](https://github.com/androidx/media) |
| Compose UI/runtime/foundation/material and Material 3 | 1.7.8 / 1.3.1; BOM 2025.03.00 | Apache-2.0; [AndroidX source](https://android.googlesource.com/platform/frameworks/support/) |
| Room / SQLite framework integration | 2.6.1 / 2.4.0 | Apache-2.0; AndroidX source |
| Core, Activity, Lifecycle, AppCompat, Navigation, annotations and other AndroidX modules | See inventory | Apache-2.0; AndroidX source |
| BoofCV feature/geo/ip/types | 1.4.0 | Apache-2.0; [source](https://github.com/lessthanoptimal/BoofCV) |
| DDogleg | 0.25.1 | Apache-2.0; [source](https://github.com/lessthanoptimal/ddogleg) |
| EJML modules | 0.45.1 | Apache-2.0; [source](https://github.com/lessthanoptimal/ejml) |
| GeoRegression | 0.30.0 | Apache-2.0; [source](https://github.com/lessthanoptimal/GeoRegression) |
| Kotlin standard library, kotlinx.coroutines, serialization and atomicfu | See inventory | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx](https://github.com/Kotlin) |
| Guava/failureaccess/listenablefuture, Dagger, JSpecify, AutoValue/error-prone/J2ObjC/JSR-305 annotations | See inventory | Apache-2.0; publisher/source URLs in inventory |
| javax.inject / Jakarta Dependency Injection | 1 / 2.0.1 | Apache-2.0; [Jakarta source](https://github.com/eclipse-ee4j/injection-api) |
| Checker Framework qualifiers | 3.43.0 | MIT; [source and license](https://github.com/typetools/checker-framework/tree/checker-framework-3.43.0/checker-qual) |
| Trove4j, transitively resolved by the computer-vision graph | 3.0.3 | LGPL-2.1-or-later; [published source archive](https://repo.maven.apache.org/maven2/net/sf/trove4j/trove4j/3.0.3/trove4j-3.0.3-sources.jar) |

Trove's source headers explicitly permit LGPL 2.1 **or later**, unlike its abbreviated
POM license name. It is free software, not a proprietary SDK. The baseline minified
release mapping contains no retained `gnu.trove` classes. Its license is nevertheless
included conservatively. GPL-3.0-or-later for OpenJump remains unchanged.

## Native code

There is no vendored native executable in this repository. Three native library
names, in four ABIs, come from freely licensed AndroidX Maven artifacts:

- `libandroidx.graphics.path.so`: graphics-path 1.0.1, Apache-2.0 with Skia's
  BSD-3-Clause portions; [source](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/graphics/graphics-path/src/main/cpp/).
- `libimage_processing_util_jni.so`: camera-core 1.6.2, Apache-2.0 with libyuv's
  BSD-3-Clause portions; [source](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/camera/camera-core/src/main/cpp/).
- `libsurface_util_jni.so`: camera-view 1.6.2, Apache-2.0;
  [source](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/camera/camera-view/src/main/cpp/).

[Skia's license](https://skia.googlesource.com/skia/+/refs/heads/main/LICENSE) and
[libyuv's license](https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/LICENSE)
are preserved in the APK. These are not MediaPipe libraries or ML models.

## License materials shipped with the APK

[`app/src/main/assets/licenses`](app/src/main/assets/licenses) contains canonical
Apache-2.0, MIT, LGPL-2.1 and BSD-3-Clause texts, upstream notices for Jakarta Inject
and kotlinx.coroutines, and the exact runtime dependency inventory with artifact
SHA-256 hashes. Identical upstream license texts are not duplicated per artifact.
The GPL text is also bundled as `res/raw/gpl_3_0.txt`, accessible from About → License.

## Build-only components

AGP, Kotlin plugins and KSP are Apache-2.0. The resolved build classpath also includes
freely licensed MIT/BSD/public-domain components, Bouncy Castle's MIT-style license,
JNA under its Apache/LGPL alternative, MPL-1.1 juniversalchardet and CDDL/GPL-with-
classpath-exception Java APIs. These are build tools, not proprietary SDKs linked
into OpenJump's APK. Room's compiler is build-only too. See the separate scopes and
concrete license URLs in the inventory rather than applying the app's license to
these tools.

## Artwork and fonts

See [`ASSET_LICENSES.md`](ASSET_LICENSES.md). Third-party marks do not become the
property of OpenJump. No font files, MediaPipe/BlazePose models, `.task`, `.tflite`
or similar inference artifacts are bundled.
