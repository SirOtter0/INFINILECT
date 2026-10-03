# Toolchain verification

Official documentation checked on 2026-10-02 for the foundation and rechecked for
the second technical pass before updating the wrapper.

| Component | Selected | Official evidence |
| --- | --- | --- |
| Kotlin / Kotlin Gradle plugin / Compose compiler plugin | 2.4.20 | [Kotlin release history](https://kotlinlang.org/docs/releases.html), [KGP compatibility](https://kotlinlang.org/docs/gradle-configure-project.html) |
| Compose Multiplatform | 1.12.1 | [JetBrains release](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1), [release API](https://api.github.com/repos/JetBrains/compose-multiplatform/releases/tags/v1.12.1), [JetBrains compatibility guide](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html) |
| Gradle | 9.7.1 | [Patch release notes and recommendation](https://docs.gradle.org/9.7.1/release-notes.html), [Gradle compatibility](https://docs.gradle.org/9.7.1/userguide/compatibility.html) |
| JDK | 21 | [Gradle JVM compatibility](https://docs.gradle.org/9.7.1/userguide/compatibility.html), [Compose desktop requirements](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html) |
| Ktor client / Java engine / test MockEngine | 3.6.0 | [Official release](https://github.com/ktorio/ktor/releases/tag/3.6.0), [Ktor release history](https://ktor.io/docs/releases.html), [client engine platforms](https://ktor.io/docs/client-engines.html) |
| kotlinx.coroutines core / test | 1.11.0 | [Official stable release](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0), [Ktor's tagged dependency versions](https://github.com/ktorio/ktor/blob/3.6.0/gradle/libs.versions.toml) |

## Gutenberg slice additions

Rechecked official release/metadata/license sources before adding dependencies on
2026-10-02. Ktor 3.6.0 is not a draft/prerelease; its tagged build and published
POMs use Kotlin 2.3.21 and coroutines 1.11.0. The existing newer Kotlin 2.4.20
compiler accepts these libraries; project tests/build establish actual compatibility.
No Ktor Gradle plugin, serialization plugin, JSON/XML library or logging backend
is introduced. Java engine requires Java 11+ and supports the current JDK 21
desktop target; it is not an Android/iOS engine. StAX is the built-in desktop JDK
parser. Additional platforms require their own adapters/checks. Foundation Kotlin,
Compose, Gradle and JDK choices are unchanged.

## Compose 1.12.1 is an official stable release

The JetBrains-owned compose-multiplatform repository has tag/release `v1.12.1`,
published on 2026-09-22. Its official GitHub release API returns `draft: false` and
`prerelease: false`, lists changes since 1.12.0, and identifies the Gradle plugin,
runtime, UI, foundation and Material coordinates at 1.12.1. JetBrains' compatibility
page independently names Compose Multiplatform 1.12.1 as the supported release.
Therefore retain 1.12.1; this is not an EAP, RC, snapshot or an inferred version.
Use stable Material rather than the Material3 alpha listed in that release.

The Compose guide states that latest stable Compose is compatible with latest
stable Kotlin. Its compiler plugin must match Kotlin, so both Kotlin plugins remain
2.4.20. At that foundation check no Android Gradle plugin, SDK or Xcode dependency
was introduced; the later Android verification below supersedes that target scope.

## Gradle patch upgrade and compatibility precision

Gradle calls 9.7.1 the first patch release of 9.7.0 and explicitly recommends the
upgrade. Fixes include execution-stream API behavior, test difference formatting,
ANTLR leaking into kapt, transformer behavior, Ant classloading and Option annotation
argument order. Move from the original foundation's 9.7.0 to the recommended patch.

JetBrains' [KMP compatibility table](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-compatibility-guide.html)
and KGP table still list 7.6.3–9.7.0 for Kotlin 2.4.20 at the time of this check;
they do **not** explicitly list 9.7.1. Do not present the patch as an updated vendor
certification range. The selection follows Gradle's patch recommendation, and
compatibility for INFINILECT is established by the actual JDK 21 test/full-build
results in [VERIFICATION.md](VERIFICATION.md). Gradle 9.8.0 is outside that listed
range and is not adopted. IDE imports and additional platform targets require their
own checks when introduced.

## Wrapper integrity

The wrapper was updated and then regenerated through Gradle 9.7.1. The downloaded
binary distribution is verified using `distributionSha256Sum`, and the generated
wrapper JAR is checked separately against Gradle's official wrapper checksum.
The JAR checksum is identical to the previous wrapper, so an unchanged binary is
expected rather than an incomplete upgrade.

| Artifact | Official SHA-256 |
| --- | --- |
| [Gradle 9.7.1 binary distribution](https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256) | `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a` |
| [Gradle 9.7.1 wrapper JAR](https://services.gradle.org/distributions/gradle-9.7.1-wrapper.jar.sha256) | `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` |

Versions and documented compatibility guide selection; successful builds are
reported separately, not assumed from these references.

## First Android target — verified 2026-10-03

Keep Kotlin/Compose compiler **2.4.20**, Compose Multiplatform **1.12.1**, Gradle
**9.7.1** and JDK **21**. No existing version is changed. Add:

| Component | Selected | Official evidence / reason |
| --- | --- | --- |
| Android Gradle Plugin, application + Android-KMP library plugins | 9.3.1 | [Google release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes); [JetBrains Kotlin 2.4.20 compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) lists AGP 8.5.2–9.3.1. AGP requires Gradle ≥9.5/JDK ≥17 and supports API 37. |
| compileSdk | 37 | [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk); Compose 1.12.1 Android AAR metadata requires minCompileSdk 37. SDK 36 failed this actual check; 37 passes. Official stable `platforms;android-37.0` r2 is installed. |
| targetSdk | 37 | [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk); first Android target uses the current stable API, no legacy target compatibility mode. Runtime/device validation remains pending; no Play Store distribution. |
| minSdk | 26 | [java.util.Base64](https://developer.android.com/reference/java/util/Base64) added at API 26. Preserves shared validated opaque tokens without desugaring/reimplementation. Compose's documented platform minimum alone would permit 21. |
| SDK Build Tools | 36.0.0 | AGP 9.3 default/minimum in Google release notes; no custom build-tools override. |
| AndroidX Activity Compose | 1.13.0 | [Official stable release](https://developer.android.com/jetpack/androidx/releases/activity), not 1.14 alpha. Provides ComponentActivity/setContent/BackHandler/insets ownership. |
| AndroidX Startup runtime | 1.2.0 | [Official stable release](https://developer.android.com/jetpack/androidx/releases/startup). The launcher manifest directly references its provider to disable automatic font initialization; compile/runtime versions match. |
| Ktor Android engine | 3.6.0 | [Official engine docs](https://ktor.io/docs/client-engines.html#android); [tagged engine](https://github.com/ktorio/ktor/blob/3.6.0/ktor-client/ktor-client-android/jvm/src/io/ktor/client/engine/android/AndroidClientEngine.kt). Uses HttpURLConnection, disables connection-level redirects, supports HttpTimeout and cancellation/disconnect; no OkHttp dependency. |
| Android host XML test support only | kxml2 2.3.0 | [Published source artifact](https://repo.maven.apache.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0-sources.jar), MIT header. Real tokenization tests without Android OS/emulator; not a runtime XML library or part of the APK. |

Use [AGP's dedicated Android-KMP plugin](https://developer.android.com/kotlin/multiplatform/plugin)
with the current `kotlin.android {}` DSL (the earlier `androidLibrary {}` spelling
is deprecated). Apply the KMP plugin only to core/app and com.android.application
only to androidApp, following [JetBrains migration guidance](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html).
AGP built-in Kotlin uses the root-resolved KGP/compiler **2.4.20**; no extra
org.jetbrains.kotlin.android plugin or legacy/built-in-Kotlin opt-out is needed.
[Google's built-in Kotlin guide](https://developer.android.com/build/migrate-to-built-in-kotlin).
AGP 9.4 is not selected despite being newer: it is outside the current explicit
Kotlin 2.4.20 compatibility table. The Gradle 9.7.1 vendor-range precision above
still applies; actual multi-target builds establish this project's compatibility.

Android libraries/app target JVM bytecode **17**, while JDK **21** runs Gradle and
Desktop compilation. minSdk 26 supports every shared Java URI/NIO/Base64/atomic API
used here. No Java desktop engine or javax.xml.stream classes enter the APK.
The Android parser is platform XmlPull, obtained through android.util.Xml; DTD
processing is explicitly disabled (Android's default may enable it). Official
[XmlPull contract](https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser),
[AOSP Xml implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/util/Xml.java),
and [AOSP parser factory](https://android.googlesource.com/platform/libcore/+/refs/heads/main/luni/src/main/java/libcore/util/XmlObjectFactory.java)
identify the OS implementation. Desktop retains hardened JDK StAX.

SDK/toolchain files live outside Git. The debug APK uses standard Android debug
signing only; no release key/signing configuration. AndroidX's prebuilt graphics
path native library is packaged unchanged with symbols; no unnecessary NDK/native
build is introduced. See [verification](VERIFICATION.md) for actual commands,
APK metadata and environment provisioning details.

## SQLDelight local metadata database — 2026-10-03

Add only SQLDelight **2.4.0**, a stable release published 2026-09-18, Apache-2.0:
[official release](https://github.com/sqldelight/sqldelight/releases/tag/2.4.0),
[tagged license](https://github.com/sqldelight/sqldelight/blob/2.4.0/LICENSE.txt).
[Official multiplatform setup](https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/)
uses the runtime and platform Android/JVM SQLite drivers chosen here.
Its [tagged wrapper](https://github.com/sqldelight/sqldelight/blob/2.4.0/gradle/wrapper/gradle-wrapper.properties)
uses Gradle **9.7.1**; its Kotlin compiler baseline is older than this project's
2.4.20. No claim of a published certification matrix for this exact full stack:
repository compilation, real SQLite tests, Android lint and APK assembly provide
actual compatibility evidence in [VERIFICATION](VERIFICATION.md).
Kotlin/Compose/Gradle/AGP/JDK/SDK versions remain unchanged. SQLDelight is app-only;
core remains pure and ReadingProgress is not migrated. Initial schema v1 runs SQL
definition checks and fresh/reopen tests; historical migration replay is introduced
with the first version change/baseline, not a generated database committed today.
