# First semantic EPUB reader — Draft PR #15

The existing [EPUB preparation](EPUB.md) proves structure, ownership and bounded
local resource access. This reader adds a separate **passive presentation** boundary.
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
deterministic three-chapter EPUB from project-owned text (GPL-3.0-or-later), no
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

Supported presentation: headings, paragraphs, sections/div boundaries, unordered
list-item bullets (ordered lists also use bullets), blockquotes, emphasis/strong,
line breaks, Unicode and internal spine links/anchors. Mixed text/element order
is preserved. Passive unknown XHTML elements unwrap; HTML whitespace collapses.
`pre`/tables do not retain their original layout. Previous/Next follows package
spine order, including non-linear items; no inferred chapter ordering. TOC retains
bounded nested labels/targets, and the reader displays chapter context/whole percent.

Not supported: CSS layout, publisher fonts, fixed-layout fidelity, RTL publication
layout policy, scripting, audio/video, forms, SVG/MathML, remote resources, image
decoding, EPUB2/NCX, DRM, annotations, settings or Downloads. Manifest image references
are validated but display only bounded alt text; no decoder or image-resource read.
There is no decompression-to-image allocation or image-bomb path. Foreign namespace
content and active constructs fail, rather than becoming browser content.

## Security and budgets

Only manifest-owned XHTML spine/nav resources are opened through EpubDocument.
No browser, JS evaluator, URI handler, HTTP client or filesystem path enters the
renderer. External/file/content/javascript/network-relative schemes, encoded aliases,
queries, escaping traversal, undeclared targets and non-spine hyperlinks reject.
Fragments use the foundation's portable ID subset; missing anchors produce a fixed
reader error. DTD/entities/PI/XInclude/xml:base remain forbidden. Required SAX
hardening fails closed. Scripts/forms/objects/iframes/events/active media and foreign
namespaces cannot reach Compose. CSS is never interpreted or fetched.

| Budget | Ceiling |
| --- | ---: |
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
| Decoded images | **0** |
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
work. No source/background/image jobs are launched per node.

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

## Manual Android plan (not an automated/device-test claim)

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

## Manual Desktop plan

Start Gradle-run or packaged app with the explicit environment opt-in above. Repeat
steps2–9 (close/relaunch process for persistence; remove only private cache for step8).
Check resizing/layout and platform Back button. The existing niri/Wayland outer-window
black-area issue is untouched. Build success does not claim graphical execution.
