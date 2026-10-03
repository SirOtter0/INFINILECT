# ADR 0014: First persistent automatic resource cache

- Status: accepted for the first bounded disk-cache slice
- Date: 2026-10-03
- Refines [ADR 0004](0004-resource-lifetimes.md) and [ADR 0007](0007-metadata-and-resource-identity.md)
- Preserves [ADR 0011](0011-verified-source-acquisition.md), [0012](0012-bounded-text-reading.md) and [0013](0013-first-android-application.md)

## Context

Desktop and Android share ResourceLoader/ResourceContent and a real TEXT flow.
The domain already has structured, revision-aware cache keys. Current Archive
resources have revision=null; storing their bytes does not establish permission,
freshness or byte identity on the next acquisition. A cache must not weaken those
source checks merely to demonstrate a hit.

## Decision

Keep core and source/reader contracts unchanged. ApplicationSources supplies the
existing session with a disk-caching ResourceLoader, followed by DirectResourceLoader.
Share policy and API-26-compatible JDK/NIO storage in app/jvmSharedMain; platform
factories select private paths. Android receives applicationContext only to obtain
cacheDir and retains no Context. Desktop chooses per-user OS cache locations.
Factories become platform-specific because private Android storage requires that
platform input; shared UI only sees the existing ApplicationSources owner.

Only a non-null source-owned trustworthy revision permits lookup/fill. Null resources
skip storage, preserving all Archive fresh-metadata/CC0/public/redirect/size behavior.
No inferred hash revision, source metadata persistence, stale fallback or Downloads.

Use one immutable binary container with versioned length-prefixed identity, payload
size and SHA-256. Digest filenames contain no untrusted path component. Stream into
a temp file; publish with same-directory atomic/best-effort safe move only after
successful EOF and normal close. Verify full digest/identity/length on the same open
descriptor before a hit is exposed. This adds disk I/O but avoids trusting corrupt
bytes or loading future large resources wholly into RAM.

Default 64 MiB includes complete and temp bytes; max 1024 indexed entries/writers.
Approximate LRU persists use time in file mtime and pins active hit handles. No room
means abandon the fill, not fail a valid source read. One exclusive owner per
directory simplifies inter-Activity/process concurrency; unavailable storage/lock
passes through. Reserved names only, no-follow links, best-effort cleanup; cleanup
failure disables new writes. Close is idempotent and cancels sessions before handles,
cache lock and source transport release.

## Consequences

Restart reuse is tested with stable-revision fixtures. The real Archive TEXT UI
exercises the loader but intentionally cannot demonstrate persistent hits yet.
There is no offline reading guarantee, since publication details remain source data.
The planned MemoryCache → DiskCache → Source pipeline is preserved, with L1 RAM
explicitly deferred. Downloads/progress/library/database/settings remain separate.

JDK/Android standard filesystem/crypto APIs suffice: no dependencies or toolchain
changes. Host tests exercise real files on both targets; actual Android OS eviction,
device filesystem/lifecycle and visual smoke still require device validation.
See [CACHE.md](../CACHE.md) for exact storage, integrity and ownership policies.
