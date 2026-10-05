# Architecture

> A source obtains publications. A reader displays them. INFINILECT connects both.

## Foundation, search and bounded TEXT reading implemented today

Four modules now have concrete consumers: `core`, `app` (shared KMP library),
`desktopApp` and `androidApp`. The dependency direction is both launchers → app →
core. No empty registry/engine/navigation modules. Keeping the established `app`
name avoids an unnecessary rename; it no longer packages a Desktop application.

`core/commonMain` remains Kotlin-only models/suspend contracts, without Compose,
Readium, Ktor, SQLDelight or Android APIs. JVM and Android are build targets, not
platform code in the domain. `app/commonMain` owns the same shared Compose UI,
ReadingSession, SearchController, opener, TextDocument and TextReader.

`app/jvmSharedMain` is explicitly shared by Desktop and Android: Java-compatible
URI/Base64 validation, metadata/OPDS mapping, Ktor engine-independent transport
and acquisition rules. These APIs are supported at minSdk 26. This is not a claim
that JVM source code works on iOS. `desktopMain` supplies Ktor Java;
`androidMain` supplies Ktor Android (HttpURLConnection). Gutenberg now shares
strict OPDS2 JSON parsing/policy, with no platform XML adapter or legacy fallback.
OAPEN diagnostics and live CLI checks remain Desktop-only.

`desktopApp` owns Window/application/OS runtime. `androidApp` owns MainActivity,
manifest/insets/Android Back and APK packaging. Neither owns source policies.
Platform factories create ApplicationSources without initiating requests. It
attaches the active ApplicationSession (owning ReadingSessions) and cancels it before closing both clients,
idempotently. Composition disposal detaches/cancels sessions; Activity destruction
and Desktop disposal close sources. No Activity/Context is retained by adapters.

The same UI defaults to public-CC0 Internet Archive TEXT reading and offers
Gutenberg as an explicitly selected experimental catalog-only source. No simultaneous search or
registry. The application injects a disk-caching ResourceLoader before
DirectResourceLoader; neutral format selection remains unchanged. No download store.
Core contracts and Archive access/redirect/revision limits
are unchanged. See [ADR 0013](adr/0013-first-android-application.md).

Result-list UI keys project PublicationId to Pair<String, String>: source and
local ID remain distinct, while the JVM/Android pair is serializable for Android
Bundle saveability. Core identities stay platform-free; this UI key is not a
persisted cache key and does not introduce session persistence.

## Domain

`SourceId` is a stable namespace. `PublicationId` is the structured pair of source
ID and local publication ID, so identical local IDs from different catalogs do
not collide. Do not flatten it with an unescaped separator in storage or URLs.
`PublicationType` describes meaning (BOOK, COMIC, MAGAZINE, ARTICLE, DOCUMENT).
`PublicationFormat` describes representation (EPUB, PDF, PAGES, HTML, TEXT).
A publication can offer multiple representations without changing semantic type.

`PublicationResource` is an opaque source-owned resource reference, with a
publication identity, unique resource key, format, media type and optional opaque
content revision. Known revisions produce a structured `ResourceCacheKey`; unknown
revisions never imply safe unconditional cache reuse. It is not a filesystem path
or a reader engine object. `Publication` enforces ownership and resource-key
uniqueness. Empty resources are valid for search metadata awaiting detail retrieval.
`PublicationSource` provides search, details and resource handles; its results must
carry its namespace. Pagination tokens belong to the source.
Source implementations must reject foreign identities and preserve coroutine
cancellation. When a resource has an expected revision, acquired bytes must match
it; a mismatch needs a refreshed reference or an error, never a write under the
old key. Transport/parser limits and richer errors belong to the first adapter.

The first slice also preserves `languages` (empty means unknown), `sourceUrl`
(optional publication detail/canonical URL) and `rights` (optional verbatim source
statement). These support language display, provenance and rights transparency.
Missing rights never imply permission or public-domain status. Adapters validate
URLs and normalize language tags; core rejects blank values but implements no URL
parser or complete language registry. Cover and summary are deferred: title and
authors suffice for the initial result list, without image acquisition or rich
text handling. See [ADR 0007](adr/0007-metadata-and-resource-identity.md).

## Cache pipeline and independent user progress

```mermaid
flowchart LR
    Reader --> ResourceLoader --> MemoryCache --> DiskCache --> Source
    UI --> Search[PublicationSource search/details]
    Source --> HTTP[Trusted engine / Ktor adapter]
    Reader --> Progress[Independent reading progress store]
```

The application chooses a representation and reader implementation by format and
platform. A reader consumes publication metadata and `ResourceLoader`, never a
source adapter, OPDS parser, authentication client or catalog search interface.
The loader routes by SourceId and applies caching before its source fallback.
Sources return metadata; readers render it. Neither owns the other.

The first disk tier is implemented in app/jvmSharedMain; MemoryCache remains
deferred. Persistent reading progress is a separate user-state store. Only stable-revision resources may reuse bytes. Current Archive
null revisions bypass disk lookup/fills and retain fresh metadata/acquisition.
Platform factories choose app-private storage, inject loaders and own closing after
session cancellation. See [CACHE.md](CACHE.md) and [ADR 0014](adr/0014-persistent-resource-cache.md).

## Resource access

`loadResource` and `load` open a fresh, single-consumer `ResourceContent` handle.
It has a nullable nonnegative total `sizeBytes`, a suspend sequential `read` into
a caller-owned ByteArray, and an idempotent non-suspending `close`. A positive read
may be short, returns -1 at EOF, and must never return 0; zero-length reads return
0. Buffer bounds and read-after-close behavior are specified in the interface.
No whole-resource allocation, Ktor stream, JVM InputStream, seeking API or platform
file is part of core's boundary.

The caller closes each handle in `finally`, including after partial consumption,
errors and cancellation. Implementations release acquired I/O if opening fails,
propagate cancellation during reads, and make close abort/release I/O without
suspending or waiting for completion. An incomplete stream cannot become a complete
cache entry. Each cache hit must expose an independent cursor, not share a live handle.

For a small TEXT resource, a reader can explicitly opt into bounded materialization:

```kotlin
val bytes = loader.load(resource).readBytes(maxBytes = 2 * 1024 * 1024)
// Decode using the source's verified charset; UTF-8 only when established.
```

`readBytes` always closes, enforces the actual byte limit even with unknown or
understated size, and probes at most one byte beyond the limit. It preserves the
original read/cancellation error if cleanup also fails. Buffering is bounded;
small partial reads are accumulated without allocating a new chunk for each byte.
Large-resource consumers use `read` incrementally and close in `finally` instead.
Future EPUB/PDF/archive readers may spool to a platform-managed file and use their
engine's random access; this interface promises only sequential access. Introduce
any seek/range capability only with a concrete consumer and implementation.
See [ADR 0006](adr/0006-bounded-resource-access.md).

Ktor belongs in a concrete trusted source/transport implementation outside core.
SQLDelight now implements app-only local library/history metadata; core and the
existing progress/cache stores remain independent. Readium is deferred
and must remain behind an Android-specific reader implementation; no Readium
objects or dependencies may cross into core or shared reader contracts.

External source definitions will be declarative data interpreted by trusted
engines, not downloaded code. See [Sources](SOURCES.md). Cache, explicit downloads
and progress have separate lifetimes; see [Cache](CACHE.md).

No complete reader framework, engine registry or downloads are
claimed. The bounded disk tier is documented separately. See the [ADRs](adr/README.md).

## EPUB preparation and first passive reader (Draft PR #15)

`ResourceLoader → ResourceContent → EpubPreparer → EpubDocument` preserves the
structural ZIP/CRC/XML/manifest boundary established by PR #13. A separate bounded
parser creates chapter blocks, styled text runs and manifest-owned internal targets;
shared EpubReaderController/Compose presentation never handles HTTP, filesystem URLs,
browsers or scripts. Core gains only a typed layout-independent EPUB progress locator.
Two parsed chapters and one serialized navigation parser are retained; no whole-book
DOM, CSS execution or image decoding. Document/resource ownership closes on error,
cancellation, Back/replacement and application disposal. TEXT/PR #14 are unchanged.

The neutral opener prefers offered TEXT, otherwise selects EPUB only for an enabled
application source. Successful first-chapter parsing precedes History. Library and
History still re-resolve IDs; prepared ZIPs remain disposable cache infrastructure,
separate from resource cache and persistent user metadata/progress. Historical TEXT
progress stays v1; EPUB adds v2/tag2 without SQL changes. [Reader subset/research/
limits/manual route](EPUB_READER.md), [foundation](EPUB.md),
[ADR 0021](adr/0021-semantic-epub-reader.md).

Production IA EPUB and Gutenberg acquisition remain disabled. Android debug and
explicitly opted-in Desktop expose an original development EPUB source exercising
the actual ResourceLoader/preparation/rendering/persistent-state pipeline. This is
not a source registry or production catalog. Desktop's bundled runtime explicitly
includes java.xml for the shared SAX parser; Android uses its existing API26+ provider.

## Search slice

`SearchScreen → SearchController → PublicationSource → GutenbergSource → Ktor/OPDS`.
The same controller works with InternetArchiveSource; no duplicated search logic.
Shared UI observes Idle, Loading, Results, Empty and Error through StateFlow and
never parses source payloads or handles HTTP. Only submit/Next page actions initiate requests;
busy actions are ignored and the source serializes HTTP operations. Pagination
replaces the displayed page, keeping memory bounded; a failed next-page request
keeps the previous results and token for an explicit retry. Cancellation propagates
and restores the previous/idle state. No cache, retry loop or prefetcher is involved.

GutenbergSource is an experimental trusted adapter outside core. OPDS2 search and
fresh details use bounded JSON; `loadResource` explicitly rejects unsupported
acquisition. The old XML feed and RDF download proposal are removed, not a fallback.
[Sources](SOURCES.md), [corrected ADR0018](adr/0018-gutenberg-catalog-acquisition.md).
Historical ADR0009 describes the original search-only XML slice, not current policy.

## First end-to-end TEXT reading slice

```text
SearchScreen → SearchController → PublicationSource.search
explicit Open text → OpenPublicationController → PublicationSource.getPublication
→ selectResource(TEXT) → disk-caching ResourceLoader → DirectResourceLoader → PublicationSource.loadResource
→ ResourceContent → bounded strict UTF-8 loading → TextDocument → TextReader
```

Internet Archive enables Open text through the neutral reader path; Gutenberg is
catalog-only and has no reader/source-specific UI branch.
The opener verifies detail identity and selects the first advertised TEXT resource
with the existing source-neutral helper. No PDF/EPUB fallback. Acquisition still
refreshes permissions/location metadata within InternetArchiveSource; its public
CC0 scope, fail-closed redirects, 64 MiB source cap and null revisions are unchanged.

The app-level **16 MiB** preparation cap is separate and lower than the source's
64 MiB acquisition limit. Require a known, positive stable size and exact EOF,
with at most one excess probe byte. Strict incremental UTF-8 decoding/indexing
uses at-most-8-KiB requests, carries split sequences and closes ResourceContent on
all terminal paths. One leading BOM is omitted logically; interior BOMs remain.
Empty/whitespace/NUL-bearing text is rejected. No whole-book String/ByteArray.

Application-owned FileTextPreparer writes a private session file and builds two
bounded primitive byte/code-point indexes. TextDocument exposes total code points,
window lookup and suspend bounded window reads without source/transport/filesystem
APIs. Each window is at most 2,048 points; cache at most eight decoded windows.
Shared Compose lazily lays out visible windows. PR #14 keeps stable global
code-point item keys, measured UI-only slot heights across decoded-window eviction,
and a single generation-checked loading worker with two prefetched neighbors per
side. Its eight-window UI LRU is separate from the existing eight-window local
file LRU; only composed layouts retain additional windows. Heights reset for new
width/font/density and never enter user persistence. Initial restoration scrolls
once; placeholder loading never writes EOF. [Viewport decision](adr/0020-stable-text-viewport.md).
Production preparation/window IO
runs on Dispatchers.IO. Android uses cacheDir/reader-text-v1; Desktop uses private
per-user cache conventions. Normal close removes files off the UI thread; owner
locks permit conservative stale cleanup. These files are neither ResourceCache
nor Downloads. [TEXT policy](TEXT_READER.md), [ADR 0017](adr/0017-indexed-text-document.md).

OpenPublicationController owns a job in the session's UI scope and exposes
Idle/Loading/Ready/Error. Ignore duplicate Open while Loading, bound the whole
operation to 60s, propagate cancellation and use an operation generation to
prevent stale Ready/Error after Back. Errors are fixed safe messages; no transport
exceptions/URLs/paths pass to the UI. TextReader receives only TextDocument and a
Back/logical-position callbacks, with title, plain text, approximate whole
percentage and vertical scroll.

ReadingSession owns one SearchController, opener, query and search job. UI-thread
actions are serialized by the UI dispatcher; preparation work returns to that scope
before state publication. ApplicationSession now owns the small Search/Library/History destinations;
Idle shows the selected destination, Loading/Error the opening screen, Ready the
reader. Back resets opening state and returns to its origin, keeping Search
source/query/results; it does not refetch. Switching
source closes/discards the old session, creates a fresh one and resets Compose
collectors using a session key, so old-source results cannot flash or replace new
results. No request is started by changing source. Disposal cancels session jobs
and closes platform sources. Query/results are session-local; progress and library/history metadata persist
independently of current Archive bytes (its revisions are null). Automatic disk-cache
infrastructure is separate from session state. See [ADR 0012](adr/0012-bounded-text-reading.md).

## Acquisition verification and lifecycle

OAPEN REST access remains blocked, but official OAI metadata with download links
is accessible; actual PDF transfer is still unverified. Preserve the original
[ADR 0010](adr/0010-oapen-verification-gate.md) finding and see
[ADR 0011](adr/0011-verified-source-acquisition.md) for the verified Archive route.
Both OAPEN diagnostics remain independent opt-in tasks, not source implementations.

The neutral consumer uses ResourceLoader, selects a caller-supported format and
closes its prefix-test handle. Archive validates ownership/fresh permissions and
opens a sequential HTTP stream. Its producer keeps Ktor's public scoped streaming
response alive until close/cancellation; close aborts without waiting for transfer
or materializing the file. A later consumer must open a fresh handle. That acquisition
slice introduced no cache, registry or simultaneous-source search. The later bounded
TextReader preparation uses a fresh handle and consumes the entire eligible bounded resource;
the historical prefix check remains independent.

## First Android target and lifecycle

[ADR 0008](adr/0008-platform-entrypoints.md) is retained as historical planning;
[ADR 0013](adr/0013-first-android-application.md) applies its boundaries with `app`
retained as the shared library name. Both pure core and app use the official
Android-KMP library plugin; only androidApp applies com.android.application with
AGP built-in Kotlin. There is no application/KMP plugin combination in one module.

App receives a composable platform Back callback, with no Android imports in
shared UI. Enabled in Loading/Ready/Error and Library/History, Android Back calls application.back:
cancel the opener, invalidate late responses, retain source/query/results. At
root Search, system Back follows Activity behavior. Insets/keyboard padding belong
to the Android launcher; source-choice buttons share available width on phones.
TextReader's title/plain text/vertical scroll/Back remain unchanged.

Recreation/configuration changes/process death lose in-memory session/document/
pixel scroll state by design. A logical progress locator persists in filesDir and
restores after another explicit valid open. Destruction cancels old work/closes clients;
progress drains on a bounded independent writer. No ViewModel/SavedState is added.
Manifest denies cleartext and backup, requests INTERNET only directly, and retains
AndroidX's generated app-scoped signature permission protecting non-exported
receivers. It is not storage/location/identifier access. Disable EmojiCompat's
automatic downloadable-font initializer so app startup does not request fonts.
No telemetry, login or background source requests. The standard debug APK uses
Android's debug signing only; no release key, distribution or release build goal.

## Persistent reading progress

```text
PublicationSource / ResourceLoader → publication bytes → Reader
                                                        │
                                                  logical locator
                                                        ▼
                                                ReadingProgressStore
```

Resource cache ≠ reading progress. Core owns pure identity/typed logical locator/
normalized progression/store contracts. App owns sparse code-point mapping, current
layout approximation, fixed 2s save windows and generation-safe flush on Back/close.
ProgressPersistence is application-owned and drains without retaining Compose or
Activity objects; store operations run on IO. Android uses filesDir, Desktop uses
per-user persistent data paths. Versioned bounded digest-named atomic files preserve
user state independently of byte revisions/cache eviction. No contents or network
requests are involved. Details/limits in [PROGRESS.md](PROGRESS.md) and
[ADR 0015](adr/0015-persistent-reading-progress.md); historical ADRs remain unchanged.
Android permissions use the no-follow POSIX path attribute view, avoiding the
unsupported FileStore query. Durable restart tests instantiate new stores/writers;
same-process restoration through RAM is explicitly insufficient evidence of a save.

## Persistent local library/history

Core defines bounded metadata snapshots and repository contracts, without resources
or SQL APIs. App generates SQLDelight 2.4.0 schema v1 for library/history and ordered
author/language child rows. IO-only platform drivers use private persistent database
storage, separate from resource-cache-v1 and reading-progress-v1. Library mutations
reflect committed storage; history is captured only after a successful reader open.
ApplicationCollections owns repositories/finite history queue, ApplicationSession
owns small navigation, and both reuse the existing ReadingSession/opener.

CollectionsController now loads one bounded Library list into a source-scoped
membership set for catalog results and the reader. Unknown membership disables
Add/Remove until storage responds; storage failures have fixed errors and an
explicit retry. One pending mutation per PublicationId suppresses repeated taps
without disabling unrelated rows. PublicationSnapshot.from(publication) stores
metadata only, with visible membership updated after repository commit, followed
by an authoritative list refresh. Request generations/cancellation reject stale
list responses. No per-result database lookup, source operation, acquisition,
History capture or progress update is part of a catalog Library action.
Reader and catalog actions share this controller; normal source-resolved opening
and Search/query/results/pagination/Back behavior remain separate and unchanged.

Saved entry → PublicationId → owning PublicationSource.getPublication → normal
ResourceLoader/strict TEXT decoding → Reader + unchanged progress restoration.
Stored metadata/rights/URLs never replace current source permission checks.
Clearing history/removing library affects only its own table. Details, bounds,
migration and failure policy in [LIBRARY_HISTORY](LIBRARY_HISTORY.md) and
[ADR 0016](adr/0016-local-library-history.md).

## Experimental Gutenberg catalog boundary

`GutenbergCatalog` owns observed OPDS2 discovery/search/details. The shared JSON
parser validates bounded current metadata and stable numeric ID/self links.
All Gutenberg Publications have empty resources: delivery URLs and optional
size/length extensions are inert, excluded from catalog authority; `loadResource`
is explicitly unsupported. First Search discovers root once
per source lifetime, then requests one page; subsequent pages are user-triggered.
The development service is labelled experimental and IA is the initial source.
No legacy XML, RDF, file-download or mirror fallback remains. Core/reader/progress/
cache/collections APIs and source security are unchanged. Existing Gutenberg user
metadata/progress are kept, but unsupported opens create no successful History.
[Policy/evidence](GUTENBERG.md), [ADR0018](adr/0018-gutenberg-catalog-acquisition.md).
