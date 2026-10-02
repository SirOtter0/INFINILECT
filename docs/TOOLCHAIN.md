# Toolchain verification

Official documentation checked on 2026-10-02, before creating build files.

| Component | Latest stable observed | Selected | Evidence |
| --- | --- | --- | --- |
| Kotlin / Kotlin Gradle plugin | 2.4.20 | 2.4.20 | [Release history](https://kotlinlang.org/docs/releases.html) |
| Compose Multiplatform | 1.12.1 | 1.12.1 | [Official release](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1), [compatibility](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html) |
| Gradle | 9.8.0 | 9.7.0 | [Current release API](https://services.gradle.org/versions/current), [Kotlin Gradle compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html) |
| JDK | Not a latest-version choice | 21 | [Gradle JVM compatibility](https://docs.gradle.org/current/userguide/compatibility.html), [Compose desktop requirements](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html) |

Kotlin 2.4.20's fully supported Gradle range is 7.6.3–9.7.0, so select 9.7.0
rather than latest Gradle 9.8.0. Compose documentation says the latest stable
Compose is compatible with the latest stable Kotlin; its compiler plugin must
match Kotlin, so both Kotlin plugins use 2.4.20. Use stable Material rather than
the Material3 alpha listed in the Compose 1.12.1 release. JDK 21 is compatible
with Gradle and exceeds Compose's JDK 11 runtime / JDK 17 packaging minimums.
No Android Gradle plugin, mobile SDK or Xcode dependency is introduced yet.

The Gradle binary distribution was downloaded from the official service and
verified against its published SHA-256 before generating the wrapper. The wrapper
pins that checksum in gradle-wrapper.properties. Release compatibility is evidence
for selection, not proof of a build; see [Verification](VERIFICATION.md) for actual results.
