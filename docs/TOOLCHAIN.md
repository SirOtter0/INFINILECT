# Toolchain verification

Official documentation checked on 2026-10-02 for the foundation and rechecked for
the second technical pass before updating the wrapper.

| Component | Selected | Official evidence |
| --- | --- | --- |
| Kotlin / Kotlin Gradle plugin / Compose compiler plugin | 2.4.20 | [Kotlin release history](https://kotlinlang.org/docs/releases.html), [KGP compatibility](https://kotlinlang.org/docs/gradle-configure-project.html) |
| Compose Multiplatform | 1.12.1 | [JetBrains release](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1), [release API](https://api.github.com/repos/JetBrains/compose-multiplatform/releases/tags/v1.12.1), [JetBrains compatibility guide](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html) |
| Gradle | 9.7.1 | [Patch release notes and recommendation](https://docs.gradle.org/9.7.1/release-notes.html), [Gradle compatibility](https://docs.gradle.org/9.7.1/userguide/compatibility.html) |
| JDK | 21 | [Gradle JVM compatibility](https://docs.gradle.org/9.7.1/userguide/compatibility.html), [Compose desktop requirements](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html) |

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
2.4.20. No Android Gradle plugin, SDK or Xcode dependency is introduced yet.

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
