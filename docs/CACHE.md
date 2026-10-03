# Cache, downloads and reading progress

## Implemented slice

The long-term lookup pipeline remains:

`Reader → ResourceLoader → MemoryCache → DiskCache → Source`

This PR implements only the **first persistent DiskCache**, not an L1 RAM cache.
Both applications inject its ResourceLoader decorator into the existing session:
`TextReader consumer → ResourceLoader → DiskCache → DirectResourceLoader → Source`.
Sources and reader UI do not know a cache exists. Core models/contracts are unchanged.
Policy/container storage are shared in app/jvmSharedMain; platform factories choose
private paths. No database, metadata/search cache or new dependency.
Containers contain only resource identity/integrity and payload: no search results,
Publication metadata, authentication credentials/tokens or reading progress.

This is automatic, best-effort, OS-evictable storage, **never authoritative**.
No offline publication availability is promised: publication metadata/details still
come from the owning source. A miss or failed cache lookup acquires again; source
failure is never replaced with stale bytes from an unversioned entry.

## Revision and identity

Only `PublicationResource.cacheKey != null` can look up or populate reusable bytes.
The source owns a trustworthy revision identifying the actual bytes; weak ETags or
catalog timestamps do not automatically qualify. The identity comprises separate
source ID, publication-local ID, resource key, format, mediaType and revision.
An encoding version and UTF-8 byte-length prefix for every field precede SHA-256.
Names are exactly `e-<64 lowercase hex>.entry`; no opaque source key becomes a path.
Identity encoding is capped at 32 KiB; larger identities safely bypass storage.

**revision=null bypasses disk lookup and writes entirely**, including after restart.
Current Internet Archive resources still have null revisions. Every open therefore
gets fresh metadata and uses the existing validated acquisition/redirect path;
no reusable IA entry is populated. No hash is promoted to a source revision and no
fabricated validator is introduced. The actual TEXT UI uses this loader on Desktop
and Android, but successful persistent-hit evidence uses offline stable-revision
fixtures, not a claim about current IA resources. Gutenberg remains search-only.

## Streaming and atomic publication

A miss returns a tracked single-consumer ResourceContent. Eligible bytes are teed
to a temporary file as the consumer reads; the cache never materializes the full
payload in RAM. The consumer buffer is reused for writes and hashing. Zero-length
reads do not advance; invalid counts, changed known size, truncated/overlong streams
and overflow fail rather than publish. Unknown-size stable resources can cache
their actual bounded byte count after successful EOF.

A single versioned container stores bounded identity, actual byte count, SHA-256
payload digest and bytes. Publication requires EOF, exact known length where supplied,
an active consumer job and successful normal source close. Early close, cancellation,
timeout/read failure, source-close failure or owner shutdown discards the temp file.
Header and payload are flushed, then moved together within the same directory.
ATOMIC_MOVE is preferred; a non-replacing same-directory move is the fallback.
Partial/crash remnants can never pass the length/header/digest checks.

Hits open independent cursors with NOFOLLOW_LINKS, compare the complete encoded
identity and container length, and verify SHA-256 on that **same descriptor** before
returning bytes. Verification uses a 64 KiB buffer and checks cancellation. Cached
files are immutable while owned. Corruption/missing metadata/payload disagreement
is a miss and invalid files are deleted best-effort. A mid-handle I/O failure is
reported rather than silently mixing cached and newly acquired bytes.
The reader's separate 512 KiB TEXT/UTF-8/BOM/EOF policy remains unchanged.

## Budget and approximate LRU

Default budget: **64 MiB** per application cache, configurable down to zero in tests.
The budget counts complete containers (including headers) and live temporary headers/
payloads. Approximate LRU uses last-access file timestamps, restored on restart;
timestamps are advanced monotonically within one owner. Ties use deterministic
digest filenames. Maximum indexed complete entries/live writers: **1024**.
Oldest unpinned entries go first. Age alone does not expire an entry.

Active hit descriptors are pinned and never evicted by the cache. If pinned entries
or other fills leave no room, the new fill is abandoned while valid upstream bytes
continue. Known oversized resources skip writes; unknown-size fills stop caching
when the budget is reached. OS removal remains possible for cache storage.

Only reserved entry/temp names are managed, never unrelated files or directories
outside the namespace. Root/file symlinks fail closed; target bytes outside the
cache are never read/deleted. Abandoned owned temp files are removed only after
exclusive directory ownership is obtained. If cleanup fails, writes are disabled
to avoid accumulating orphan files. Unrelated files are not part of the cache budget.
Unavailable directory/permissions/lock/rename/storage disables or abandons caching;
it does not turn a valid network acquisition into a failure.

## Paths and ownership

- Android: `applicationContext.cacheDir/resource-cache-v1`, app-private internal
  storage, surviving normal Activity recreation/restart but evictable by Android.
  Only the Path is retained; no Activity/Context, external storage or storage permission.
- Linux: absolute `$XDG_CACHE_HOME/org.infinilect.app/resource-cache-v1`, otherwise
  `$HOME/.cache/org.infinilect.app/resource-cache-v1`.
- macOS: `$HOME/Library/Caches/org.infinilect.app/resource-cache-v1`.
- Windows: absolute `%LOCALAPPDATA%/org.infinilect.app/resource-cache-v1`, otherwise
  the per-user `AppData/Local` location. Inherit per-user ACLs.

Relative environment/home paths never become repository-relative storage. Invalid
paths disable caching. POSIX cache directory/files use owner-only permissions.
Storage initialization is lazy, on an eligible resource load, on the I/O dispatcher.

One application owner has an exclusive OS lock per directory. A competing owner
passes through without cleanup/writes; it does not steal the first owner's temp
files. Network calls/full-file verification never hold the store monitor; only
bounded filesystem operations and index changes do. Disk reads/writes run on I/O;
non-suspending close performs short finalization/cleanup, never waits for a transfer.
Same-key concurrent misses have one writer; others independently acquire without
competing publication. Separate handles have separate cursors.

ApplicationSources cancels its active session first, closes tracked cache/source
handles, then releases cache locks and both source clients/engines, idempotently.
Cancelled handoffs and late upstream handles are closed. Session disposal cancels
consumption; application disposal additionally closes all outstanding loader handles.

## Explicit downloads (future)

Downloads will be explicit, persistent user storage, architecturally distinct from
this automatic cache. Cache eviction must never delete downloads. No download API,
manager, persistent library or UI is introduced here.

## Reading progress (independent user state)

Progress now lives in a separate persistent store, keyed by source-scoped PublicationId,
resource key and format. Android uses filesDir, Desktop uses per-user data storage;
never this cache namespace. It survives cache eviction/missing resources, and can
persist for revision=null resources without making their bytes reusable. Progress
corruption cannot invalidate cached bytes. No contents/metadata/credentials/history
are persisted with progress. See [PROGRESS.md](PROGRESS.md) and
[ADR 0015](adr/0015-persistent-reading-progress.md). Downloads and SQLDelight remain deferred.

See [ADR 0014](adr/0014-persistent-resource-cache.md) and actual offline verification
in [VERIFICATION.md](VERIFICATION.md).

## Local library/history separation

The app-private SQLDelight library/history database stores user metadata, never
resource bytes or progress. It lives in persistent application data, outside this
evictable cache. Clearing Library/History cannot evict bytes or reading position;
cache-only deletion cannot delete those stores. A saved entry must re-resolve its
owning source and follow normal acquisition, including Archive null-revision
cache bypass. See [LIBRARY_HISTORY](LIBRARY_HISTORY.md).
