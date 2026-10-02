# ADR 0008: Keep today's scaffold and separate platform apps when Android arrives

Date: 2026-10-02 · Status: Accepted · Refines ADR 0001

## Context

Today's app contains shared Compose UI and a desktop launcher. JetBrains recommends
shared libraries consumed by platform applications; under AGP 9 the KMP plugin is
not compatible with com.android.application/com.android.library in the same module.
See the official [AGP migration guide](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-project-agp-9-migration.html)
and [configuration guide](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-project-configuration.html).

## Decision

Keep core + app for the desktop-only foundation. When implementing Android, rename/
extract app's common UI into sharedUi; move the desktop launcher, runtime dependency
and application packaging into desktopApp; add androidApp for Activity, manifest,
permissions and packaging. Both apps depend on sharedUi, which depends on core.
Use the Android-KMP library plugin for Android targets in shared libraries and
recheck the toolchain then. Core's common code remains free of platform APIs.
Android-only Readium stays in an Android reader implementation behind pure contracts.

## Consequences

The current commonMain/desktopMain boundary makes that move mechanical and does
not require a new domain abstraction. Shared UI must not acquire launcher/lifecycle
responsibilities while the desktop slice evolves. No Android target, SDK/plugin,
empty module or premature rename is introduced now. The eventual four modules
(core, sharedUi, desktopApp, androidApp) each have an actual consumer/responsibility.
