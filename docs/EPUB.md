# Bounded EPUB preparation — EPUB2/EPUB3 subset

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
or becomes a cache revision. Identifier/title/language metadata are required. EPUB3 also requires
`dcterms:modified`; EPUB2 has no fabricated modification timestamp (`modified=null`); optional creators/rights are retained without inferring
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
| Canonical path length / components | 512 UTF-16 units / 32 (ZIP name still ≤513 UTF-8 bytes) |
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
- Never extract entries. Canonical ZIP paths preserve literal spaces and valid NFC
  UTF-8 names, case sensitively. Reject absolute paths, backslashes, empty/dot
  entry segments, controls, private-use/noncharacter scalars, schemes, raw `%`,
  query/fragment ambiguity and alternate encoded ZIP names. Reject Unicode
  normalization and conservative case aliases (including expanding case mappings).
  Non-NFC names are deliberately unsupported rather than normalized onto a payload.
- URI references are parsed as relative paths, with literal-space compatibility
  and strict UTF-8 percent decoding **once per segment**. `+` stays `+`.
  `Images/cover image.jpg` and `Images/cover%20image.jpg` resolve to the same literal
  entry. OPF/nav/NCX/XHTML references use their containing document's directory;
  container rootfile references use the archive root. Literal `.`/`..` may resolve
  within the archive; root escape, encoded dot segments, encoded separators,
  decoded `%`/double encoding, malformed escapes, authorities, schemes and queries
  are rejected. Manifest resources must exist as files and be unique after
  resolution; links/resources cannot authorize entries absent from the manifest.
  TOC targets must additionally be spine documents. Fragments decode once and
  use a bounded ASCII content-anchor subset (128 units), including safe recovery
  of legacy numeric XHTML IDs; OPF/NCX IDs retain their stricter grammar. Duplicate
  content IDs and unsafe fragment syntax still fail; no external launch.
- Ordinary empty ZIP directory entries may be STORED or DEFLATED, including valid
  data descriptors. They count toward 512 entries and receive the same structural,
  size and CRC verification, but never become resources, spine items or descendant
  authorization. Directories with payload bytes are rejected. **CBZ retains its
  existing ASCII-name and STORED-empty-directory policy**; this is an EPUB opt-in.
- One `META-INF/container.xml` rootfile. EPUB3 keeps OPF `version="3.0"`, required
  metadata/manifest/spine and exactly one XHTML navigation item. EPUB2 supports OPF
  `version="2.0"` (the version value used by EPUB 2.0/2.0.1), manifest and XHTML
  spine with `linear` semantics. Its required `spine toc` must identify a manifest
  `application/x-dtbncx+xml` resource. Reject duplicate IDs/paths, missing entries,
  unsupported fallback/media overlays and non-XHTML spine items.
- EPUB2 NCX `version="2005-1"`/namespace, `navMap`, `navPoint`, `navLabel/text` and
  `content src` normalize to the existing owned TOC entries, preserving nested
  document order. Spine order alone determines reading order; `playOrder` does
  not reorder it. Iterative NCX traversal enforces 256 navPoints, 16 levels,
  512 UTF-16-unit labels, 640-unit references (path component ≤512), within the
  unchanged 1 MiB XML and 32/20,000 XML depth/node limits. Missing, malformed,
  empty, over-limit or unauthorized NCX fails preparation with a controlled
  INVALID/LIMIT result; no incomplete import is published. EPUB2 requires NCX,
  so this subset does not silently discard a broken TOC. NCX without a DOCTYPE
  and the exact standard NCX public declaration are supported. The latter is
  removed before SAX; its external DTD is never loaded.
- Strict UTF-8 XML1.0 only. Namespace-aware SAX with required external-entity
  flags and lexical DTD handler; reject custom/internal DTD/entity declarations, external
  resolution, processing instructions, XInclude and `xml:base`. Setup fails
  closed if a provider lacks the required protection. Enforce node/depth/text
  limits and cancellation while parsing. EPUB permits some constructs this
  deliberately narrower subset rejects; this is not a full conformance validator.
  Exact XHTML 1.1 and XHTML 1.0 Strict/Transitional public declarations are also
  removed before SAX, with matching root namespaces required. Only under these
  XHTML declarations, `&nbsp;` maps to the fixed U+00A0 character; comments/CDATA
  stay literal. Other named/custom entities remain unsupported. No entity table,
  DTD evaluator, external lookup or encoding fallback is added.
- Validate all manifest XHTML for basic root/head/body structure, local
  manifest-owned references, duplicate IDs and navigation `toc`. Reject remote
  references, scripts/event handlers and embedded active elements. At the structural-preparation boundary XHTML is not rendered and
  CSS/images/fonts and standalone SVG assets remain **opaque inert bytes**, not
  sanitized browser content. A narrow inline SVG cover wrapper containing exactly
  one manifest-owned PNG/JPEG projects to the existing image model. Only explicitly
  supported static attributes are allowed; scripts, animation, shapes, transforms,
  clipping, nested/multiple images, external targets and ambiguous hrefs fail.
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

Official compatibility sources inspected on 2026-10-07:

- [IDPF OPF 2.0.1](https://idpf.org/epub/20/spec/OPF_2.0.1_draft.htm) — required
  NCX/spine toc, manifest and reading order; NCX without DOCTYPE may omit playOrder.
- [RFC3986](https://www.rfc-editor.org/rfc/rfc3986) — relative paths, percent
  encoding and dot-segment resolution; the reader accepts a conservative subset.

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

## PR #22 durable imports and acceptance

The existing content-validated local import path is unchanged: validation succeeds
before an owned import is published, SHA-256 identity/deduplication and Library
insertion are unchanged, and no original picker URI/path is retained. NCX metadata
never enters publication/progress identity. Existing EPUB3 locators keep their
literal canonical spine paths and element/code-point positions; no progress schema
migration or reader/controller redesign. Synthetic deterministic ZIP fixtures are
original project content generated in tests; no third-party books are committed.

### User-reported physical Android acceptance

USER-REPORTED PHYSICAL ANDROID: The user reports that the PR #22 development APK
installed successfully and that they manually tested every supplied positive EPUB
compatibility artifact on a physical Android device. The user reports that none
of the tested EPUBs produced an error. The four supplied fixtures covered EPUB3
regression, EPUB2 + NCX, space/encoded-space resource paths, and explicit ZIP
directory-entry handling. This is user-reported physical device evidence;
individual checklist observations not explicitly reported by the user are not
claimed.

The tested corpus was `PR22-EPUB3-regression.epub`, `PR22-EPUB2-NCX.epub`,
`PR22-EPUB2-spaces.epub`, and `PR22-EPUB2-directories.epub`; no combined fixture
was produced. The development APK was built from reviewed HEAD
`456a80405c19365ab2ebc355ac3b56c9e35e2cd8`.

Unclaimed individual manual observations include exact NCX UI hierarchy and
spine-vs-NCX progression, individual image correctness, process-death progress
restoration, original-file deletion/reopening, Library/History semantics,
TXT/CBZ/PDF regression, resource/memory measurements and performance/FPS.
Their automated coverage, where present, remains separate from physical evidence.
Native Desktop graphical acceptance (Windows/Linux/Wayland/niri) remains pending.

Not full EPUB2/EPUB3 conformance: UTF-16 XML, custom/internal DTDs/entities, DTBook/non-XHTML spine,
pageList/navList/audio, fallbacks, SVG rendering, DRM/font obfuscation, signatures,
script/forms/media execution, remote resources and browser CSS remain unsupported.
No dependency, Android permission, acquisition policy or signing change.

## PR #25 real-world compatibility

Base main includes merged PR #24; its retained-session/semantic-position ownership
is unchanged. A local host harness inspected all five supplied originals, outside
tracked repository content. Original books, artwork and translations are not
redistributed. Committed regressions contain only original synthetic text/artwork.
EPUBCheck 5.3.0 was run as an external tool, without adding a project dependency.

All five have intact ZIPs, the correct first STORED `mimetype`, a matching OCF
container, OPF 2.0, XHTML spine and NCX; none declares encryption/signatures. Paths,
namespaces, UTF-8 and manifest/resource references were inspected separately from
ZIP integrity. Full conformance classifications below use EPUBCheck, not ZIP alone.

| Original | Structural classification / confirmed rejection | Host behavior after correction |
| --- | --- | --- |
| El arte de la guerra — Sun Tzu | EPUBCheck clean EPUB2; import succeeded, but reader rejected the inline SVG/raster cover namespace | Import, NCX and all 15 spine documents parse |
| El conde de Montecristo — Alejandro Dumas | Nonconforming but bounded ID recovery is safe: numeric XHTML IDs and invalid language attributes; ID validation caused import rejection | Import, NCX and cover parse; 22 of 26 spine documents remain over the unchanged 2,048-block chapter limit |
| De la brevedad de la vida — Séneca | Nonconforming numeric IDs and literal-space resource URIs, safely recoverable; import failed on numeric IDs | Import, NCX and all 22 spine documents parse, including owned space-named PNG wrapper |
| Analectas — Confucio | Nonconforming CSS syntax, inert in this reader; otherwise standard XHTML/NCX declarations and `nbsp` were unsupported | Import, NCX and all 50 spine documents parse; publisher CSS/fonts remain unevaluated |
| Meditaciones — Marco Aurelio | EPUBCheck clean EPUB2; standard XHTML declaration rejected before its SVG/raster cover | Import, NCX and all 3 spine documents parse |

The original-book probe therefore has **four complete successes and one partial
result**, not five complete reading successes. Montecristo's 22 large chapters
contain 2,808–3,268 nonblank paragraphs and still produce a controlled LIMIT result.
Supporting them requires future bounded chapter-window work; no limit is raised,
text discarded or block-limit assertion weakened here. ZIP/source/XML/resource,
image, link, chapter and retained-cache limits remain unchanged.

Synthetic tests reproduce numeric-ID and SVG-cover failures and standard declaration
rejection before correction. They also check hostile SVG/DTD inputs, entity/CDATA
semantics, owned references, duplicate/unsafe IDs, ZIP recognition, encrypted/invalid
import errors, failed-import cleanup, Library/History registration, fresh-owner
semantic reopening and progress preservation after a chapter LIMIT failure.

Physical Android acceptance of PR #25 and native Desktop graphical acceptance are
pending. Host parsing and EPUBCheck do not establish device rendering or layout
fidelity. Manual checks are in [the reader documentation](EPUB_READER.md#pr-25-manual-android-acceptance).

### Automated verification for PR #25

Verified production/test revision: `be89210897aa32c70db93d5fb82cae153f7e2fba`.
The subsequent evidence update changes documentation only. Thirty-four original
synthetic tests were added; the initial three numeric-ID/SVG/declaration
reproductions all failed before the correction. Existing security and continuity
assertions remain intact; the encryption assertion now requires its precise category.

- Focused EPUB/import/CBZ/continuity: **307 Desktop / 294 Android-host**, followed
  by **39 / 39** controller/error/progress/integration tests after the final changes.
- Final complete app regression: **1,029 Desktop / 944 Android-host** tests,
  **zero failures, errors or skipped tests**, verified from JUnit XML.
- Android `:androidApp:compileDebugKotlin` and Desktop `:desktopApp:compileKotlin`
  passed. Core is unchanged; unrelated core suites were not rerun.
- The separate, external five-original-book diagnostic ran on both hosts:
  **four passed / one failed per host**. Its Montecristo failure records the
  unchanged chapter limit, not complete compatibility. It is not hidden in or
  substituted for the green synthetic app regression.
- Full diff review, `git diff --check` and tracked/nonignored signing-secret,
  attachment-path and generated-artifact scans passed. No project dependency,
  permission, schema, signing change or temporary CI workflow is included.

Reproduction diagnostics and original-book/EPUBCheck reports remain outside the
repository. No graphical or physical acceptance is inferred from these results.

## PR #22 automated verification

[Final CI run](https://github.com/SirOtter0/INFINILECT/actions/runs/37640027762)
verified production/test revision `7cc5284d9266c4dbb5710ec0050dc9f424e4a9e2`
on 2026-10-07. Reviewed HEAD `456a80405c19365ab2ebc355ac3b56c9e35e2cd8`
differs only by documentation and temporary-CI cleanup; the subsequent physical
acceptance documentation update also leaves that production/test tree unchanged.
Original generated fixtures require no network/books.

Focused command (EPUB/ZIP/CBZ/import/progress), then full app/core regression:

```sh
./gradlew :core:jvmTest --tests '*Epub*Test' :core:testAndroidHostTest --tests '*Epub*Test' :app:desktopTest --tests '*Epub*Test' --tests '*CbzPagePreparerTest' --tests '*LocalImport*Test' :app:testAndroidHostTest --tests '*Epub*Test' --tests '*CbzPagePreparerTest' --tests '*LocalImport*Test' --no-daemon --console=plain --max-workers=2
./gradlew :core:jvmTest :core:testAndroidHostTest :app:desktopTest :app:testAndroidHostTest --no-daemon --console=plain --max-workers=2
./gradlew :androidApp:compileDebugKotlin :desktopApp:compileKotlin --no-daemon --console=plain --max-workers=2
```

| Suite | Focused tests | Final regression tests |
| --- | ---: | ---: |
| app Desktop | 271 | 872 |
| app Android-host | 262 | 827 |
| core JVM | 11 | 59 |
| core Android-host | 11 | 59 |

All final suites: **zero failures, errors and skipped tests** (counts verified from
JUnit XML). Both Android/Desktop Kotlin compilation tasks passed. CBZ regressions,
malformed import non-publication, original deletion/cache clearing/full owner
restart, deduplication, Library/History and EPUB2/EPUB3 progress restore passed.
`git diff --check` and tracked secret/generated-artifact scans passed. No dependency
or signing change. Temporary per-branch verification workflow is removed from the
final diff; the run/logs and this summary preserve evidence. These are host/compile
checks, **not physical Android or native Desktop graphical acceptance**.

## PR #26 bounded long-chapter presentation

The PR #25 Montecristo whole-chapter LIMIT finding above is historical. The reader
now selects bounded semantic windows without retaining the entire chapter model;
its old full-model API still enforces the original 2,048-block guard. Preparation,
import validation, manifest/spine ownership, XML/ZIP/security and image limits are
unchanged. XML remains limited to 1MiB/20,000 nodes and individual semantic blocks
to 8,192 text units; broader conformance is not claimed.

See [reader architecture, exact resource bounds, original-book results and pending
Android acceptance](EPUB_READER.md#pr-26-long-chapters-with-bounded-semantic-windows).
All five originals pass complete local semantic traversal on both hosts, including
Montecristo's 26 spine documents and 22 formerly oversized chapters. This is
additional automated evidence, not physical Android or graphical Desktop acceptance.
No original books or external diagnostic harness are committed.
