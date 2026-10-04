# Android / Desktop resolved dependency graphs

## Current PR #11 correction — 2026-10-04

Gutenberg now uses shared OPDS2 JSON via already-declared
kotlinx.serialization-json 1.11.0 (Apache-2.0), on both Android and Desktop.
No dependency/version is added or upgraded. The obsolete Gutenberg XML adapters
and host-only kXML2 2.3.0 declaration are removed. Prior Android/XML graph sections
below describe their historical configuration; retained upstream license copies
are unchanged. JDK/Android licenses and all existing runtime dependencies remain
unchanged. No kXML test jar or parser is newly bundled.


Verified on 2026-10-03 with JDK 21 / Gradle 9.7.1 / AGP 9.3.1. Generated
from Gradle component graphs and cached Maven POMs/parent licenses; exceptions
use the linked published source or upstream license. This includes metadata,
BOM and platform redirects, **not** a count of jars/classes in the APK. Local
project roots are excluded. The complete [machine-readable inventory](dependencies/android-2026-10-03.json)
also records current Desktop, core and host-test configurations. [Direct declarations](DEPENDENCIES.md),
[notices](../THIRD_PARTY_NOTICES.md), [toolchain](TOOLCHAIN.md).

| Configuration | External components |
| --- | --- |
| coreRuntime | 2 |
| coreAndroid | 2 |
| desktopRuntime | 128 |
| appTests | 130 |
| androidRuntime | 166 |
| androidTests | 173 |
| build | 131 |

Core JVM/Android runtime resolves only Kotlin stdlib 2.4.20 and annotations 13.0.
Android runtime contains no Java HTTP engine, Desktop/Skiko runtime, kXML test jar,
JUnit/test classes or AGP/tooling dependencies. No analytics SDK, reader charset
detector or navigation framework. SQLDelight stores local metadata only. AndroidX coroutines/lifecycle/startup
are transitive runtime support, not new product functionality.

## Android application runtime

| Component | License evidence |
| --- | --- |
| `androidx.activity:activity-compose:1.13.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.activity:activity-ktx:1.13.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.activity:activity:1.13.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.annotation:annotation-experimental:1.4.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.annotation:annotation-jvm:1.9.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.annotation:annotation:1.9.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.arch.core:core-common:2.2.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.arch.core:core-runtime:2.2.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.autofill:autofill:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.collection:collection-jvm:1.5.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.collection:collection-ktx:1.5.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.collection:collection:1.5.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.animation:animation-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.animation:animation-core-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.animation:animation-core:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.animation:animation:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.foundation:foundation-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.foundation:foundation-layout-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.foundation:foundation-layout:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.foundation:foundation:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.material:material-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.material:material-ripple-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.material:material-ripple:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.material:material:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-annotation-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-annotation:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-retain-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-retain:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-saveable-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime-saveable:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.runtime:runtime:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-geometry-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-geometry:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-graphics-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-graphics:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-text-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-text:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-unit-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-unit:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-util-android:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui-util:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.compose.ui:ui:1.12.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.concurrent:concurrent-futures:1.1.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.core:core-ktx:1.18.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.core:core-viewtree:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.core:core:1.18.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.customview:customview-poolingcontainer:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.emoji2:emoji2:1.4.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.graphics:graphics-path:1.0.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.interpolator:interpolator:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-common-jvm:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-common:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-livedata-core:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-process:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-android:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-compose:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-ktx-android:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime-ktx:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-android:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel:2.9.4` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.navigationevent:navigationevent-android:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.navigationevent:navigationevent-compose-android:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.navigationevent:navigationevent-compose:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.navigationevent:navigationevent:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.profileinstaller:profileinstaller:1.4.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate-android:1.4.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate-compose-android:1.4.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate-compose:1.4.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate-ktx:1.4.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate:1.4.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.sqlite:sqlite-android:2.7.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.sqlite:sqlite-framework-android:2.7.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.sqlite:sqlite-framework:2.7.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.sqlite:sqlite:2.7.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.startup:startup-runtime:1.2.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.tracing:tracing:1.2.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.window:window-core-android:1.5.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.window:window-core:1.5.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.window:window:1.5.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `app.cash.sqldelight:android-driver:2.4.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `app.cash.sqldelight:runtime-jvm:2.4.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `app.cash.sqldelight:runtime:2.4.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.guava:listenablefuture:1.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-android-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-android:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-core-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-core:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-events-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-events:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-http-cio-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-http-cio:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-http-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-http:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-io-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-io:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-network-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-network:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-serialization-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-serialization:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-sse-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-sse:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-utils-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-utils:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-websocket-serialization-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-websocket-serialization:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-websockets-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-websockets:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.lifecycle:lifecycle-common:2.9.6` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime:2.9.6` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.savedstate:savedstate-compose:1.3.6` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.androidx.savedstate:savedstate:1.3.6` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.animation:animation-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.animation:animation-core-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.animation:animation-core:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.animation:animation:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.foundation:foundation-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.foundation:foundation-layout-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.foundation:foundation-layout:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.foundation:foundation:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.material:material-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.material:material-ripple-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.material:material-ripple:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.material:material:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.runtime:runtime-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.runtime:runtime-saveable-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.runtime:runtime-saveable:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.runtime:runtime:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-geometry-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-geometry:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-graphics-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-graphics:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-text-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-text:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-unit-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-unit:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-util-android:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui-util:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose.ui:ui:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-stdlib:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.9.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring:0.9.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-io-core:0.9.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations:23.0.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jspecify:jspecify:1.0.0` | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.slf4j:slf4j-api:2.0.19` | [MIT](https://opensource.org/license/mit) |

## Additional host-test support (not in APK)

Both targets also execute the same common/controller and shared adapter fixtures.
The complete graphs distinguish versions selected by each target. The Android
host-only kXML dependency exercises real XML through AndroidOpdsXmlReader; its
upstream disabled-DTD default needs a test-only configuration shim because the
Android fork exposes an extra optional feature. This does not replace device tests.
JUnit is EPL-1.0 and stays test-only; Hamcrest BSD-3-Clause, kXML MIT.

| Component | License evidence |
| --- | --- |
| `androidx.activity:activity-ktx:1.7.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.activity:activity:1.7.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.core:core-ktx:1.16.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.core:core:1.16.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.startup:startup-runtime:1.1.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-mock-jvm:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `io.ktor:ktor-client-mock:3.6.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `junit:junit:4.13.2` | [Eclipse Public License 1.0](http://www.eclipse.org/legal/epl-v10.html) |
| `net.sf.kxml:kxml2:2.3.0` | [MIT, published source headers](https://repo.maven.apache.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0-sources.jar) |
| `org.hamcrest:hamcrest-core:1.3` | [New BSD License](http://www.opensource.org/licenses/bsd-license.php) |
| `org.jetbrains.kotlin:kotlin-test-junit:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-test:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |

## Build tooling (not in APK)

AGP/plugins are separate build tools. JNA declares Apache-2.0/LGPL-2.1 options;
JAXB/istack declare EDL-1.0; JDOM has its linked BSD-style notice; Bouncy Castle MIT.
AGP transitive universalchardet declares MPL-1.1. It is tooling only, **not** a
charset detector in INFINILECT. Do not redistribute build/test tool classes as
application classes. SQLDelight adds build-only SQL compiler/migration dependencies, including
JGraphT (EPL-2.0/LGPL alternatives), JHeaps (Apache/LGPL alternatives) and Apfloat
(LGPL-2.1). They never enter runtime/APK. POM terms are preserved, not relicensed.

| Component | License evidence |
| --- | --- |
| `androidx.databinding:databinding-common:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.databinding:databinding-compiler-common:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `app.cash.sqldelight:app.cash.sqldelight.gradle.plugin:2.4.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `app.cash.sqldelight:core:2.4.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `app.cash.sqldelight:dialect-api:2.4.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `app.cash.sqldelight:gradle-plugin:2.4.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `app.cash.sqldelight:migrations:2.4.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.application:com.android.application.gradle.plugin:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.databinding:baseLibrary:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.kotlin.multiplatform.library:com.android.kotlin.multiplatform.library.gradle.plugin:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.analytics-library:crash:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.analytics-library:protos:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.analytics-library:shared:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.analytics-library:tracker:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build.jetifier:jetifier-core:1.0.0-beta10` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build.jetifier:jetifier-processor:1.0.0-beta10` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:aapt2-proto:9.3.1-15703166` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:aaptcompiler:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:apksig:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:apkzlib:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:builder-model:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:builder-test-api:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:builder:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:bundletool:1.18.3` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:gradle-api:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:gradle-common-api:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:gradle-settings-api:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:gradle:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.build:manifest-merger:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.ddms:ddmlib:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.layoutlib:layoutlib-api:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.lint:lint-model:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.lint:lint-typedef-remover:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools.utp:gradle-work-action-api:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools:annotations:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools:common:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools:dvlib:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools:repository:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools:sdk-common:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android.tools:sdklib:32.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android:signflinger:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.android:zipflinger:9.3.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.auto.value:auto-value-annotations:1.6.2` | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.code.findbugs:jsr305:3.0.2` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.code.gson:gson:2.11.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.crypto.tink:tink:1.7.0` | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.dagger:dagger:2.28.3` | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.errorprone:error_prone_annotations:2.36.0` | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.flatbuffers:flatbuffers-java:1.12.0` | [Apache License V2.0](https://raw.githubusercontent.com/google/flatbuffers/master/LICENSE.txt) |
| `com.google.guava:failureaccess:1.0.2` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.guava:guava:33.4.0-jre` | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.j2objc:j2objc-annotations:3.0.0` | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.jimfs:jimfs:1.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.protobuf:protobuf-java-util:3.25.5` | [BSD-3-Clause](https://opensource.org/licenses/BSD-3-Clause) |
| `com.google.protobuf:protobuf-java:3.25.5` | [BSD-3-Clause](https://opensource.org/licenses/BSD-3-Clause) |
| `com.googlecode.juniversalchardet:juniversalchardet:1.0.3` | [Mozilla Public License 1.1 (MPL 1.1)](http://www.mozilla.org/MPL/MPL-1.1.html) |
| `com.squareup:javapoet:1.13.0` | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.squareup:javawriter:2.5.0` | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.squareup:kotlinpoet-jvm:2.4.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.squareup:kotlinpoet:2.4.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.sun.istack:istack-commons-runtime:3.0.8` | [Eclipse Distribution License - v 1.0](http://www.eclipse.org/org/documents/edl-v10.php) |
| `com.sun.xml.fastinfoset:FastInfoset:1.2.16` | [Apache License, Version 2.0](http://www.opensource.org/licenses/apache2.0.php), [Eclipse Distribution License - v 1.0](http://www.eclipse.org/org/documents/edl-v10.php) |
| `commons-codec:commons-codec:1.17.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `commons-io:commons-io:2.16.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `commons-logging:commons-logging:1.2` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `dev.lysine.sql-psi:core:0.8.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `dev.lysine.sql-psi:environment:0.8.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `jakarta.activation:jakarta.activation-api:1.2.1` | [EDL 1.0](http://www.eclipse.org/org/documents/edl-v10.php) |
| `jakarta.xml.bind:jakarta.xml.bind-api:2.3.2` | [Eclipse Distribution License - v 1.0](http://www.eclipse.org/org/documents/edl-v10.php) |
| `javax.inject:javax.inject:1` | [Apache-2.0, published source headers](https://repo.maven.apache.org/maven2/javax/inject/javax.inject/1/javax.inject-1-sources.jar) |
| `net.java.dev.jna:jna-platform:5.6.0` | [LGPL, version 2.1](http://www.gnu.org/licenses/licenses.html), [Apache License v2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `net.java.dev.jna:jna:5.6.0` | [LGPL, version 2.1](http://www.gnu.org/licenses/licenses.html), [Apache License v2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `net.sf.jopt-simple:jopt-simple:4.9` | [The MIT License](http://www.opensource.org/licenses/mit-license.php) |
| `net.sf.kxml:kxml2:2.3.0` | [MIT, published source headers](https://repo.maven.apache.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0-sources.jar) |
| `org.apache.commons:commons-compress:1.27.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.apache.commons:commons-lang3:3.16.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.apache.httpcomponents:httpclient:4.5.14` | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.apache.httpcomponents:httpcore:4.4.16` | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.apache.httpcomponents:httpmime:4.5.6` | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.apfloat:apfloat:1.14.0` | [MIT License](https://opensource.org/licenses/MIT) |
| `org.bitbucket.b_c:jose4j:0.9.5` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.bouncycastle:bcpkix-jdk18on:1.79` | [MIT, tagged upstream license](https://github.com/bcgit/bc-java/blob/r1rv79/LICENSE.html) |
| `org.bouncycastle:bcprov-jdk18on:1.79` | [MIT, tagged upstream license](https://github.com/bcgit/bc-java/blob/r1rv79/LICENSE.html) |
| `org.bouncycastle:bcutil-jdk18on:1.79` | [MIT, tagged upstream license](https://github.com/bcgit/bc-java/blob/r1rv79/LICENSE.html) |
| `org.checkerframework:checker-qual:3.43.0` | [The MIT License](http://opensource.org/licenses/MIT) |
| `org.glassfish.jaxb:jaxb-runtime:2.3.2` | [Eclipse Distribution License - v 1.0](http://www.eclipse.org/org/documents/edl-v10.php) |
| `org.glassfish.jaxb:txw2:2.3.2` | [Eclipse Distribution License - v 1.0](http://www.eclipse.org/org/documents/edl-v10.php) |
| `org.jdom:jdom2:2.0.6` | [JDOM BSD-style license](https://github.com/hunterhacker/jdom/blob/JDOM-2.0.6/LICENSE.txt) |
| `org.jetbrains.compose.hot-reload:hot-reload-gradle-plugin:1.2.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose:compose-gradle-plugin:1.12.1` | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.compose:org.jetbrains.compose.gradle.plugin:1.12.1` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.4.20` | [Apache-2.0, Kotlin plugin marker](https://github.com/JetBrains/kotlin/blob/v2.4.20/license/LICENSE.txt) |
| `org.jetbrains.kotlin.multiplatform:org.jetbrains.kotlin.multiplatform.gradle.plugin:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:fus-statistics-gradle-plugin:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-build-statistics:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-build-tools-api:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-compiler-runner:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-daemon-client:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-annotations:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-idea-proto:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-idea:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-klib-commonizer-api:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-native-utils:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-reflect:2.4.0` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10` | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10` | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-stdlib:2.4.0` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-tooling-core:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-util-io:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-util-klib-metadata:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-util-klib:2.4.20` | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains:annotations:13.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jgrapht:jgrapht-core:1.5.3` | [GNU Lesser General Public License Version 2.1, February 1999](http://jgrapht.sourceforge.net/LGPL.html), [Eclipse Public License (EPL) 2.0](http://www.eclipse.org/legal/epl-v20.html) |
| `org.jheaps:jheaps:0.14` | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jvnet.staxex:stax-ex:1.8.1` | [Eclipse Distribution License - v 1.0](http://www.eclipse.org/org/documents/edl-v10.php) |
| `org.ow2.asm:asm-analysis:9.9` | [BSD-3-Clause](https://asm.ow2.io/license.html) |
| `org.ow2.asm:asm-commons:9.9` | [BSD-3-Clause](https://asm.ow2.io/license.html) |
| `org.ow2.asm:asm-tree:9.9` | [BSD-3-Clause](https://asm.ow2.io/license.html) |
| `org.ow2.asm:asm-util:9.9` | [BSD-3-Clause](https://asm.ow2.io/license.html) |
| `org.ow2.asm:asm:9.9` | [BSD-3-Clause](https://asm.ow2.io/license.html) |
| `org.slf4j:slf4j-api:1.7.30` | [MIT License](http://www.opensource.org/licenses/mit-license.php) |
| `org.tensorflow:tensorflow-lite-metadata:0.2.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |

## Local metadata additions and native notices

The 2026-10-03 inventory now includes the local library/history SQLDelight slice:
Desktop runtime adds five components, Android seven, Desktop tests five, Android
host tests ten and build tooling twelve. No pre-existing component/version was
removed or changed; core graphs remain exactly Kotlin stdlib + annotations.

SQLDelight 2.4.0 and AndroidX SQLite 2.7.1 are Apache-2.0. Xerial SQLite JDBC
3.53.4.0 is Desktop/host-test only: its Apache POM declaration is supplemented by
the bundled Zentus BSD-2-Clause notice and SQLite public-domain dedication, linked
in [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md). The Android APK uses OS SQLite;
it excludes Xerial/JDBC/native libraries as well as test/build dependencies.
SQLDelight schema/compiler/migration transitives are tooling only and retain their
POM/upstream licenses, including LGPL/EPL alternatives. No source transport,
telemetry, permission or core dependency changes.
