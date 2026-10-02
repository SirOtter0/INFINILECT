# ADR 0004: Resource loading and independent persistence lifetimes

Date: 2026-10-02 · Status: Accepted

## Context

Reading should reuse resources across sessions without losing user-owned state
when opportunistic storage is evicted.

## Decision

Use Reader → ResourceLoader → MemoryCache → DiskCache → Source. Disk cache persists
across sessions and is evicted primarily by size/recency. Explicit downloads are
persistent and separate. ReadingProgress is persisted independently of cache and
downloads, keyed by the full publication identity.

## Consequences

Cache deletion cannot cascade to downloads or progress. The reader stays source-
and storage-agnostic. Atomic writes, byte budgets and restart/eviction tests are
required when implemented. The foundation adds the loader boundary and design.
[ADR 0006](0006-bounded-resource-access.md) defines handle lifetime and partial reads;
[ADR 0007](0007-metadata-and-resource-identity.md) adds revision identity and an
explicit rule against unvalidated reuse when revision is unknown. Storage remains
unimplemented.
