# Passive EPUB reader — bounded EPUB3 subset

The existing [EPUB preparation](EPUB.md) proves structure, ownership and bounded
local resource access. Merged PR #15 added the initial reader; Draft PR #16 improves
its session presentation and bounded local media. This reader adds a separate **passive presentation** boundary.
It supports a narrow EPUB3/XHTML subset on Android and Desktop with the same
Compose UI. It does not claim generic EPUB compatibility or a completed v0.0.1.

## Acquisition status and a reproducible development route

Gutenberg remains experimental OPDS2 **catalog-only**: no OPDS0.9/RDF/mirror/guessed
URL acquisition. Internet Archive still exposes its existing public-CC0 TEXT/PDF
metadata subset; **production EPUB acquisition is not enabled**. No rights,
fresh-metadata, redirect, MIME/size or null-revision policy changes.

One bounded official MDAPI request on 2026-10-04 at 21:27:07 UTC checked the existing
CC0 item `gmb-2015-93040`: HTTP200, 5,359 application-consumed bytes, no redirect,
no EPUB files. This does not establish that all CC0 Archive items lack EPUBs. We
have not verified an appropriate production EPUB acquisition/rendering fixture;
that gate remains explicit instead of broadening the adapter or legal scope.

The debug Android APK offers **EPUB development demo** as an explicitly selected
source. Search `original`, then **Open EPUB**. It constructs one original,
deterministic three-chapter EPUB from project-owned text and geometric PNG/JPEG
artwork (GPL-3.0-or-later), no
Internet, copyrighted book or imported file. Release Android builds do not offer
this option. Desktop requires explicit opt-in:

```sh
INFINILECT_EPUB_DEMO=1 ./gradlew :desktopApp:run
# The same environment opt-in works with the packaged application launcher.
```

This route uses PublicationSource/search/getPublication, the injected ResourceLoader,
ResourceContent, the real FileEpubPreparer/parser/reader, and the actual persistent
progress/Library/History stores. It does not fabricate production acquisition.
Saved demo metadata only resolves when this development source is enabled; otherwise
opening fails safely and preserves the entry. It cannot authorize another source.

## Renderer research and choice (upstream checked 2026-10-04)

| Option | Current upstream evidence | Decision |
| --- | --- | --- |
| [Readium Kotlin](https://github.com/readium/kotlin-toolkit) | [Stable 3.4.0](https://github.com/readium/kotlin-toolkit/releases/tag/3.4.0), published 2026-09-11; Android SDK/AGP compatibility table; [BSD-3-Clause](https://github.com/readium/kotlin-toolkit/blob/develop/LICENSE) | Maintained Android navigator, not a shared Desktop renderer. No dependency added, and never in core. |
| Android WebView | Official [unsafe file inclusion](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion) and [URI loading](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading) guidance requires explicit file/content/network/navigation policies | Could be isolated on Android, but would require a different Desktop engine and separately verified interception/lifecycle rules. Not chosen. |
| [JavaFX WebEngine](https://openjfx.io/javadoc/25/javafx.web/javafx/scene/web/WebEngine.html) | Maintained upstream WebKit API, scripting/loading and dedicated UI-thread lifecycle; [GPLv2 + Classpath Exception](https://github.com/openjdk/jfx/blob/master/LICENSE) | A Desktop runtime/browser integration, not Android's engine; no common verified sandbox in the existing app. Not added. Version25 docs describe the API, not a proposed toolchain upgrade. |
| [JCEF](https://github.com/chromiumembedded/java-cef) | Upstream Java bindings to CEF/Chromium, native build/distribution and [BSD-style license](https://github.com/chromiumembedded/java-cef/blob/master/LICENSE.txt) | Substantial native/browser/security boundary for this narrow slice; not added. |
| [compose-richtext](https://github.com/halilozercan/compose-richtext) | Apache-2.0, not archived, last push 2026-06-08; README warns it is very experimental with an unclear roadmap; Markdown/rich text, not a trusted EPUB parser | Adds no required EPUB security/ownership semantics. Not added. |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) native text | Already used on Android/Desktop, annotated text/lazy layout available | Selected: existing bounded XML → renderer-independent blocks/runs → passive Compose. Zero new dependency. |

This decision does not assert that browser sandboxes are impossible. It avoids
claiming a cross-platform sandbox we have not implemented and verified, accepting
less CSS fidelity in exchange for an inspectable boundary with no browser.

## Pipeline and supported subset

```text
PublicationSource → Publication/resource → ResourceLoader → ResourceContent
    → FileEpubPreparer → EpubDocument.openResource(manifest path)
    → bounded XHTML parser → EpubChapter / blocks / styled runs / owned targets
    → EpubReaderController → shared Compose EpubReader
                           → typed EPUB locator → ReadingProgressStore
```

The neutral opener prefers an offered TEXT resource; EPUB is selected when TEXT
is absent and the application option enables EPUB. Source-specific decisions
are not inside either reader. Successful History begins only after TOC and first
chapter parsing succeed. Library/History reopen still resolves source-owned IDs,
acquires/rebuilds preparation, then restores progress.

Supported presentation: h1–h6 hierarchy, paragraphs, sections/div boundaries,
ordered/unordered list markers, nested lists (8 levels), blockquotes, emphasis/strong,
line breaks, separators, preformatted whitespace, captions, Unicode and owned internal
spine links/anchors. Basic ordered lists start at 1 or a bounded `start` (1–9999),
with sequential numbering; `li value`/reversed/custom styles are not implemented.
Markers are semantic fields, never injected into logical text. Multiple paragraphs in
one list item repeat its marker. Preformatted text wraps on phones, not a CSS layout.
Tables unwrap to text without a grid, column sizing or layout fidelity; richer tables
are deferred. Previous/Next follows spine order; bounded Contents entries navigate
only declared spine paths/anchors. Missing anchors give a safe fixed error.

PNG/JPEG local images render within a stable-height 200dp presentation area using
Fit, with bounded alt-text and figure captions where available. Other images display
alt/unsupported text. Only manifest-owned resources are opened. No SVG/GIF/WebP,
responsive `srcset`/picture selection, remote or data URLs, external fonts or browser.

Not supported: arbitrary CSS, publisher fonts, fixed-layout fidelity, RTL layout
policy, scripting, audio/video/forms, SVG/MathML rendering, remote resources,
EPUB2/NCX, DRM, annotations or Downloads. Foreign namespace/active content fails.
No generic EPUB compatibility or production acquisition claim.

### Reading settings and semantic position

A compact, height-bounded scrollable Settings dialog controls font size (14–30sp), line spacing (120–200%),
horizontal margins (8–40dp) and system/light/dark reading theme. Settings affect only
the current EPUB reader session and reset on close/restart. This avoids premature
storage/settings infrastructure and leaves TEXT unchanged. Reading text remains real
accessible text; image alt labels, theme controls and navigation are labelled.

Changes in settings, width, density or font scale preserve the latest semantic
locator, invalidate previous layout tickets, and restore its block/containing line
using the new layout. They never persist pixels/indices, reparse the chapter or save
loading layouts. Theme/style changes do not mutate chapter content. Restoration is
approximate within a passage; stable 200dp image space prevents delayed decoding from
moving the viewport. Whole percentages remain chapter-weighted approximations.

### Image boundary and upstream API evidence (2026-10-05)

`EpubImage(path, mediaType, alt)` belongs to the semantic chapter. It contains no
image decoder, pixels, filesystem path or framework object. `EpubRasterDecoder`
returns INFINILECT-owned bounded ARGB data, used only by the session media controller.
A separate UI-only platform conversion produces Compose ImageBitmap. Replacement
of BitmapFactory/ImageIO affects adapters, not sources, progress, Library/History
or parser models. See [dependency boundaries](DEPENDENCY_BOUNDARIES.md).

The existing Compose Multiplatform 1.12.1 artifacts expose Image/ImageBitmap,
annotated links, lazy layout and Android/Skia bitmap conversion. No HTML/image-loading
library is needed; no new third-party dependency/version/plugin is added. Desktop
host image tests use the same existing Compose/Skiko runtime as desktopApp. Official
sources checked: [Compose upstream](https://github.com/JetBrains/compose-multiplatform),
[Android BitmapFactory.Options](https://developer.android.com/reference/android/graphics/BitmapFactory.Options)
(`inJustDecodeBounds` explicitly avoids allocating pixels),
[JDK21 ImageReader](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/ImageReader.html)
(`getWidth`/`getHeight` before `read`), and existing compiled platform conversion APIs.
Desktop bundles java.desktop explicitly for its standard ImageIO provider; no new
native/browser engine. Android minSdk26 supports the selected APIs.

Shared preflight verifies PNG signature/IHDR, chunk lengths/CRC and dimensions;
accepts static 8-bit pixel/palette/transparency chunks only. Animation, ICC/compressed
metadata/EXIF and unknown PNG extensions degrade to alt text. JPEG allows 8-bit
baseline/progressive grayscale/RGB, optional JFIF; EXIF/ICC/other APP extensions are
unsupported. Both provider bounds must match preflight before pixel allocation.
Encoded bytes and decoded pixels are bounded independently. The decoder is not an
integrity/authenticity or complete image-conformance proof.

Image references cannot contain dot/dot-dot segments, encoding, query or fragment
aliases, schemes/absolute/network-relative paths, or undeclared resources. Other
existing relative EPUB link normalization stays unchanged. The controller independently
checks exact path and MIME against the manifest, rejects unsupported types before
opening, verifies declared byte size, and always closes resource handles. Wrong MIME,
corruption, oversized data or decoder failure produces alt text, not a source request
or a reader crash. UI conversion failures also fall back safely.

## Security and budgets

Only manifest-owned XHTML spine/nav and supported raster resources are opened through
EpubDocument. XHTML is parsed, never executed; raster bytes enter only the bounded media
adapter. No generic URL resolver exists.
No browser, JS evaluator, URI handler, HTTP client or filesystem path enters the
renderer. External/file/content/javascript/network-relative schemes, encoded aliases,
queries, escaping traversal, undeclared targets and non-spine hyperlinks reject.
Fragments use the foundation's portable ID subset; missing anchors produce a fixed
reader error. DTD/entities/PI/XInclude/xml:base remain forbidden. Required SAX
hardening fails closed. Scripts/forms/objects/iframes/events/active media and foreign
namespaces cannot reach Compose. CSS is never interpreted or fetched.

| Budget | Ceiling |
| --- | ---: |
| Per-chapter image references | 64 |
| Alt text | 256 UTF-16 units, surrogate-safe truncation |
| Encoded image / read buffer | 2 MiB / 8 KiB |
| Width/height / pixels / decoded ARGB per image | 2048 each / 1,048,576 / 4 MiB |
| Retained decoded frames / UI images / simultaneous provider decode | 2 / 2 distinct / 1 |
| Media deadline / JPEG scans | 10 seconds including serialized wait / 64 |
| Acquisition/archive, expansion | Existing 32 MiB / 64 MiB |
| Entries, per expanded entry, compression ratio | Existing 512 / 8 MiB / 100 |
| Manifest, spine | Existing 256 / 128 |
| XML payload per parse, streaming resource buffer | 1 MiB / 8 KiB |
| XML nodes/depth/attributes per node | Existing 20,000 / 32 / 32 |
| Attribute value / direct-node text | Existing 8,192 UTF-16 units each |
| Chapter text / display block text | 262,144 / 8,192 UTF-16 units |
| Blocks / text-append events / links / anchors per chapter | 2,048 / 8,192 / 512 / 4,096 |
| Retained parsed chapter models | 2 (current + one previously used); no whole-book DOM |
| TOC entries / nested list depth / label | 256 / 16 / 512 UTF-16 units |
| Transient Compose text layouts | 12 |
| Chapter navigation parse jobs | One serialized parse; latest generation wins |
| Open/preparation / chapter navigation deadline | 60 seconds / 15 seconds |

One parse transiently holds at most 1MiB XML bytes (bounded read helper additionally
uses up to another 1MiB in chunks plus 8KiB buffer), strict decoded XML ≤2MiB, and
one bounded tree with ≤20,000 nodes. Ordered SAX content coalesces character events;
parts grow with element transitions, never with each character. Direct/ordered
text copies are each bounded by XML text. The complete tree is discarded after
one chapter/nav model is built. Two chapter models retain at most 524,288 UTF-16
units each in run text and assembled block text combined across their two copies
(~2MiB text payload overall), plus explicitly bounded blocks/spans/anchors/TOC.
Object/layout overhead is bounded structurally but not claimed as a measured heap
limit; device profiling remains pending. Only composed blocks create layout objects.

Prepared ZIPs remain app-private disposable cache infrastructure, not ResourceCache,
user progress, SQL publication contents or Downloads. Existing close/stale-session
cleanup and two-document/eight-entry-handle caps remain. Android cache-only deletion
cannot remove Library/History/progress under persistent files/database storage.

## Semantic position and lifecycle

[Progress contract and binary compatibility](PROGRESS.md). EPUB locator:
canonical spine path + bounded XHTML element-child ordinal path rooted at body
(with a synthetic root ordinal0) + Unicode code-point offset within that block +
chapter normalized fallback. It is **not** a lazy item index or pixel value.
Internal block segments retain their original element path and cumulative offset.
Same structure restores the block/containing line using current layout; structural
changes fall back approximately within that chapter, a removed spine path starts
at the first chapter. Layout wrapping/density can change the exact line. Whole-book
progress is (spine ordinal + chapter fraction)/spine count, an approximation weighted
by chapters rather than actual word counts. No completion/history flag inferred.

TEXT records stay byte-for-byte schema v1. EPUB adds schema v2/tag2 only; no
SQLDelight schema change and no destructive progress migration. A fixed two-second
throttle saves during reading; Back/navigation/application ownership flush latest
meaningful state. Initial restoration does not overwrite it. Old tickets/closed
reader callbacks cannot write; immutable progress IDs bind each submitted record.
Store failures still display the existing fixed save-failure message.

Open owns document until successful handoff; any error/cancellation closes it.
Reader close cancels navigation, flushes progress, releases chapter/TOC state and
closes EpubDocument idempotently. Navigation uses generation checks, a cancellable
Mutex around parser work and a 15s deadline; non-cooperative late results cannot
publish after a newer request or close. Document resources always close in finally
via bounded readBytes. The parser checks cancellation during resource/XML/semantic
work. No source jobs or per-node/image-per-book jobs are launched. One media worker decodes
only the first two distinct visible references. Chapter/layout tickets reject stale
visibility/progress callbacks; cancellation and media generations reject late decode
results. Provider calls themselves may finish after cancellation; results are discarded.
Two owned pixel arrays (8 MiB), two UI bitmaps (8 MiB), one in-flight decoder
bitmap/pixel array (up to 8 MiB), UI conversion scratch (up to 4 MiB), and bounded
encoded read/assembly (up to 4 MiB) give a conservative ~32 MiB media payload budget
before provider/object overhead and transient GC. This is a structural working-set
estimate, not a measured process-heap guarantee. Repeated identical references in the current visible set share one decoded
frame/UI bitmap; differently labelled references can occupy separate slots. Chapter navigation drops old media; close cancels worker and drops
all presentation state. Decoded images are never persisted.

Android's system Back and the visible reader Back button use the same
`ApplicationSession.back()` command. The shared platform binding observes opening
and destination StateFlows as Compose snapshot state in its own composition scope;
EPUB Ready, TEXT Ready, Loading and Error enable it. Library/History also enable it,
but Idle/Search leaves Android's normal system exit behavior intact. A user-reported
physical finding on the initial PR #15 APK exposed an unobserved StateFlow read:
the reader child recomposed while the parent callback could remain disabled.
The follow-up corrects that observation boundary without changing session navigation,
document ownership or the progress flush path. Headless Compose regression tests
reproduce the stale callback and verify the new binding; corrected-device confirmation
remains a manual test, not a host-test claim.

## Historical PR #15 manual plan (not an automated/device-test claim)

1. Install debug APK over PR #14 without clearing data. Check historical Library,
   History and TEXT positions remain; existing IA acquisition still revalidates.
2. Read existing small and large TEXT, scroll both directions repeatedly, Back/reopen
   and process-restart restore. Check no PR #14 viewport regression.
3. Select EPUB development demo, search `original`, Open EPUB. Verify headings,
   emphasis, Unicode, list items and quotes; no raw XHTML.
4. Scroll chapter1, follow the internal chapter link, use Previous/Next and Contents
   to visit all three chapters. Check target anchors/current chapter/percentage.
5. Scroll well into chapter3, wait≥3s, use **Android system Back** and reopen. Verify
   return to results without exiting, preserved query/results and approximately the
   same passage. Repeat using the visible Back button and without waiting for the
   save interval, to exercise the pending-progress flush.
6. Terminate/relaunch, select demo/search/open again. Verify chapter/passage restores.
7. Add demo to Library, reopen via Library; reopen via History and verify one updated
   successful-open record. Verify normal metadata resolution still occurs. System
   Back from each EPUB returns to its respective Library/History list; another Back
   returns to Search. At Idle/Search, system Back retains normal Activity exit.
8. Clear **cache only**, not app data. Library/History/progress remain. Reopen demo;
   preparation rebuilds and restores. Repeat existing IA TEXT reopen.
9. Use system Back quickly during opening/chapter transition; switch source/publication rapidly.
   No late old chapter, crash, storage failure, or leaked reading state.
10. Release builds do not advertise demo as production EPUB. No production EPUB test
    is claimed in this PR. No permission or telemetry change.

## Historical PR #15 Desktop plan

Start Gradle-run or packaged app with the explicit environment opt-in above. Repeat
steps2–9 (close/relaunch process for persistence; remove only private cache for step8).
Check resizing/layout and platform Back button. The existing niri/Wayland outer-window
black-area issue is untouched. Build success does not claim graphical execution.

## PR #16 Android acceptance checklist — physical verification pending

1. Install over the merged PR #15 debug build **without clearing app data**.
2. Check old Library/History and both TEXT/EPUB saved positions survive.
3. Open large IA TEXT; scroll repeatedly down and up. PR #14 behavior must remain.
4. Select **EPUB development demo**, search `original`, Open EPUB. Production sources
   still cannot acquire EPUB through this build.
5. Inspect heading/paragraph/emphasis/strong/list/quote presentation and real text.
6. Follow **Visit the local presentation showcase** near the chapter beginning;
   verify the ordered list reads **3, 4**, with h2/h3 distinction, separator and pre.
7. Inspect the original PNG and JPEG panels and the caption; SVG is alt text only.
8. Repeat steps4–7 in **airplane mode / network disabled**. Media must still work.
9. There must be no source/network request caused by EPUB images (the selected demo
   is entirely local); no browser/file/content navigation should occur.
10. Change font size, line spacing, margins and system/light/dark reading theme.
11. Deep in a chapter, change settings/orientation; approximately the same semantic
    passage must remain. No jump to the beginning or spurious saved EOF.
12. Use Previous/Next, Contents and internal anchors; verify current chapter/percent.
13. Wait≥3s, use Android system Back, reopen; repeat with visible Back and no wait.
14. Fully terminate/relaunch, reopen; chapter/passage restores, session settings reset.
15. Open the demo from Library and History. Back returns to the respective origin;
    another Back returns to Search. Root Search retains normal Android exit.
16. Clear **cache only**; persistent user state stays. Reopen rebuilds prepared bytes
    and restores progress. Normal IA null-revision acquisition checks still run.
17. System Back during chapter/media work cancels/closes without exiting prematurely.
18. Rapid chapter/settings/publication changes and Back must not show stale media or
    old chapters, crash, or produce storage/progress/library/history errors.
19. Release UI must not advertise development EPUB or production EPUB acquisition.
20. Verify no new Android permission, telemetry, remote media or font request.

## PR #16 Desktop acceptance checklist — graphical verification pending

- Run `INFINILECT_EPUB_DEMO=1 ./gradlew :desktopApp:run`, then run the packaged
  `desktopApp` launcher with the same opt-in. Both use the shared reader/wiring.
- Select demo, search `original`, inspect the showcase/PNG/JPEG/alt fallback and
  text/numbering. Repeat offline; no media network access is needed.
- Resize repeatedly, change settings deep in a chapter, check passage retention,
  readable bounded column, TOC/anchors/Previous/Next and whole percentages.
- Back/reopen through Search, Library and History; close/relaunch the process and
  verify semantic restoration. Delete only cache, repeat preparation/restore.
- Check TEXT forward/backward scrolling and retained Search query/results.
- Rapid navigation/settings/Back must not publish stale content. No graphical
  execution is claimed by host codec tests or distributable construction. The known
  unrelated niri/Wayland outer-window sizing issue remains outside this PR.
