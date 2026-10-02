# Architecture

> A source obtains publications. A reader displays them. INFINILECT connects both.

## Foundation implemented today

Two modules are sufficient: `core` and `app`. Both use Kotlin Multiplatform source
sets, with JVM/desktop as the only configured targets. More targets and modules
will be introduced with working implementations, not empty placeholders.

`core/commonMain` contains Kotlin-only models and suspend contracts. It has no
Compose, Readium, Ktor, SQLDelight or platform dependency. `app/commonMain` owns the
welcome UI and `app/desktopMain` owns its launcher. The dependency direction is
`app → core`; core never refers to app.

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

## Intended reading flow (not implemented)

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
SQLDelight is deferred until persistent data needs a schema. Readium is deferred
and must remain behind an Android-specific reader implementation; no Readium
objects or dependencies may cross into core or shared reader contracts.

External source definitions will be declarative data interpreted by trusted
engines, not downloaded code. See [Sources](SOURCES.md). Cache, explicit downloads
and progress have separate lifetimes; see [Cache](CACHE.md).

No full reader, engine registry, persistence framework or cache implementation is
claimed by the current foundation. See the [ADRs](adr/README.md).

## Evolution when Android is added (no modules added now)

Keep the two current modules until a working Android entry point needs more.
Follow JetBrains' [AGP 9 migration guidance](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-project-agp-9-migration.html)
and [module configuration guidance](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-project-configuration.html):

1. Turn today's `app/commonMain` into a shared UI library, named `sharedUi`.
2. Move `app/desktopMain/Main.kt`, the desktop application plugin configuration,
   OS runtime dependency and packaging into `desktopApp`, depending on `sharedUi`.
3. Add `androidApp` for Activity, manifest, lifecycle, permissions and application
   packaging, depending on `sharedUi`. Use the Android-KMP library plugin for
   Android targets in shared libraries; do not combine `com.android.application`
   with the Kotlin Multiplatform plugin in a shared module under AGP 9.
4. Keep `sharedUi → core`; both platform apps consume shared UI. Configure core's
   additional target at that point without adding Android APIs to its common code.
   Android-only Readium objects and adapters stay behind the Android reader boundary.

Shared UI source/package boundaries already allow this move without changing the
domain API. No launchers, Android plugins, empty modules or packaging changes are
introduced in this pass. See [ADR 0008](adr/0008-platform-entrypoints.md).
