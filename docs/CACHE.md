# Cache, downloads and reading progress

This document records the agreed design; storage is not implemented yet.

## Resource lookup

`Reader → ResourceLoader → MemoryCache → DiskCache → Source`

The reader knows only ResourceLoader. On a memory miss, the loader checks disk;
on a disk miss, it calls the owning source. A successful source read populates
disk and memory as permitted by policy. The resource identity includes SourceId,
publication-local ID, resource key and a representation/revision discriminator
when available; persistence must encode components without ambiguous separators.

Memory cache is bounded and session-local. Disk cache persists between sessions
and is automatically evicted primarily by byte budget and least-recent use.
Persist access metadata, count actual bytes, and use atomic writes so interrupted
fetches are never served as complete resources. Disk errors or corrupt entries
must allow a source retry; eviction must not touch downloads or progress.
Staleness validation and content revision handling belong to the loader/source
boundary. Exact budgets and validators will follow real payload measurements.

## Explicit downloads

A user-requested download is persistent storage, separate from the opportunistic
disk cache. Cache eviction cannot delete it. Downloads are removed by explicit
user action. The loader should be able to use a verified downloaded copy as a
local resource through an implementation policy; it is not another evictable cache
tier. No download manager is needed for the foundational commit.

## ReadingProgress

ReadingProgress will live in an independent persistent store keyed by the complete
PublicationId, with a format-aware locator and revision information where needed.
It must survive cache eviction, source unavailability and removal of a download.
Deleting cached bytes must never delete or reset reading progress. A later reopen
can reacquire content and apply the saved locator; changed content may require
locator validation. Do not store progress inside cache metadata or use cascading
cache deletion. SQLDelight can be introduced when this store is implemented.

Verification of the implementation must cover restart persistence, recency/size
eviction, interrupted writes, namespace isolation, and preservation of downloads
and progress when clearing cache. These behaviors are requirements, not tests
claimed to exist today.
