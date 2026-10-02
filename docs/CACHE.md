# Cache, downloads and reading progress

This document records the agreed design; storage is not implemented yet.

## Resource lookup

`Reader → ResourceLoader → MemoryCache → DiskCache → Source`

The reader knows only ResourceLoader. On a memory miss, the loader checks disk;
on a disk miss, it calls the owning source. A successful source read populates
disk and memory as permitted by policy, committing only after complete verified
consumption. Early close, cancellation or a read failure must discard incomplete
cache writes. A handle does not imply that a full resource fits in memory; future
memory entries must obey a byte budget and disk fills can consume chunks.

`PublicationResource.cacheKey` is a nullable `ResourceCacheKey` comprising the full
PublicationId (SourceId + local ID), resource key, format, media type and a known,
nonblank revision. The source owns the opaque revision: it must change whenever
bytes change, for example a strong validator, immutable version or verified content
digest. A weak ETag or catalog metadata timestamp is not sufficient unless the
trusted engine can guarantee byte identity. Changing representation or revision
creates a different key; never write new bytes under the previous key.

A null revision produces no key for unconditional reuse. An unversioned reference
must be resolved/revalidated with its source before any retained bytes are reused;
source unavailability cannot silently make an unknown-revision cache entry current.
If the source cannot establish freshness, fetch again. A digest after acquisition
can identify those bytes but does not establish that the source has not changed
on a later session. Explicit verified downloads have their own persistent policy.
These rules prevent unknown revisions from becoming an unlimited stale-cache fallback.

Persistence must store separate fields (including both PublicationId components),
or use a versioned length-prefixed encoding before hashing; never concatenate with
an unescaped delimiter, serialize `toString()` or persist runtime `hashCode()`.
No cache serializer or cache implementation is introduced yet. See
[ADR 0007](adr/0007-metadata-and-resource-identity.md).

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
