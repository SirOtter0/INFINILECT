# Dependency independence audit — PR #15

Audited on 2026-10-05, starting from exact PR #15 HEAD
`557fb6b43148879ee88f2d842c197f4072730d9f`, base/main
`0968a36e3401a1ba86ef18a4098c2a7f69ce4e0c`. GitHub reported Open, Draft and
MERGEABLE. After the Back fix, the user reported successful physical Android testing,
including corrected EPUB system Back. That is user-supplied evidence, not a device
test performed by this audit. No production-code correction was required.

## Scope and conclusion

Inspected all tracked production Kotlin source sets, core/app contracts, qualified
type references as well as imports, module/build/dependency declarations, SQL schema,
composition roots, launchers and contract/implementation test boundaries. Historical
dependency notices do not override the current Gradle declarations. This is an
architectural coupling audit, not a dependency substitution experiment or a new
runtime/security certification.

**No external transport, storage or parser type leaks into core or shared application
contracts.** Core has no production dependency declaration beyond Kotlin/build
targets and no platform/library imports. Shared application controllers/state depend
on core/owned application contracts plus Kotlin and coroutines. They do not import
SQLDelight, Ktor, serialization JSON, NIO, SAX, ZIP or Android. Compose imports occur
in App, the Back binding and the two reader UIs, plus platform launchers; no business,
storage or parsing implementation needs Compose.

The important distinction is usage versus availability: app/commonMain declares
SQLDelight runtime for generated infrastructure, and the `.sq` definitions live
under commonMain. Neither its generated database classes nor query/driver types
appear in application/session/reader/UI contracts. The app is not published as a
general-purpose SDK promising its generated implementation classes as public API.

## Current boundary map and replacement experiments

Infrastructure types below do not enter core or business/application contracts.
Compose is the deliberate framework at the UI boundary. Infrastructure helpers can
legitimately exchange backend types within their own implementation/wiring layer.

| Dependency / concern | Current implementation and imports | Owned boundary and selection | Replacement impact / persistent data |
| --- | --- | --- | --- |
| SQLDelight, SQLite/JDBC | `SqlCollectionsStore.kt`, generated `LocalCollectionsDatabase`/queries and `Collections.sq`; Android/desktop collections drivers. SQLDelight imports occur in the store and those two drivers. AndroidX SQLite and JDBC also stay there. | `LibraryRepository`, `ReadingHistoryRepository`, `PublicationSnapshot`, `LocalStoreResult`. Platform `CreateApplicationSources` injects repositories into `ApplicationCollections`; the application owner/controllers use no driver/query types. | Replace store, platform drivers, generation/dependency configuration and real database integration tests. Room is Android-specific: Desktop needs its own implementation of the same contracts. Preserve/import user metadata, schema/version/transactions and ordered author/language rows; no reader/session/source/UI rewrite. |
| Ktor | `GutenbergSource`, `InternetArchiveSource`, their URL builders, `platformHttpEngine` expect/actual and desktop OAPEN probes. Constructors of concrete adapters accept Ktor engines for backend testing. | `PublicationSource`, `SearchPage`/`Publication`, `ResourceLoader`, `ResourceContent`; owned `SearchException` for safe application errors. `network/CreateSources` constructs sources; platform engine factories select Java or Android engine. | Rewrite transport code, HTTP-library-dependent URL building, engine factories, probes and MockEngine integration tests. Keep source authorization, bounded bytes, cancellation and redirect policies. No domain/UI/reader/cache/progress/collections edits or user-data migration. Ktor-specific causes can be retained internally for errors; application code never interprets them as transport policy. |
| kotlinx.serialization JSON | `ArchiveMetadata.kt`, `GutenbergOpds2Parser.kt`; JSON elements/private helpers stay inside these adapters. There is no serialized Publication object or JSON node in a business contract. | Parsers output owned Publication/SearchPage or source-internal records containing owned values. | Replace bounded JSON decoding/mapping implementation and parser-specific tests. Existing behavioral fixtures, metadata identity and source policy must remain. No Library/History/progress migration, since these are not their persistence codecs. |
| Filesystem / NIO | Disk cache/store/identity codec, file progress store, file TEXT/EPUB preparers, platform path factories and diagnostics; `Path`, channels, locks and permissions are implementation/wiring types. | `ResourceLoader`/`ResourceContent`, `ReadingProgressStore`, `TextPreparer`/`TextWindows`, `EpubPreparer`/`EpubDocument`. Platform roots supply private paths. | Replace the corresponding adapters/path wiring and filesystem integration tests, preserving bounded IO/close/cancellation semantics. Disposable cache/preparation can rebuild. Persistent progress requires non-destructive migration if its codec/backend changes; metadata remains independent. |
| Resource cache backend | `DiskResourceCache` decorates an upstream loader; `DiskCacheStore` handles files, digest/integrity, commits and eviction. Consumers cannot obtain its Hit/Write/channel objects. | `ResourceLoader.load` returns only `ResourceContent`. Platform/createSources owns the concrete cache and release callback. | Replace decorator/backend plus composition. No extra cache-backend interface is needed for current consumers. Old cache entries can be discarded; preserve structured identity, trustworthy-revision requirement, null-revision bypass and active-handle lifecycle. No user-state migration. |
| Progress backend | `FileReadingProgressStore` owns record bytes/checksums/locks/NIO and safe diagnostics. | `ReadingProgressStore` + pure identity/typed locators. `ProgressPersistence` accepts that interface; createSources selects the file implementation and clock. | Replace store and wiring; migrate/import committed TEXT v1/EPUB v2 records if needed. Reader controllers and semantic locators remain unchanged. A resource-owning replacement needs explicit writer-drain-before-backend-release ownership, discussed below. |
| ZIP / EPUB / CBZ structural storage | `app.zip.BoundedZip` owns shared ZIP32 inspection, central/local agreement, path/type/size/CRC validation. `EpubZip` adds only EPUB mimetype/package policy; `CbzPagePreparer` adds strict raster-page policy. `ZipFile`, archive paths, CRC and `Path` stay in jvmShared adapters. | EPUB returns core `EpubDocument`; CBZ returns core `PageDocument` with synthetic ordinal keys. Both expose bounded `ResourceContent`; the composition root selects private temporary storage. | Replace ZIP/provider/storage adapters and real integration tests while preserving shared ZIP32 defenses and each format's separate semantics. Prepared archives are disposable. No change to source identity, Library/History or semantic progress. |
| XHTML / SAX | SAX is imported only in `EpubXml.kt`; the separate desktop OAPEN XML diagnostic uses StAX. `EpubXmlNode` is adapter-internal to package/presentation parsing, not an application model. `BoundedEpubParser` implements EpubParser. | `EpubParser` returns `EpubChapter`, `EpubBlock`, `EpubRun` and owned TOC/targets. `defaultEpubParser` actual selects the implementation; opener/controller accept injectable EpubParser. | A SAX-provider substitution can stay inside EpubXml/provider tests and retain its owned intermediate tree. A different semantic parser replaces BoundedEpubParser and, if necessary, its helpers; adjust the factory. Preserve passive-content validation, limits, cancellation and semantic element-path/Unicode-offset conventions. A parser-library substitution must not silently reinterpret persisted locators. No acquisition/session/collections rewrite. |
| Compose / renderer | Shared App, ApplicationBackHandler, TextReader and EpubReader plus the Android/Desktop launchers. Semantic chapter/block/run/locator models have no Compose types. | UI consumes owned session/controller StateFlows and document models; platform Back is injected as a UI callback. | A UI-framework replacement necessarily rewrites UI and launcher integration. A richer renderer can consume the same semantic boundary; rendering a larger EPUB subset/browser would need an explicit reader-local security/locator adapter, not a claim of a zero-file drop-in swap. Sources/collection repositories/persistent publication identity remain unaffected. |
| EPUB user preferences / filesystem | FileEpubReaderSettingsStore in jvmSharedMain contains NIO/checksum/file-lock/atomic-write implementation. Platform CreateSources selects the private persistent directory and injects the store into application-owned EpubSettingsPersistence. | EpubReaderSettingsStore and EpubReaderPreferences; reader/session use only owned validated settings and persistence lifetime, with no Path, SQLDelight, SharedPreferences or Compose persistence types. | Replace the adapter and composition wiring, optionally import its small versioned user-preference record. No progress/cache/schema/source/reader-business rewrite. Preference failure/restart tests use both owned fake stores and real files/new owners. |
| Android / Desktop/JVM | Activity/insets/Back and Window/runtime packaging in launchers; Context/Log/engine/driver/private-path selection in platform factories. Java-compatible implementation is deliberately in jvmSharedMain for Android/Desktop, not claimed portable to iOS. | ApplicationSources/session-owned lifetimes, owned document/storage/source contracts, expect/actual infrastructure factories and the composable platform Back callback. | Replace platform adapters/composition, source-set/build configuration and platform tests. Shared business/session models remain. Preserve/import platform-private user data if its storage location changes. Supporting iOS requires new platform infrastructure, not moving JVM APIs into common/core. |

The current rule is documented in [ARCHITECTURE](ARCHITECTURE.md#dependency-ownership).
Replacing infrastructure is not equivalent to changing only a Maven coordinate.
Implementation-specific tests are expected to change; behavior tests through the
owned contracts must remain. Current collections/progress/source/opener/EPUB-controller
tests use fake owned repositories/stores/sources/loaders/parsers alongside actual
SQLite/file/ZIP/parser and MockEngine integration coverage.

## PR #15-specific assessment

`EpubPreparer`, `EpubDocument`, `EpubParser`, chapter/block/run/TOC models and
`EpubReaderController` expose no SAX nodes, ZipFile, File/Path, browser, Readium,
Ktor or Compose types. Progress is an owned typed locator, not a parser object.
Its element-child ordinal path is an INFINILECT semantic convention, so a new parser
must preserve it or deliberately handle restoration compatibility; it is not a SAX
API. Rendering/layout changes do not alter persisted pixels because none are stored.

EPUB and TEXT both use the existing source re-resolution/ResourceLoader boundary.
Library snapshots still cannot authorize bytes. SQLDelight schema and dependencies
are not changed by EPUB. Prepared data, cache and progress have independent contracts
and lifetimes. No production EPUB/Gutenberg acquisition is newly enabled by this audit.

Android's BackHandler remains only a platform binding. Shared ApplicationSession owns
`back()` and destination semantics; the reactive shared Compose bridge observes the
owned opening/destination states. No Android dispatcher/navigation type occurs in
ApplicationSession or the EPUB controller. Physical system Back success is reported
by the user; the existing host/runtime tests cover the shared observation/lifecycle.

## Deliberate limits and deferred improvements

- **Logical, not fully separate Gradle infrastructure modules.** Shared app UI and
  infrastructure are in one small module, so classpath availability alone does not
  enforce every rule. Source-set/type/import checks establish current usage. If the
  repository grows, consider a focused automated boundary check or a real-consumer
  module split. Do not add empty modules or one interface per implementation now.
- **Transport and source policy share an adapter.** Replacing Ktor affects both source
  implementations/URL builders and their transport tests. Policy semantics must be
  preserved during that port. A reusable transport seam is a future option only if
  another concrete transport consumer/implementation justifies it.
- **Composition root naming.** `network/CreateSources.kt` also wires cache, progress,
  collections and preparers. Its concrete types are legitimate composition, not
  dependencies of ApplicationSession. Renaming/moving it for clarity can be done
  later; no speculative factory/registry refactor in this reviewed PR.
- **Progress-store resource ownership.** The current file store owns no long-lived
  driver. A future database/cloud/encrypted backend that does own resources must be
  released after ProgressPersistence drains, via explicit application-owned lifecycle
  wiring (potentially a drain/release callback), just as collections already drain
  before driver close. The store contract documents separate resource ownership;
  no speculative closable-store hierarchy is added. Reader/session logic need not
  learn the backend type. Cache/preparation and persistent user data stay separate.
- **Local-only progress policy.** ReadingProgressStore currently explicitly forbids
  network operations. A direct CloudReadingProgressStore is therefore not a compliant
  drop-in under today's policy. Future cloud sync requires a separate consent/privacy
  decision, preferably retaining a local reader-facing store with synchronization
  outside it, or explicitly revising that policy. This is a product/contract guarantee,
  not a file-library dependency; it need not make readers aware of networking.
- **Not library-neutral at every level.** Kotlin/coroutines and Compose are deliberate
  language/concurrency/UI choices. Wrapping primitives, StateFlow or every composable
  would obscure real boundaries rather than improve infrastructure replacement.

No production leak was found that warrants changing the physically validated PR #15
implementation. This follow-up adds documentation only. No substitution, runtime
data migration, dependency change, new source request, APK rebuild or new device
test was performed. Documentation/import/type/diff checks are recorded in
[VERIFICATION](VERIFICATION.md); preceding clean build evidence remains historical.

## Shared ZIP validation and bounded CBZ (Draft PR #18)

`app.zip.BoundedZip` is an app-owned jvmShared adapter, not a core abstraction. It
validates ZIP32 records, entry paths/types, header agreement, bounds and CRC for both
formats. `EpubZip` maps that metadata to EPUB-owned path/package rules; CBZ validates
the strict raster-only subset and maps archive entries to PageDocument's synthetic
ordinal page keys. Neither ZipFile, ZIP path, filesystem path nor parser metadata
crosses into PageReader/core. Replacing ZIP/JDK storage changes this adapter,
composition path and ZIP/provider integration tests; EPUB parsing policy, PageReader,
source contracts and persisted PAGE progress remain separate. No dependency was added.

## PR #16 media boundary (2026-10-05)

The earlier PR #15 audit above is historical evidence. PR #16 adds one justified
owned adapter boundary: EpubRasterDecoder accepts bounded local bytes/MIME and returns
EpubRaster (dimensions/ARGB array), never Android Bitmap, ImageIO/Skia or Compose types.
Semantic EpubImage has only canonical manifest path, media type and bounded alt text.
EpubMediaController owns validation, visibility/retention/generations and document
handles. Android/desktop actual factories select BitmapFactory/ImageIO implementations.
The expect/actual ImageBitmap conversion is explicitly UI-only, not a semantic or
application contract. Replacing a decoder changes its adapter and implementation
integration tests; changing UI technology changes presentation, not source permission,
progress identity, repositories or chapter parsing. No new dependency is introduced:
Desktop tests reuse desktopApp's existing Compose/Skiko runtime to exercise the real
conversion headlessly; java.desktop is explicitly bundled for ImageIO. Coroutines
and ordinary pixel arrays are not mechanically wrapped in hypothetical abstractions.

## Page reading and neutral raster adapters (Draft PR #17)

PagePreparer returns pure PageDocument/ordered PageEntry; the current individual-page
adapter uses ResourceLoader, never an acquisition URL. PageReaderController consumes
only those owned values and RasterDecoder. Raster owns validated ARGB pixels, not
Bitmap/Skia/ImageBitmap; UI-only conversion supplies Compose images. PR #16's hardened
PNG/JPEG preflight/providers are shared low-level adapters, while EPUB/Page semantic
models and controllers stay separate. No EPUB manifest/spine concepts enter pages.

PageReaderSettingsStore is a separate replaceable global-mode contract; the file
adapter/composition contain all NIO/private-path/version/checksum concerns. Changing
page acquisition, raster decoder or settings backend changes the corresponding
adapter/wiring/tests and (for user preferences) migration, not Library/History,
source identity, semantic progress or other reader business logic. Future CBZ/web
sources feed the same PageDocument. No PDF engine is hidden behind comic semantics.
No new third-party library or framework wrapper is introduced.
