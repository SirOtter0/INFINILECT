# Third-party notices

Copyright © 2026 SirOtter0 and INFINILECT contributors.
Original INFINILECT code is GPL-3.0-only; upstream components retain their licenses.
No publication files are bundled. This foundation is not a packaged distribution.

## Direct dependencies and build tools

Licenses were checked against upstream license files before the foundation build.
The second technical pass adds no dependencies. Gradle 9.7.1's official tagged
[license](https://github.com/gradle/gradle/blob/v9.7.1/LICENSE) remains Apache-2.0.

| Component | Version | Purpose | License / official source |
| --- | --- | --- | --- |
| Kotlin Gradle plugin, standard library and kotlin-test | 2.4.20 | Compiler, pure core/runtime, tests | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Kotlin Compose compiler plugin | 2.4.20 | Compile Compose UI | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Compose Multiplatform plugin, runtime, foundation, Material and desktop | 1.12.1 | UI and desktop launcher | [Apache-2.0](https://github.com/JetBrains/compose-multiplatform/blob/master/LICENSE.txt) |
| Gradle / wrapper | 9.7.1 | Build tooling, including checked-in wrapper | [Apache-2.0](https://github.com/gradle/gradle/blob/master/LICENSE) |

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

Ktor, SQLDelight and Readium are **not included**. Check their exact versions,
licenses, transitive dependencies and notices when an implementation needs them.
Before any packaged release, produce an artifact-specific notice inventory and
include all required upstream license/copyright files, including native components.
