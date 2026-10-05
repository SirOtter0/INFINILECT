# First bounded page-reader foundation

Draft PR #17 adds comic/page reading on Android and Desktop. This is a controlled
development slice, not manga-site, Mihon, CBZ or generic image-viewer compatibility.
No production source policy changes. Gutenberg stays experimental OPDS2 catalog-only;
Archive stays public CC0 TEXT with fresh authorization and revision=null.

## Owned boundaries

```text
PublicationSource → fresh Publication (COMIC / PAGES resources in logical order)
                  → ResourceLoader → ResourceContent
                  → PagePreparer → PageDocument / PageEntry
                  → PageReaderController → shared Compose PageReader
                           │                        │
                    semantic Page locator     global reading mode
                           ▼                        ▼
                  ReadingProgressStore      PageReaderSettingsStore
```

Type expresses publication meaning; format expresses transport. Individual raster
resources use PAGES, not EPUB/PDF. Pure core owns PageDocument, PageEntry and validated
PageDimensions, without paths, source URLs, platform bitmaps, Compose, ZIP or SQL.
ResourcePageDocument is the individual-resource adapter. It snapshots declared
resource order, rejects foreign/modified/undeclared pages and uses only the injected
ResourceLoader. Sources do not know the reader; the reader does not know transport.

This first adapter requires source-declared dimensions before presenting pages.
They reserve stable width-fit height, **not** allocation authority: strict header
dimensions must match before native decoding, and provider dimensions must match
again. Missing geometry rejects preparation; invalid bounded image metadata or a
corrupt/unavailable page renders a fixed safe placeholder without breaking navigation.
No URI loader exists. Media ownership is PublicationId + exact resource equality.

The neutral Raster/RasterDecoder/preflight utility is extracted from PR #16. EPUB
keeps its own document/media controller and unchanged limits/behavior. Owned ARGB
data has no framework type. Android BitmapFactory and Desktop ImageIO are adapters;
ImageBitmap conversion lives in UI-only adapters. Replacing a decoder or future
page acquisition backend does not rewrite page progress/session/source contracts.

## Modes and presentation

- PAGED_RTL (default): rightward swipe advances logical next; leftward returns.
- PAGED_LTR: leftward swipe advances; rightward returns.
- VERTICAL: lazy, width-fit individual pages with an 8dp gap.
- WEBTOON: same bounded lazy implementation with no gap. Distinct semantic preference.

Paged canvas fits the page, supports 1–4× pinch zoom/pan and visible zoom/reset controls.
Swipe navigation requires a predominantly horizontal ≥48dp gesture at 1×. A gesture
that ever uses multiple fingers/zoom cannot change page; panning while zoomed never
advances. Page navigation resets transient zoom/pan. No spreads/tap zones/stitching.
Controls are collapsible; accessible labels describe controls and “Page N of M”,
without pretending OCR descriptions exist. Existing launcher insets remain in use.

Vertical items use stable page keys and source-declared aspect ratios (clamped for
unsupported-placeholder geometry). Loading, ready and error have exactly the same
reserved height. Moving backward never changes slot geometry when media arrives.
Width changes retire old callbacks and restore the current semantic page/fraction.
More than three simultaneously visible short pages can leave lower pages loading
until they enter the working window; memory bounds take priority over eager decoding.

## Limits and memory

| Boundary | Limit / policy |
| --- | --- |
| Document | 1–512 ordered pages; unique resource keys ≤512 UTF-16 units |
| Page geometry model | Positive dimensions ≤16384; stricter raster policy below |
| MIME | Exact image/png or image/jpeg, no MIME parameters/guessing |
| Encoded page | Known size 1–2 MiB; bounded read plus overflow probe and exact EOF/count |
| Raster | ≤2048 per dimension, ≤1,048,576 pixels, ≤4 MiB owned ARGB |
| Aspect | width/height 0.125–8, preventing extreme reserved-layout heights |
| Retained decoded pages | At most 3 (≤12 MiB owned pixel payload), including prefetch |
| Work | One serialized acquisition/decode across page-reader replacements, cooperative 10s deadline per page |
| Prefetch | Current ±1; up to 3 visible pages take precedence over neighbors |
| UI bitmaps | At most 3 current raster identities; sequential conversion, stale identities hidden |
| Zoom | 1–4× presentation transform; no higher-resolution decode or persisted zoom |

Preflight retains PR #16 static 8-bit PNG/chunk CRC policy and baseline/progressive
8-bit grayscale/RGB JPEG with optional JFIF, no EXIF/ICC/decoder extensions. Animated
PNG, SVG, GIF/WebP, arbitrary metadata/profiles and unsupported color depths fail
to placeholders. The same restrictive raster decoder applies to both readers.
Long multiplication precedes allocation; compressed byte bounds alone are insufficient.

At peak, 3 controller frames plus 3 UI bitmap copies total ≤24 MiB pixel payload,
with one ≤4 MiB decoder buffer and one ≤4 MiB conversion scratch buffer. Encoded
read/chunk assembly is ≤4 MiB plus 8 KiB; desktop provider encoded caching can add
≤2 MiB. Budget roughly 38 MiB payload/scratch in steady handover; an old, cancelling UI
conversion may temporarily retain its previous three-frame input (another ≤12 MiB),
so allow roughly 50 MiB bounded application payload during replacement, plus platform-provider,
GPU, object/metadata and GC overhead. This is a structural retention bound, **not**
an exact process-heap ceiling or promise of immediate native GC. Cancellation is
cooperative around synchronous provider calls; the shared page decode mutex prevents overlapping
replacement decodes even if an old provider finishes late. No job is spawned for
every page and no map retains the entire comic's pixels. No encoded/decoded pages
are persisted by this reader; ResourceCache remains the independent acquisition tier.

## Durable semantic progress

ReadingLocator.Page(pageKey, pageIndex, pageProgression) identifies a stable resource
key and an ordered-page fallback, plus finite fraction [0,1]. pageIndex is a document
ordinal, not persisted LazyListState. The current adapter uses structured progress
identity (PublicationId, "page-sequence", PAGES): one logical sequence per publication.
This is not a fabricated source revision. Keys win on reordering; missing keys fall
back to a clamped index. Changing title/author/revision does not invalidate user state;
another PublicationId/source/sequence cannot receive it. Content changes may make
restoration approximate; no checksum is invented as source authority.

Paged navigation saves page start. Continuous reading saves approximate fraction
through the first visible page; UI pixels are transient mapping inputs only. Mode
changes preserve page/fraction and invalidate older callback tickets. Initial restore
and loading geometry do not replace persisted position. Unsupported-page placeholders
still represent their logical page, so a reader may save its position and move on.
Progress throttles at 2s while reading, flushes on Back/close and uses the existing
application-owned writer. PAGE uses schema3/tag3; historical TEXT v1 and EPUB v2 bytes
remain unchanged/readable. No SQL migration or progress/cache amalgamation.

## Durable global mode

PageReaderSettingsStore is an independent owned contract. PageSettingsPersistence
loads once, leases updates to the current reader, holds one pending record and
coalesces changes over 300ms. Back/onStop/close wake the writer and application close
drains it. Newer timestamps win across owners; failed writes report a fixed message,
never pretend RAM proves durability. Missing/corrupt/future records safely use RTL.

FilePageReaderSettingsStore uses one fixed 56-byte version1 record: magic, schema,
mode enum, nonnegative timestamp, SHA-256 checksum. Private same-directory temporary
file + force + atomic replacement; a failed commit preserves the previous record.
Owned stale temps only are cleaned; no destructive recovery or storage permission.

- Android: applicationContext.filesDir/page-reader-preferences-v1/settings.preferences.
  Only its path is retained; no Activity/Context. Never cacheDir.
- Desktop: sibling of progress under per-user org.infinilect.app persistent data
  (Linux XDG_DATA_HOME or ~/.local/share, macOS Application Support, Windows
  LOCALAPPDATA). Relative/invalid environment paths use the established safe fallback
  or degrade without writing into cwd.

Library, History, EPUB preferences, progress and resource cache remain independent.
Deleting cache cannot delete mode/progress. Saved metadata still resolves through
the owning source before opening; successful PageReady records History as for the
other readers. A valid sequence containing a failed page can open with placeholders.
No credential, publication content, URL, network, telemetry or sync in preferences.

## Lifetime

ApplicationSources owns preferences and injected preparer. ApplicationSession owns
the active PageReaderController. Back uses the same application transition for
visible and Android system Back, flushes progress/preferences, cancels page work,
clears retained frames and closes PageDocument/active handles idempotently. Pending
source loads/late decodes cannot publish after reader replacement. Search/query/results
and Library/History origin are retained exactly as with TEXT/EPUB. Application shutdown
first closes its session, then drains independent writers/releases transport resources.
Android process death has no guaranteed final callback; periodic/coalesced saves limit loss.

## Development comic and future boundaries

Android debug shows **Comic development demo**; select it, search `original`, press
**Open pages**. Release has neither demo option nor production comic acquisition.
Desktop requires `INFINILECT_COMIC_DEMO=1 ./gradlew :desktopApp:run`; the packaged
launcher accepts the same environment opt-in. The original 24-page geometric comic
uses portrait, landscape, square and narrow strip geometry. PNG generation is local,
cancellation-aware and per-page; JPEG artwork is project-generated, reproducible
with `java -Djava.awt.headless=true tools/generate-comic-jpeg.java` (Base64 output).
No downloaded artwork, website, source plugin or external network dependency.

Future CBZ adapter/preparer → PageDocument → this PageReader. Future web source →
owned ordered page resources/PageDocument → this PageReader. Future explicit Downloads
are separately authorized persistent bytes. Future PDF uses PdfDocument/PdfReader,
never masquerades as a comic; only low-level raster presentation may be shared.
None is implemented here. No OCR, covers, library redesign, double spreads, arbitrary
image import, extreme full-resolution scans, zoom persistence or production comics.

## Android physical acceptance — pending PR #17

Automated host tests are not device tests. User-reported PR #16 verification covers
existing EPUB/settings/media/TEXT, not this page reader. Install the new debug APK
over that build without clearing app data, then:

1. Existing Library/History still work.
2. Existing large TEXT reader still works.
3. Existing EPUB reader still works.
4. Select Comic development demo, search `original`, Open pages (repeat offline).
5. Verify first page and page counter.
6. Swipe several pages in PAGED_RTL (right advances).
7. Go backward.
8. Controls → PAGED_LTR; verify reversed swipe direction.
9. Zoom/pan a page using pinch or Controls zoom buttons.
10. Verify zoom/pan does not accidentally skip pages; Reset zoom.
11. Switch to VERTICAL.
12. Scroll quickly forward and backward.
13. Verify no catastrophic jumps as images load.
14. Switch to WEBTOON.
15. Fast-scroll enough pages to exercise lazy loading.
16. Return to PAGED_RTL.
17. Verify logical reading position remains near the same page.
18. Back out and reopen.
19. Verify progress restoration (repeat after waiting ≥3s and with quick Back).
20. Change reading mode, leave, reopen.
21. Verify reader mode persistence and apply it to another comic when one is available.
22. Terminate/relaunch application.
23. Verify progress and reader mode again.
24. Clear application **CACHE only**, not app data.
25. Verify progress/settings/Library/History remain; re-search/open demo.
26. Test Android system Back from Search/Library/History-opened comic and during loading;
    root Search retains normal system exit.
27. Exercise rapid navigation and rapid mode switching.
28. Confirm no crash, obvious memory runaway, stale page flash or storage error.

## Desktop graphical acceptance — pending

Run Gradle and packaged launcher with INFINILECT_COMIC_DEMO=1; both share wiring/UI.
Exercise all modes, drag swipes, zoom/pan/buttons, resize, rapid reverse vertical
scroll, Back through Search/Library/History, mode/progress save and full process restart.
Repeat offline/cache-only deletion, verify TEXT/EPUB regression. Building the
distributable and headless ImageIO/Skia tests do not prove graphical execution.
The unrelated niri/Wayland outer-window sizing issue remains outside this PR.
