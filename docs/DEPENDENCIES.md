# Resolved dependency inventory

Resolved on Linux x86-64 with JDK 21 and Gradle 9.7.1 on 2026-10-02. Generated from the runtime, test and build-plugin artifact graphs and their cached Maven POMs. Native artifacts vary by OS. POM declarations supplement, rather than replace, upstream license/notice files. See [third-party notices](../THIRD_PARTY_NOTICES.md).

The second technical pass adds no library dependencies. Desktop runtime, JVM test
and build-plugin graphs match the original foundation. The core graph is included
below to make its dependency boundary explicit. Gradle itself is a separately
downloaded build tool, now at 9.7.1.

## Core JVM runtime

| Artifact | Version | Declared license |
| --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations` | 13.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |

## Desktop runtime

| Artifact | Version | Declared license |
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
| `org.infinilect:core` | 0.0.1-SNAPSHOT | GPL-3.0-only (local project, not third-party) |
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
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.9.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.7.3 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.runtime:jbr-api` | 1.9.0 | [The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| `org.jetbrains.skiko:skiko-awt` | 0.150.1 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.skiko:skiko-awt-runtime-linux-x64` | 0.150.1 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations` | 23.0.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jspecify:jspecify` | 1.0.0 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |

## Core JVM tests (not bundled in app)

| Artifact | Version | Declared license |
| --- | --- | --- |
| `junit:junit` | 4.13.2 | [Eclipse Public License 1.0](http://www.eclipse.org/legal/epl-v10.html) |
| `org.hamcrest:hamcrest-core` | 1.3 | [New BSD License](http://www.opensource.org/licenses/bsd-license.php) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-test` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-test-junit` | 2.4.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations` | 13.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |

## Gradle plugin classpath (build tooling)

| Artifact | Version | Declared license |
| --- | --- | --- |
| `com.google.code.gson:gson` | 2.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.errorprone:error_prone_annotations` | 2.27.0 | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.hot-reload:hot-reload-gradle-plugin` | 1.2.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose:compose-gradle-plugin` | 1.12.1 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
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
