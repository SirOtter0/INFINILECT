# Third-party notices

Copyright © 2026 SirOtter0 and INFINILECT contributors.
Original INFINILECT code is GPL-3.0-or-later; upstream components retain their licenses.
No publication files are bundled. This foundation is not a packaged distribution.

## Direct dependencies and build tools

Licenses were checked against upstream license files before the foundation build.
The Gutenberg search slice adds Ktor and directly declares coroutines already
required by Compose/Ktor. Gradle 9.7.1's official tagged
[license](https://github.com/gradle/gradle/blob/v9.7.1/LICENSE) remains Apache-2.0.

| Component | Version | Purpose | License / official source |
| --- | --- | --- | --- |
| Kotlin Gradle plugin, standard library and kotlin-test | 2.4.20 | Compiler, pure core/runtime, tests | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Kotlin Compose compiler plugin | 2.4.20 | Compile Compose UI | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Compose Multiplatform plugin, runtime, foundation, Material and desktop | 1.12.1 | UI and desktop launcher | [Apache-2.0](https://github.com/JetBrains/compose-multiplatform/blob/master/LICENSE.txt) |
| Gradle / wrapper | 9.7.1 | Build tooling, including checked-in wrapper | [Apache-2.0](https://github.com/gradle/gradle/blob/master/LICENSE) |
| Ktor client core and Java engine | 3.6.0 | Desktop HTTP/OPDS transport | [Apache-2.0](https://github.com/ktorio/ktor/blob/3.6.0/LICENSE) |
| kotlinx.coroutines core | 1.11.0 | Search state, cancellation and request serialization | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/LICENSE.txt) |
| Ktor MockEngine; kotlinx.coroutines test | 3.6.0; 1.11.0 | Deterministic tests only | Same upstream Apache-2.0 licenses |
| kotlinx.serialization JSON (desktop only) | 1.11.0 | Bounded Archive JSON metadata; existing serialization-core reused | [Apache-2.0, exact tag](https://github.com/Kotlin/kotlinx.serialization/blob/v1.11.0/LICENSE.txt) |

Apache-2.0 is compatible with GPLv3; retain the upstream license and notices when
redistributing these components. A copy is in [third-party/Apache-2.0.txt](third-party/Apache-2.0.txt).
Gradle's distribution also contains its own license and third-party notices; the
wrapper downloads that distribution rather than including it in this repository.

## Transitive components

Compose also brings UI support libraries and native rendering components; these
are not optional new product features. Versions and artifact metadata are recorded
in [the resolved dependency inventory](docs/DEPENDENCIES.md).

- JetBrains AndroidX / AndroidX libraries: Apache-2.0; see the resolved Maven POMs
  and [AndroidX license](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/LICENSE.txt).
- kotlinx.coroutines: [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt).
- Ktor's supporting modules and kotlinx-io: Apache-2.0; see [kotlinx-io 0.9.1 license](https://github.com/Kotlin/kotlinx-io/blob/0.9.1/LICENSE).
- SLF4J API 2.0.19 (no logging backend added): [MIT](https://github.com/qos-ch/slf4j/blob/v_2.0.19/LICENSE.txt),
  copied in [third-party/slf4j-license.txt](third-party/slf4j-license.txt). MIT is GPLv3-compatible.
- JetBrains annotations: [Apache-2.0](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt).
- Skiko: [Apache-2.0](https://github.com/JetBrains/skiko/blob/master/LICENSE).
  Its native rendering uses Skia ([BSD-style license](https://skia.googlesource.com/skia/+/main/LICENSE))
  and upstream native components. The Skia license is copied in
  [third-party/skia-license.txt](third-party/skia-license.txt). Preserve the native artifact's included notices
  and audit the native bundle before distributing installers for each OS.
- kotlin-test's JVM test integration uses JUnit 4
  ([EPL-1.0](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt)) and
  Hamcrest ([BSD-3-Clause](https://github.com/hamcrest/JavaHamcrest/blob/master/LICENSE)).
  They are test-only, not app runtime dependencies. EPL-1.0 is not GPLv3-compatible
  for a combined distributed program; do not bundle JUnit or test binaries into the
  application. License copies are retained under `third-party/`. Running a separate test harness does not relicense project code.

The desktop XML parser is JDK 21's built-in StAX (`java.xml`), not a new Maven
library. OpenJDK uses [GPLv2](https://github.com/openjdk/jdk21u/blob/master/LICENSE)
with the [Classpath Exception](https://github.com/openjdk/jdk21u/blob/master/ADDITIONAL_LICENSE_INFO)
for its library code; this exception permits use by differently licensed applications.
JDK 21 is an external runtime/toolchain prerequisite, not bundled here. Preserve
its complete upstream legal directory if a runtime is redistributed later.

Readium is **not included**. SQLDelight is now used only by the local library/history
metadata store; its licenses and driver notices are recorded below.
Before any packaged release, produce an artifact-specific notice inventory and
include all required upstream license/copyright files, including native components.


## First Android target — 2026-10-03

The original code remains GPL-3.0-or-later. Third-party licenses are unchanged.
The following new direct platform/test/build declarations were checked against
upstream release documentation and published source/POM license declarations:

| Dependency/plugin | Version | License | Module / purpose / official source |
| --- | --- | --- | --- |
| Android Gradle Plugin: application and Android-KMP library plugins | 9.3.1 | Apache-2.0 | androidApp and core/app respectively; build tooling. [Google release](https://developer.android.com/build/releases/agp-9-3-0-release-notes), [published POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.1/gradle-9.3.1.pom). |
| Kotlin JVM Gradle plugin | 2.4.20 | Apache-2.0 | desktopApp launcher, same Kotlin toolchain. [Upstream license](https://github.com/JetBrains/kotlin/blob/v2.4.20/license/LICENSE.txt). |
| Ktor Android engine | 3.6.0 | Apache-2.0 | app/androidMain, officially maintained HttpURLConnection transport; no OkHttp dependency. [Tagged source/license](https://github.com/ktorio/ktor/tree/3.6.0/ktor-client/ktor-client-android). |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0 | androidApp, ComponentActivity/setContent/BackHandler. [Official release](https://developer.android.com/jetpack/androidx/releases/activity). |
| AndroidX Compose Foundation | 1.12.1 | Apache-2.0 | androidApp, system/keyboard inset wrapper; same Android Compose version already selected by shared Compose. [Google POM](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.12.1/foundation-1.12.1.pom). |
| AndroidX Startup runtime | 1.2.0 | Apache-2.0 | androidApp, explicit manifest provider dependency (already transitive at 1.1.1); disable EmojiCompat automatic downloadable-font initialization. [Stable release](https://developer.android.com/jetpack/androidx/releases/startup), [POM](https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.2.0/startup-runtime-1.2.0.pom). |
| kXML2 | 2.3.0 | MIT | app/androidHostTest only, real XML tokenization on the host where Android APIs are stubs; never in the APK. The MIT header is in the [published sources](https://repo.maven.apache.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0-sources.jar), copied to [kxml2-MIT.txt](third-party/kxml2-MIT.txt). |

AndroidX runtime transitive dependencies (including coroutines-android) keep their
Apache-2.0 licenses. Existing SLF4J stays MIT. AndroidX graphics-path 1.0.1 supplies
a prebuilt native library under Apache-2.0; [official native sources](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/graphics/graphics-path/src/main/cpp/)
carry AOSP notices. Debug packaging retains that upstream library/symbols unchanged.
These runtime licenses are compatible with GPL-3.0-or-later; no analytics/advertising
SDK, charset detector or reader framework is added to app runtime.

Production Android XML parsing uses the OS XmlPull API, not the test kXML jar.
The [current AOSP parser factory](https://android.googlesource.com/platform/libcore/+/refs/heads/main/luni/src/main/java/libcore/util/XmlObjectFactory.java)
identifies the platform KXmlParser. AOSP wrapper code is Apache-2.0; the parser's
[official Android 8 implementation](https://android.googlesource.com/platform/libcore/+/refs/tags/android-8.0.0_r1/xml/src/main/java/org/kxml2/io/KXmlParser.java)
has the MIT attribution to Stefan Haustein. Platform libraries are supplied by
Android, not copied into the APK. Android SDK command-line/build/platform tools
are external prerequisites governed by [SDK terms](https://developer.android.com/studio/terms),
not project libraries or redistributed SDK binaries.

The full [current graph inventory](docs/ANDROID_DEPENDENCIES.md) distinguishes
runtime, host tests and tooling, including metadata/BOM components. AGP brings
additional separately licensed tooling, including Bouncy Castle (MIT), JDOM's
BSD-style license, JNA's Apache-2.0/LGPL dual option and universalchardet (MPL-1.1).
The latter is **AGP tooling only**, not a reader charset detector. JUnit EPL-1.0
and test kXML are absent from the APK. Do not bundle test/build-tool classes into
product artifacts. Preserve upstream notices if those tools themselves are
redistributed. The future packaged-release/native notice audit remains required;
this task produces only a local debug APK and no release distribution.

## Local library/history SQLDelight — 2026-10-03

SQLDelight Gradle plugin, runtime, JDBC/SQLite driver and Android driver **2.4.0**
are Apache-2.0. [Stable upstream release](https://github.com/sqldelight/sqldelight/releases/tag/2.4.0),
[exact-tag license](https://github.com/sqldelight/sqldelight/blob/2.4.0/LICENSE.txt),
[official multiplatform driver setup](https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/).
Existing [Apache license copy](third-party/Apache-2.0.txt) applies. AndroidX SQLite
and SQLite Framework **2.7.1** retain Apache-2.0 (official Google Maven POMs linked
in [DEPENDENCIES](docs/DEPENDENCIES.md)). Core and ReadingProgress do not use SQLDelight.

Desktop/host-test Xerial SQLite JDBC **3.53.4.0** is Apache-2.0, with inherited
Zentus code under BSD-2-Clause and native SQLite in the public domain. Retain both
upstream [Apache license](third-party/sqlite-jdbc-Apache-2.0.txt) and
[David Crawshaw BSD notice](third-party/sqlite-jdbc-BSD-2-Clause.txt), copied from
the resolved artifact's META-INF/maven/org.xerial/sqlite-jdbc license files (CRLF
line endings normalized to LF, license wording unchanged).
[Official tagged Apache](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE),
[Zentus](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE.zentus),
[SQLite public-domain statement](https://www.sqlite.org/copyright.html).
These are GPLv3-compatible upstream licenses/dedication, not relicensed project code.
The JDBC driver/native binaries and host tests are absent from the Android APK;
Android uses its OS SQLite through AndroidX. Redistributors must retain included
native/driver notices. SQL compiler/plugin transitives are build tooling only.
No new permission, telemetry or network behavior is introduced by this database.
