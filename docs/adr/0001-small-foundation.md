# ADR 0001: Small multiplatform foundation and pure core

Date: 2026-10-02 · Status: Accepted

## Context

INFINILECT needs a universal domain and multiplatform UI without premature modules
or platform dependencies in its contracts.

## Decision

Start with `core` and `app`, using Kotlin Multiplatform common source sets and a
verified JVM/desktop target. Core uses Kotlin only; Compose belongs to app.
Use the official compatible toolchain recorded in [TOOLCHAIN](../TOOLCHAIN.md).
Ktor and persistence integrations stay outside core. Readium, if adopted, belongs
only to an Android-specific implementation behind reader contracts.

## Consequences

Desktop provides a buildable first entry point. Mobile launchers are future work;
common source sets are a portability boundary, not a claim of verified mobile
support. Split modules and configure targets when implementations require them.
[ADR 0008](0008-platform-entrypoints.md) records the concrete Android/shared UI/
desktop migration while retaining the two-module foundation today.
