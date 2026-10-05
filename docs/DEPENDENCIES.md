# Dependencies and licenses

## Desktop runtime verification — PR #12, 2026-10-04

No Maven dependency, version or license changes. The existing Desktop app image
must include JDK modules `java.net.http` and `java.sql` for its already-declared
Ktor Java engine and SQLDelight/JDBC driver. The reduced runtime retains upstream
module notices; [third-party policy](../THIRD_PARTY_NOTICES.md) and
[actual verification](VERIFICATION.md) distinguish local images from releases.

## Current PR #11 correction — 2026-10-04

Gutenberg now uses shared OPDS2 JSON via already-declared
kotlinx.serialization-json 1.11.0 (Apache-2.0), on both Android and Desktop.
No dependency/version is added or upgraded. The obsolete Gutenberg XML adapters
and host-only kXML2 2.3.0 declaration are removed. Prior Android/XML graph sections
below describe their historical configuration; retained upstream license copies
are unchanged. JDK/Android licenses and all existing runtime dependencies remain
unchanged. No kXML test jar or parser is newly bundled.


## Local library/history additions — 2026-10-03

Only the new metadata database uses SQLDelight. ReadingProgress remains its existing
file store; cache remains files, core remains Kotlin-only. No unrelated dependency
version is changed. [Storage/schema policy](LIBRARY_HISTORY.md).

| Explicit dependency/plugin | Version | License | Module / reason / official evidence |
| --- | --- | --- | --- |
| app.cash.sqldelight Gradle plugin | 2.4.0 | Apache-2.0 | root apply-false/app; SQL code generation and definition checks. [Stable release](https://github.com/sqldelight/sqldelight/releases/tag/2.4.0), [exact license](https://github.com/sqldelight/sqldelight/blob/2.4.0/LICENSE.txt). |
| app.cash.sqldelight:runtime | 2.4.0 | Apache-2.0 | app/commonMain generated query/runtime API; never core. [POM](https://repo.maven.apache.org/maven2/app/cash/sqldelight/runtime/2.4.0/runtime-2.4.0.pom). |
| app.cash.sqldelight:sqlite-driver | 2.4.0 | Apache-2.0 | app/desktopMain; also jvmSharedTest for real offline file-database tests, not Android runtime. [POM](https://repo.maven.apache.org/maven2/app/cash/sqldelight/sqlite-driver/2.4.0/sqlite-driver-2.4.0.pom). |
| app.cash.sqldelight:android-driver | 2.4.0 | Apache-2.0 | app/androidMain, maintained OS SQLite driver/private Context database path. [POM](https://repo.maven.apache.org/maven2/app/cash/sqldelight/android-driver/2.4.0/android-driver-2.4.0.pom). |

Runtime additions also include SQLDelight runtime-jvm/jdbc-driver 2.4.0
(Apache-2.0), Desktop Xerial sqlite-jdbc **3.53.4.0** (Apache-2.0 plus retained
BSD-2-Clause Zentus code and public-domain SQLite), and AndroidX sqlite /
sqlite-framework **2.7.1** (Apache-2.0). Official [JDBC license](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE),
[Zentus notice](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE.zentus),
[SQLite public-domain dedication](https://www.sqlite.org/copyright.html),
[AndroidX sqlite POM](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite/2.7.1/sqlite-2.7.1.pom)
and [framework POM](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-framework/2.7.1/sqlite-framework-2.7.1.pom).
The JDBC native bundle is Desktop/host-test only, absent from the APK. These runtime
licenses are compatible with GPL-3.0-or-later; upstream terms remain unchanged.

SQLDelight is used instead of a hand-maintained SQL layer for generated typed
queries, normalized child metadata, atomic multi-row transactions and migration
support. The official multiplatform setup supports these JVM/Android drivers.
Stable 2.4.0's tagged wrapper uses this project's Gradle 9.7.1; this repository
verifies actual Kotlin 2.4.20/AGP 9.3.1/Compose 1.12.1 compilation and tests rather
than claiming an upstream certification matrix. See [TOOLCHAIN](TOOLCHAIN.md).
Build-only SQL compiler/plugin dependencies are not included in applications.
No navigation, telemetry, serialization format or additional source dependency.

## Current Android/Desktop declarations — 2026-10-03

Core domain dependencies remain Kotlin-only. Android build plugins/targets do not
add Android/Compose/Ktor APIs to core/commonMain. Shared app retains Compose 1.12.1,
coroutines 1.11.0, Ktor 3.6.0 and serialization-json 1.11.0. The Desktop Java engine
and OS Compose runtime remain on Desktop; neither enters the Android APK.

| New explicit declaration | Version | License | Module / necessity |
| --- | --- | --- | --- |
| com.android.application | 9.3.1 | Apache-2.0 | androidApp APK packaging, built-in Kotlin. |
| com.android.kotlin.multiplatform.library | 9.3.1 | Apache-2.0 | core/app Android library variants; required with AGP 9/KMP separation. |
| org.jetbrains.kotlin.jvm | 2.4.20 | Apache-2.0 | desktopApp JVM launcher, unchanged compiler version. |
| io.ktor:ktor-client-android | 3.6.0 | Apache-2.0 | app/androidMain engine; engine-independent policies stay shared. |
| androidx.activity:activity-compose | 1.13.0 | Apache-2.0 | androidApp Activity/Compose/Back. |
| androidx.compose.foundation:foundation | 1.12.1 | Apache-2.0 | androidApp safe system/keyboard insets, same Compose version. |
| androidx.startup:startup-runtime | 1.2.0 | Apache-2.0 | androidApp manifest directly references its provider to remove automatic downloadable-font initialization; compile/runtime consistent. |
| net.sf.kxml:kxml2 | 2.3.0 | MIT | app/androidHostTest only: real tokenization against the Android token adapter; no runtime parser dependency. |

Official version/license URLs and copies are in [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)
and [TOOLCHAIN](TOOLCHAIN.md). [ANDROID_DEPENDENCIES](ANDROID_DEPENDENCIES.md) records
the complete current resolved component graphs, POM licenses and upstream exceptions.
Graph components include metadata/BOM redirects and project roots; they are not
counts of jars/classes bundled in an APK. Runtime/tests/build tooling are separate.
Tests and opt-in CLI diagnostics are never packaged in either application.

Common controller/session/reader tests run on both targets. Engine-independent
Archive policy/source tests use MockEngine on Desktop and Android host. Desktop
StAX/OPDS source fixtures remain in desktopTest. Android uses a fake token parser
and test-only upstream kXML for real XML fixtures; upstream kXML does not implement
Android's optional process-docdecl feature, so the host shim accepts only its
verified disabled default. Production Android requires explicit DTD disablement,
without a fallback. Host tests are not a claim of Android OS/emulator execution.

The Android foundation introduced no database. The later local library/history
addition above introduces only its metadata database. No permanent repository
substitution, logging backend, navigation/DI, reader engine or telemetry is added.

## Historical Desktop artifact inventory — 2026-10-02

The artifact tables below retain the previous Desktop acquisition graph and its
license evidence. The current component graphs above supersede their old counts
and source-set scope after separating platform launchers.


At that earlier acquisition step, the project added one desktop-only JSON runtime module:
`kotlinx-serialization-json:1.11.0`, reusing Ktor's already resolved
serialization-core 1.11.0. Dynamic JsonElement parsing needs no serialization
compiler plugin. This avoids a handwritten JSON parser; Ktor has no built-in JSON
parser. Core remains unchanged. The [exact-tag Apache-2.0 license](https://github.com/Kotlin/kotlinx.serialization/blob/v1.11.0/LICENSE.txt)
is GPLv3-compatible; the [tagged version catalog](https://github.com/Kotlin/kotlinx.serialization/blob/v1.11.0/gradle/libs.versions.toml)
builds with Kotlin 2.3.20, supported by this project's Kotlin 2.4.20 compiler.
No version, repository, XML library, engine or logging dependency changed.

Resolved on Linux x86-64 with JDK 21 and Gradle 9.7.1 on 2026-10-02 for the Gutenberg search slice. Generated from the runtime, test and build-plugin graphs and cached Maven POMs (including inherited parent licenses). Native artifacts vary by OS. POM declarations supplement upstream license/notice files; see [third-party notices](../THIRD_PARTY_NOTICES.md).

New direct application declarations: Ktor client core/Java engine 3.6.0 and coroutines core 1.11.0. Test-only declarations: Ktor MockEngine 3.6.0, coroutines test 1.11.0 and kotlin-test 2.4.20. Their exact stable upstream licenses were reviewed before inclusion. No new XML parser library or logging backend is added; StAX belongs to the external JDK 21 prerequisite.

The desktop runtime graph now contains 64 artifacts including local core (foundation: 46). Ktor adds 17 resolved runtime artifacts and selects coroutines 1.11.0 / serialization-core 1.11.0 over the earlier transitive versions. Ktor supporting HTTP/CIO, SSE and WebSocket artifacts below are transitive dependencies of its client; no corresponding product functionality or additional engine was added. Core remains at 2 runtime artifacts; its 6 test artifacts and the 22 build-plugin artifacts are unchanged. App tests resolve 70 artifacts including the application runtime.

SLF4J API has no license element in its module POM; MIT is verified from its [tagged upstream license](https://github.com/qos-ch/slf4j/blob/v_2.0.19/LICENSE.txt), copied under third-party. Test-only JUnit/Hamcrest are not bundled into the app.

## Core JVM runtime

| Artifact | Version | License evidence |
| --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations` | 13.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |

## Desktop application runtime

| Artifact | Version | License evidence |
| --- | --- | --- |
| `androidx.annotation:annotation-jvm` | 1.9.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.arch.core:core-common` | 2.2.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.collection:collection-jvm` | 1.5.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-annotation-jvm` | 1.12.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-retain-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-saveable-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-compose-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-compose-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.navigationevent:navigationevent-compose-desktop` | 1.1.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.navigationevent:navigationevent-desktop` | 1.1.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate-compose-desktop` | 1.4.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate-desktop` | 1.4.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-core-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-java-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-events-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-http-cio-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-http-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-io-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-network-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-serialization-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-sse-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-utils-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-websocket-serialization-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-websockets-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.infinilect:core` | 0.0.1-SNAPSHOT | [GPL-3.0-or-later](../LICENSE) (local project) |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop` | 2.9.6 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose-desktop` | 1.1.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.savedstate:savedstate-compose-desktop` | 1.3.6 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.animation:animation-core-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.animation:animation-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.desktop:desktop-jvm` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.foundation:foundation-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.foundation:foundation-layout-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.material:material-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.material:material-ripple-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.runtime:runtime-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.runtime:runtime-saveable-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-geometry-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-graphics-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-text-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-tooling-preview-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-unit-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-util-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:atomicfu-jvm` | 0.28.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-jdk8` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm` | 0.9.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-io-core-jvm` | 0.9.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.11.0 | [Apache-2.0, tagged license](https://github.com/Kotlin/kotlinx.serialization/blob/v1.11.0/LICENSE.txt) |
| `org.jetbrains.runtime:jbr-api` | 1.9.0 | [The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| `org.jetbrains.skiko:skiko-awt` | 0.150.1 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.skiko:skiko-awt-runtime-linux-x64` | 0.150.1 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations` | 23.0.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jspecify:jspecify` | 1.0.0 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.slf4j:slf4j-api` | 2.0.19 | [MIT (upstream)](https://github.com/qos-ch/slf4j/blob/v_2.0.19/LICENSE.txt) |

## Core JVM tests (not bundled in app)

| Artifact | Version | License evidence |
| --- | --- | --- |
| `junit:junit` | 4.13.2 | [Eclipse Public License 1.0](http://www.eclipse.org/legal/epl-v10.html) |
| `org.hamcrest:hamcrest-core` | 1.3 | [New BSD License](http://www.opensource.org/licenses/bsd-license.php) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-test` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-test-junit` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations` | 13.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |

## App desktop tests (not bundled in app)

| Artifact | Version | License evidence |
| --- | --- | --- |
| `androidx.annotation:annotation-jvm` | 1.9.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.arch.core:core-common` | 2.2.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.collection:collection-jvm` | 1.5.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-annotation-jvm` | 1.12.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-retain-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-saveable-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-compose-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-compose-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-desktop` | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.navigationevent:navigationevent-compose-desktop` | 1.1.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.navigationevent:navigationevent-desktop` | 1.1.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate-compose-desktop` | 1.4.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate-desktop` | 1.4.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-core-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-java-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-mock-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-events-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-http-cio-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-http-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-io-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-network-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-serialization-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-sse-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-utils-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-websocket-serialization-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-websockets-jvm` | 3.6.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `junit:junit` | 4.13.2 | [Eclipse Public License 1.0](http://www.eclipse.org/legal/epl-v10.html) |
| `org.hamcrest:hamcrest-core` | 1.3 | [New BSD License](http://www.opensource.org/licenses/bsd-license.php) |
| `org.infinilect:core` | 0.0.1-SNAPSHOT | [GPL-3.0-or-later](../LICENSE) (local project) |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop` | 2.9.6 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose-desktop` | 1.1.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.savedstate:savedstate-compose-desktop` | 1.3.6 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.animation:animation-core-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.animation:animation-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.desktop:desktop-jvm` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.foundation:foundation-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.foundation:foundation-layout-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.material:material-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.material:material-ripple-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.runtime:runtime-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.runtime:runtime-saveable-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-geometry-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-graphics-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-text-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-tooling-preview-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-unit-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-util-desktop` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-test` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-test-junit` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:atomicfu-jvm` | 0.28.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-jdk8` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm` | 0.9.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-io-core-jvm` | 0.9.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.11.0 | [Apache-2.0, tagged license](https://github.com/Kotlin/kotlinx.serialization/blob/v1.11.0/LICENSE.txt) |
| `org.jetbrains.runtime:jbr-api` | 1.9.0 | [The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| `org.jetbrains.skiko:skiko-awt` | 0.150.1 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.skiko:skiko-awt-runtime-linux-x64` | 0.150.1 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations` | 23.0.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jspecify:jspecify` | 1.0.0 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.slf4j:slf4j-api` | 2.0.19 | [MIT (upstream)](https://github.com/qos-ch/slf4j/blob/v_2.0.19/LICENSE.txt) |

## Gradle plugin classpath (build tooling)

| Artifact | Version | License evidence |
| --- | --- | --- |
| `com.google.code.gson:gson` | 2.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.errorprone:error_prone_annotations` | 2.27.0 | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.hot-reload:hot-reload-gradle-plugin` | 1.2.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose:compose-gradle-plugin` | 1.12.1 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:compose-compiler-gradle-plugin` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:fus-statistics-gradle-plugin` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-build-statistics` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-build-tools-api` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-compiler-runner` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-daemon-client` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-annotations` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-api` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-idea` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-idea-proto` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-klib-commonizer-api` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-native-utils` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-tooling-core` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-util-io` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-util-klib` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-util-klib-metadata` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.8.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |

## Draft PR #15 renderer decision

No new dependencies or version changes. Existing Compose annotated text/lazy
layout and hardened platform SAX/ZIP implement the passive EPUB subset. Desktop's
bundled JDK explicitly includes the standard `java.xml` module alongside its
existing HTTP/SQL modules. Readium, WebView, JavaFX, JCEF and compose-richtext were
researched but not added; [upstream/version/license evidence and trade-offs](EPUB_READER.md).

## EPUB presentation/media — PR #16

No new third-party dependency/plugin/version. Android uses SDK26+ BitmapFactory /
ColorSpace; Desktop uses standard JDK21 ImageIO with bounded byte-stream input and
explicit java.desktop in the packaged runtime. Compose Image/ImageBitmap and existing
Android/Skia conversion support already ship in the app graph. app/desktopTest now
uses desktopApp's same `compose.desktop.currentOs` dependency to execute real headless
Skia image conversion; no decoder fake substitutes for that Desktop integration test.
Existing Compose/Skiko/Skia/JDK notices/licenses apply unchanged. Android host tests
exercise shared header/policy/controller logic, not the real BitmapFactory runtime.
