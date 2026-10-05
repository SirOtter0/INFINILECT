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

## Bounded CBZ input (Draft PR #18)

CBZ flows through `CBZ resource → shared bounded ZIP32 inspector → CBZ page adapter →
PageDocument → existing PageReader`. The app-owned generic ZIP layer checks physical
record layout, local/central agreement, ZIP32 only, flags/methods, safe relative ASCII
paths, alias/ancestor collisions, entry types, comments/extras, declared bounds and
CRC. EPUB continues to apply its own exact first-STORED `mimetype`, package/XML,
manifest and spine policy above this generic layer. The CBZ path does not weaken or
reinterpret EPUB rules.

Accepted entries are directories and regular PNG/JPEG pages only. Every regular
payload must have a recognized `.png`, `.jpg` or `.jpeg` suffix and the bounded byte
header must confirm that exact media type; anything else rejects the archive. ZIP
order is ignored. Relative paths sort using ordinal ASCII case-folded comparison with
contiguous digit runs compared by numeric value, fewer leading zeroes first when
numeric runs tie, then the original ordinal path as a deterministic tie-break. The
whole normalized path participates, so nested directory names sort before their
children's leaf names. For example, `page/1.png`, `page/2.png`, `page/3.png`,
`page/03.png`, `page/10.png` sorts as `1`, `2`, `3`, `03`, `10`; equal numeric names
do not collide. Reading direction remains the user's global PageReader setting.

Bounds: archive 32 MiB; total declared expansion 64 MiB; one ZIP entry 8 MiB;
512 total ZIP records (directories included); compression ratio at most 100:1; at
least one and at most 512 page files; one encoded raster at most 2 MiB. Raster header
inspection accepts only the existing static PNG/JPEG policy and checks dimensions
≤2048 per side, ≤1,048,576 pixels (≤4 MiB ARGB per decoded frame). Preparation keeps
one 8 KiB transfer buffer and at most one encoded page (2 MiB) at a time. It verifies
all ZIP entry streams/CRCs without retaining page payloads, then validates each
raster's signature/geometry without decoding it to ARGB. PageReader retains at most
three decoded frames, with one serialized decoder and one-page prefetch. At four MiB
maximum ARGB per frame, three retained frames plus one in-flight decode are bounded
to 16 MiB ARGB, plus at most one 2 MiB encoded page. The preparer allows at most two
open prepared documents and three live page handles per document.

The archive is streamed from ResourceLoader to a UUID-named temporary file beneath
`cacheDir/cbz-preparation-v1` on Android and the existing per-user Desktop cache root
under `cbz-preparation-v1`. ZIP paths never become filesystem paths; no extraction is
performed. A document publishes only after complete bounded transfer, structural
inspection, CRC/size verification and image-header validation. Closing it closes the
ZipFile/handles and deletes its backing archive; owner shutdown cancels preparation
and drains cleanup. Startup cleanup is locked and removes only the CBZ adapter's exact
UUID `.part`/`.zip` namespace. Incomplete/cancelled files cannot be opened as a
document. OS cache clearing may discard prepared bytes; reopening prepares them again
through the source and does not affect Library/History or ReadingProgress.

The `CBZ development demo` is original project-owned artwork generated from the
existing comic art, available only in Android debug and with Desktop
`INFINILECT_COMIC_DEMO=1`. Its physical ZIP order differs from page order and includes
numeric names, leading zeroes, a nested directory, PNG/JPEG and varied geometry.
Production comic sources and local import are not enabled. Future local file import
must be a separate platform acquisition boundary into this preparer. CBR/RAR/7z,
PDF, OCR, and local-file picking remain out of scope; this is not a PDF engine.

## Android physical acceptance — user-reported partial acceptance for PR #17

Automated host tests are not device tests. The user has now physically tested PR #17
on Android and reports the following successful checks: existing TEXT and EPUB
reading; opening the development comic; PAGED_RTL forward/back; PAGED_LTR direction;
zoom/pan; VERTICAL fast forward/reverse scrolling without catastrophic jumps;
WEBTOON fast scrolling; sensible logical position after mode changes; progress after
leaving/reopening; page-reader mode after leaving/reopening; and both progress and
mode after terminating and relaunching INFINILECT. This is user-reported device
verification, not an automated or Codex-performed test.

The following acceptance items remain pending because they were not included in the
user's report:

1. Existing Library/History still work.
2. Verify the initial page and page counter.
3. Repeat the development comic with network disabled to verify local-only media.
4. Explicitly verify zoom/pan cannot accidentally change pages and reset behavior.
5. Verify Android system Back from Search/Library/History-opened comic and during loading;
    root Search retains normal system exit.
6. Clear application **CACHE only**, not app data, then verify progress, reader mode,
   Library and History remain and the comic can be reopened.
7. Perform extended memory/performance observation under sustained rapid navigation;
   the reported successful scrolling is not a memory profile or stress result.
8. Verify the first-page indicator and other unreported controls/accessibility details.

These remaining checks are not inferred from the reported passes. The user also
identified future, non-blocking UX improvements: center tap to toggle reader
controls/settings; left/right tap zones to navigate according to reading direction;
and a paged presentation that visually drags/snaps adjacent pages instead of abruptly
replacing the current page. These are future UX work, not PR #17 defects or blockers.

## Desktop graphical acceptance — pending

Run Gradle and packaged launcher with INFINILECT_COMIC_DEMO=1; both share wiring/UI.
Exercise all modes, drag swipes, zoom/pan/buttons, resize, rapid reverse vertical
scroll, Back through Search/Library/History, mode/progress save and full process restart.
Repeat offline/cache-only deletion, verify TEXT/EPUB regression. Building the
distributable and headless ImageIO/Skia tests do not prove graphical execution.
The unrelated niri/Wayland outer-window sizing issue remains outside this PR.
