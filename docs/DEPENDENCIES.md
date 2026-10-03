# Resolved dependency inventory

The acquisition experiment adds one desktop-only JSON runtime module:
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
