# Bounded EPUB foundation — PR #13

PR #13 introduced **structural EPUB3 preparation, not a renderer**. These validation
and resource-ownership guarantees remain unchanged. Merged PR #15 added a separate
[passive semantic reader](EPUB_READER.md) and original development route; Draft PR #16 extends its bounded semantic
presentation/local raster boundary; production
EPUB acquisition remains disabled. No script execution or acquisition fallback.

## Boundary

```text
PublicationSource → ResourceLoader → ResourceContent
                                       │ bounded streaming, then close
                                       ▼
                            private seekable session ZIP
                                       │ container / CRC / XML / ownership validation
                                       ▼
                           core EpubDocument contract
                                       │ manifest-owned local ResourceContent
                                       ▼
                           bounded semantic chapter parser / Compose reader
```

`core` adds only pure Kotlin EPUB metadata, manifest, spine, canonical internal
path and document contracts. `EpubDocument.openResource(path)` opens an independent
sequential local handle for a manifest-owned entry; it cannot fetch a URL or
expose a filesystem/ZIP/Readium object. The manifest and ordered spine retain media
types, linear reading order and the navigation item's identity. Unicode metadata
is preserved. The package identifier never replaces source-scoped PublicationId
or becomes a cache revision. Identifier/title/language/modified metadata are required by this
supported package subset; optional creators/rights are retained without inferring
permissions.

`app/commonMain` has a small parallel `EpubPreparer` seam, owned on both platforms.
The opener now deliberately hands off an EPUB document to the separate semantic
reader for enabled source options; TEXT remains preferred and no generic engine
registry is added. A structurally valid document still needs renderer checks.

## Finite policy

| Limit | Value |
| --- | --- |
| Acquired/compressed ZIP | 32 MiB |
| Total declared and verified expanded bytes | 64 MiB |
| Individual expanded entry | 8 MiB |
| ZIP entries, including directories | 512 |
| Per-entry expansion ratio | 100:1 (one-byte denominator floor) |
| XML document | 1 MiB |
| XML depth / element count / attributes per element | 32 / 20,000 / 32 |
| Per-node text / attribute value | 8,192 UTF-16 units |
| Manifest / spine entries | 256 / 128 |
| Canonical path length / components | 512 ASCII characters / 32 |
| Archive/entry comments and extra fields | 1,024 bytes each |
| Acquisition and decompression buffer | 8 KiB |
| Serialized preparation deadline | 60 seconds, including waiting for the owner |
| Live prepared documents per owner / entry handles per document | 2 / 8 |

These conservative ceilings establish finite disk, parser and decompression costs
for a foundational subset, independently of TEXT's 16 MiB policy and IA's unchanged
64 MiB source ceiling. Tests can lower limits, never raise production ceilings.
Preparation scans all entries before publishing a document, checking actual size
and CRC against the ZIP directory. CRC detects corruption; it is not a trusted
source revision, authenticity proof or cryptographic signature.

No whole archive or expanded book is held in RAM. Working memory consists of an
8 KiB streaming buffer, at most 512 bounded entry descriptors and bounded container/package and current XHTML
trees, plus bounded metadata/manifest/spine models. Each XML input is at most
1 MiB; trees are not retained by the prepared document. XML decoding/tree
validation may temporarily retain several representations of at most 1 MiB,
rather than an arbitrary publication-sized allocation. Entry reads are bounded
to 8 KiB even when a caller supplies a larger buffer.

## Supported subset and fail-closed checks

- ZIP32 STORED/DEFLATED only. Validate central **and** local headers, offsets,
  sizes, flags, data descriptors, CRC and physical regions before trusting
  `ZipFile`. Reject split/ZIP64 archives, prefix/trailer/gaps/overlap, unsupported
  flags, inconsistent records, duplicate/case aliases and symlink/special modes.
- Require the first local entry to be uncompressed `mimetype`, without extra
  fields, containing exactly `application/epub+zip`.
- Never extract entries. ZIP names are canonical portable ASCII paths; reject
  absolute paths, backslashes, empty/dot segments, schemes, percent encoding,
  query/fragment ambiguity and alternate encoded ZIP names. This intentionally
  excludes legitimate EPUB filenames requiring non-ASCII/URI encoding support.
  Package-relative `.`/`..` references may normalize **within** the container;
  references escaping its root are rejected.
- One `META-INF/container.xml` rootfile, EPUB3 `version="3.0"` package, required
  metadata/manifest/spine and exactly one XHTML navigation item. Reject duplicate
  package IDs/manifest paths, missing entries/idrefs, unsupported fallback/media
  overlay, and non-XHTML spine items.
- Strict UTF-8 XML1.0 only. Namespace-aware SAX with required external-entity
  flags and lexical DTD handler; reject DTD/entity declarations, external
  resolution, processing instructions, XInclude and `xml:base`. Setup fails
  closed if a provider lacks the required protection. Enforce node/depth/text
  limits and cancellation while parsing. EPUB permits some constructs this
  deliberately narrower subset rejects; this is not a full conformance validator.
- Validate all manifest XHTML for basic root/head/body structure, local
  manifest-owned references, duplicate IDs and navigation `toc`. Reject remote
  references, scripts/event handlers and embedded active elements. At the structural-preparation boundary XHTML is not rendered and
  CSS/SVG/images/fonts remain **opaque inert bytes**, not sanitized browser content.
  The separate current semantic reader renders bounded text/local PNG/JPEG; any renderer must independently forbid network/script
  execution and impose its own content sandbox. Fragment existence and full
  navigation semantics are deferred.
- Reject encryption/signature descriptors, including font obfuscation, rather
  than pretending to decrypt/verify them. No DRM/LCP support. Recognizable nested
  ZIP signatures/archive filename extensions are rejected; opaque assets are
  never recursively opened or decompressed as archives.

## Storage, ownership and errors

Android uses `applicationContext.cacheDir/epub-preparation-v1`. Desktop uses the
existing safe per-user cache path policy under `org.infinilect.app`, with the
separate `epub-preparation-v1` namespace (Linux XDG cache or `~/.cache`, macOS
Library/Caches, Windows LOCALAPPDATA). No cwd-relative fallback, storage permission
or Activity retention. A symlink at the owned namespace is rejected; trusted
platform cache-base aliases are canonicalized, as in TEXT preparation.

An owner creates a UUID session with an owner lock and random ZIP filename.
Publication-controlled names never become disk paths. Completed documents retain
their seekable ZIP until close; close invalidates all resource handles immediately
and schedules cleanup on IO. Application close cancels preparations, closes source
and local handles, drains ZIP/file cleanup and releases the lock. Close is
idempotent. Stale cleanup scans at most 128 candidate directories/children and
deletes only owned regular payload/lock filenames after acquiring the session
lock. Active owners and unrelated/symlink files are preserved. Cleanup is
best-effort; this is not a global multi-process disk quota.

The loader's known nonnegative size, when supplied, must match actual EOF exactly.
Unknown size remains bounded by 32 MiB and one excess probe byte; it never becomes
an unlimited allocation. Short/overlong/zero-progress reads, cancelled/failed
transfers, unsupported structure and storage failures never publish a document.
Source handles close on every terminal path, including rejected known size.
Safe fixed failure categories retain internal causes for tests without exposing
paths, publication content or transport details to users. There is no telemetry.

Prepared data is rebuildable session infrastructure, **not ResourceCache or
Downloads**. Deleting cache/preparation data cannot delete Library, History or
ReadingProgress. IA remains public-CC0, fresh-metadata authorized and
`revision=null`, with the same redirect/MIME/size rules and reusable-cache bypass.
Gutenberg remains experimental catalog-only; no OPDS2 link authorizes acquisition.
No real source request is needed by this synthetic fixture suite.

## Platform/API evidence and future work

Official sources inspected on 2026-10-04:

- [W3C EPUB 3.3](https://www.w3.org/TR/epub-33/) — OCF ZIP/container/package,
  manifest/spine and navigation requirements; the implementation is a restricted
  structural EPUB3 subset rather than full EPUB3.3 conformance.
- [Android ZipFile](https://developer.android.com/reference/java/util/zip/ZipFile)
  and [SAXParserFactory](https://developer.android.com/reference/javax/xml/parsers/SAXParserFactory).
- [Android API26 AOSP ExpatReader](https://android.googlesource.com/platform/libcore/+/refs/tags/android-8.0.0_r1/luni/src/main/java/org/apache/harmony/xml/ExpatReader.java)
  — external-entity feature handling and the SAX lexical-handler property.
- [JDK21 ZipFile](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/ZipFile.html)
  and [SAX XMLReader](https://docs.oracle.com/en/java/javase/21/docs/api/java.xml/org/xml/sax/XMLReader.html).

Existing JDK/Android ZIP, SAX, NIO and coroutines suffice; no new dependency,
license, database schema or manifest/permission change. PR #15 explicitly includes
java.xml in the bundled Desktop runtime and adds a separately bounded passive
[renderer](EPUB_READER.md) and typed EPUB locator. Host tests on both Gradle targets
do **not** prove a real Android SAX/filesystem provider. Physical provider/lifecycle
and representative permitted production EPUB checks remain review gates. Readium
is not installed or treated as a Desktop engine; any future Kotlin integration
belongs behind an Android-specific adapter, never in core.

See [ADR 0019](adr/0019-bounded-epub-foundation.md) and
[verification](VERIFICATION.md). No v0.0.1 completion is claimed.
