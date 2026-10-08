# Bounded comic PageReader

PR #17 introduced the bounded comic/page foundation on Android and Desktop.
CBZ import was added in PR #18; optional spreads are described under PR #23 below.
This remains a controlled development slice, not manga-site or Mihon compatibility.
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

- PAGED_LTR (default since PR #21): right tap / leftward swipe advances; left tap / rightward swipe returns.
- PAGED_RTL: left tap / rightward swipe advances; right tap / leftward swipe returns.
- VERTICAL: lazy, width-fit individual pages with an 8dp gap.
- WEBTOON: same bounded lazy implementation with no gap. Distinct semantic preference.

Paged canvas fits the page, supports 1–4× pinch zoom/pan and visible zoom/reset controls.
Spatial navigation requires a predominantly horizontal gesture at 1× (PR #21 below). A gesture
that ever uses multiple fingers/zoom cannot change page; panning while zoomed never
advances. Page navigation resets transient zoom/pan. PR #23 adds optional spreads below; no source stitching.
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
| Retained decoded pages | Single/continuous: 3; Double paged: 4 (ownership detail below) |
| Work | One serialized acquisition/decode across page-reader replacements, cooperative 10s deadline per page |
| Prefetch | Single/continuous: current ±1 / visible priority; Double: current + next/latest target only |
| UI bitmaps | Single/continuous: 3; Double: 4; serialized conversion, stale identities hidden |
| Zoom | 1–4× presentation transform; no higher-resolution decode or persisted zoom |

Preflight retains PR #16 static 8-bit PNG/chunk CRC policy and baseline/progressive
8-bit grayscale/RGB JPEG with optional JFIF, no EXIF/ICC/decoder extensions. Animated
PNG, SVG, GIF/WebP, arbitrary metadata/profiles and unsupported color depths fail
to placeholders. The same restrictive raster decoder applies to both readers.
Long multiplication precedes allocation; compressed byte bounds alone are insufficient.

For the original Single/continuous pipeline, at peak, 3 controller frames plus 3 UI bitmap copies total ≤24 MiB pixel payload,
with one ≤4 MiB decoder buffer and one ≤4 MiB conversion scratch buffer. Encoded
read/chunk assembly is ≤4 MiB plus 8 KiB; desktop provider encoded caching can add
≤2 MiB. Budget roughly 38 MiB payload/scratch in steady handover; an old, cancelling UI
conversion may temporarily retain its previous three-frame input (another ≤12 MiB),
so allow roughly 50 MiB bounded application payload during replacement; these historic
estimates do not include every retiring bitmap/native-owner reference (see the explicit
PR #23 handover bounds below), plus platform-provider,
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
and loading geometry do not replace persisted position. Since PR #21, a current
ticket/index/raster-stamp acknowledgement from a successfully composed UI bitmap
is required to save a new position. Navigation, prefetch, failed decode/conversion,
obsolete presentation and closing during loading cannot save the requested target.
Unavailable placeholders remain navigable but do not advance durable progress.
Progress throttles at 2s while reading, flushes on Back/close and uses the existing
application-owned writer. PAGE uses schema3/tag3; historical TEXT v1 and EPUB v2 bytes
remain unchanged/readable. No SQL migration or progress/cache amalgamation.

## Durable global mode

PageReaderSettingsStore is an independent owned contract. PageSettingsPersistence
loads once, leases updates to the current reader, holds one pending record and
coalesces changes over 300ms. Back/onStop/close wake the writer and application close
drains it. Newer timestamps win across owners; failed writes report a fixed message,
never pretend RAM proves durability. Missing/corrupt/future records safely use LTR.
Existing valid RTL/vertical/webtoon records retain their original meaning and encoding.

FilePageReaderSettingsStore uses one fixed 56-byte record: magic, schema,
mode/layout choice, nonnegative timestamp, SHA-256 checksum. Legacy Single encoding
remains version1; Double uses the canonical version2 form described below. Private same-directory temporary
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
three decoded frames in Single/continuous (four in Double, PR #23 below), with one
serialized decoder and bounded prefetch. At four MiB
maximum ARGB per frame, Single's three retained frames plus one in-flight decode are bounded
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

## Comic reader UX (PR #21)

The paged canvas is the primary input surface. Its actual available width determines
invisible left/center/right tap zones (30% / 40% / 30%). Left/right request logical
previous/next in LTR and next/previous in RTL. Taps beyond either end are harmless;
center toggles chrome without changing the page or its progress ticket. A drag,
pinch or consumed control event cannot also become a navigation tap. Desktop mouse
clicks use the same surface. Canvas accessibility actions reveal controls or invoke
logical Previous/Next without depending on spatial taps.

Chrome starts hidden and overlays the unchanged fit-page canvas. Top: Back, an
ellipsized title and Hide. Bottom: page count, Previous, Settings and Next. Those
buttons always retain logical meaning in either direction. Top/bottom chrome each
have a viewport-relative height cap and their own scrolling when space/fonts require
it; the center gap passes taps to the canvas. This keeps controls reachable in short
windows without nesting scroll owners or making the image smaller. Existing launcher
safe-drawing insets apply. Save failures remain readable with hidden chrome.

Settings exposes LTR/RTL using the existing durable global PageReader preference
writer and fixed record: no new database/schema or path is introduced. The preference
survives Back, another publication and a new application owner; default is now LTR.
The earlier vertical/webtoon and zoom/reset entries are retained, not added or expanded
by this PR. Continuous modes retain scrolling and center-tap chrome; side taps do not
request paged navigation in those modes. Chrome visibility and animation are transient.

Paged LTR/RTL now uses one spatial transition for taps and the existing swipe.
At 1x, a one-finger horizontal drag moves the current page continuously; its neighbor
is exactly one viewport away and receives the same displacement. Next enters from
the right in LTR, from the left in RTL; Previous reverses those directions. Tap zones
and logical buttons request an automatic version of the same movement. Center tap
still only toggles chrome. Vertical/Webtoon scrolling is unchanged.

Release completes after 25% of a viewport, or a deliberate fling of at least 5%
with agreeing velocity of at least 0.9 viewports/second. Otherwise it returns.
A 180ms position-only settle starts at the exact release offset; there is no fade,
incoming-only jitter, artificial loading delay or animation queue. Rapid requests
coalesce into one latest target (intermediate pages may be skipped); tickets retire
older animations/decodes. Grabbing an interrupted turn preserves its incoming
identity until the finger crosses the origin. Resize, mode changes and Back
invalidate the transition.
Zoomed gestures pan/zoom; a multi-pointer gesture cannot turn a page. Explicit
navigation buttons/taps reset zoom as before.

In Single mode the controller retains its existing maximum of three raster slots. During a normal
transition these contain current and adjacent pages; a rapid request prioritizes
current and latest target, with at most one other prefetch slot. The UI reuses its
same stamp-filtered conversion map (at most three bitmaps); the two artwork layers
borrow those references without copying pixels or adding a cache. Transition state
contains indices, ticket, stamp and normalized offset only. After settling only the
current artwork layer remains; an outgoing page may remain as ordinary bounded
adjacent prefetch, never as a separate transition owner. Cancelled conversions and
animation jobs release their references; platform bitmap reclamation remains GC
managed, as before.

A missing neighbor shows a bounded loading placeholder. On release it waits without
blocking the UI until successful bitmap conversion; a failed target returns to the
current page with a controlled message. No requested/prefetched/dragged page changes
progress. Logical position changes only after a validated target reaches rest; the
new composed bitmap then acknowledges progress using the existing periodic/Back-flush
writer. Failed, reverted, obsolete and closed transitions cannot save progress.
No hard timeout around synchronous native work is claimed.

Behavioral reference: Mihon's upstream `Pager.kt`, `PagerViewer.kt` and
`PagerViewers.kt` at
[`7aacaa3`](https://github.com/mihonapp/mihon/tree/7aacaa349019ff42b8b05403d8beebe94c8f6dfc/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager).
Its pager handles drag and animated tap navigation with one offscreen neighbor;
RTL reverses logical navigation's spatial direction. INFINILECT implements this
independently with Compose and its own bounded frames/state/progress handshake.
No Mihon code or dependency was copied. Its exact thresholds/progress callbacks
are not assumed to match ours.

PR #21 added no new reader modes, zoom/pan feature, double-page mode, cropping, filters,
brightness, image processing, arbitrary gesture or animation dependency is added.
Android automatic return into a comic after Activity recreation remains separate
work: recreation closes the old owner; reopening restores the last durable
successfully presented page. Reading direction remains locally durable; transition
state and chrome visibility remain transient.

### Android physical acceptance for PR #21

User-reported physical Android acceptance is complete for reviewed source
`0c52eb415490f410d46ee171eaa3560d64330606`. The user reports that the final
spatial page transition works correctly, the previous shake/jitter problem is
resolved, page navigation works correctly, and the PR #21 comic-reader UX is
physically acceptable on Android.

This is user-reported physical-device evidence, not Codex/device-lab automated
verification. The earlier incoming-only animation had failed physical acceptance;
the replacement spatial transition is the implementation now accepted. The checklist
below remains future regression guidance, not a record of individually reported
observations:

1. Open an imported CBZ; verify its restored page and fitted artwork.
2. In LTR, tap right → next; tap left → previous.
3. Tap center → controls appear; center again → controls disappear.
4. Use visible Previous/Next; they must not also trigger an underlying zone.
5. Enable RTL; right tap → previous and left tap → next; buttons stay logical.
6. Drag both ways: both pages follow the finger; short drags return and committed
   drags/taps slide naturally into place in LTR/RTL. Navigate rapidly; confirm no
   late old page flashes/replaces the latest one. Pan above 1x and pinch with two
   fingers; neither turns pages. Verify missing/failed neighbors do not save progress.
7. Close/reopen; last successfully presented page restores, including after failure.
8. Rotate/recreate and reopen; confirm position/preference restoration, no late work.
9. Check portrait/landscape, small heights and enlarged fonts; reach Back and Settings.
10. Check first/last boundaries, loading/error placeholders and accessibility controls.

Desktop graphical acceptance is also pending: mouse taps/buttons/settings, rapid
navigation and Back, resize short/wide windows, restart progress/preferences and
native Windows/Linux/Wayland/niri presentation. Headless Compose interaction/layout
tests and successful compilation do not establish physical or native-window acceptance.

### Original automated verification for PR #21

[Focused run](https://github.com/SirOtter0/INFINILECT/actions/runs/37580430095):
64 Desktop cases (2m31s), 59 Android host cases (1m20s), followed by successful
Android/Desktop application compilation (27s). This includes all 22 existing
PageReaderController cases, eight interaction/progress cases, five headless Compose
layout/input cases on Desktop, seven preference-writer cases, 16 file-preference
cases and six real-file progress/durability cases. Each test target used:
`--tests '*PageReader*Test' --tests '*PageSettingsPersistenceTest' --tests '*FilePageReaderSettingsStoreTest' --tests '*PageProgressDurabilityTest'`.

[Final affected-module run](https://github.com/SirOtter0/INFINILECT/actions/runs/37580953953)
at `abb245ef4f4a18be81acd25876e8a393cb05ac12` ran all `app` tests: 837 Desktop
and 795 Android host, zero failures/errors/skips, plus both application targets.
`BUILD SUCCESSFUL in 2m 38s`; all 36 tasks executed. Production/tests were identical
to the original PR #21 tree at `cb122e6`; subsequent changes in that run only recorded evidence and removed the
temporary workflow. No repository-wide clean matrix, signing operation or APK
build was performed as part of that original automated verification.

```sh
./gradlew :app:desktopTest :app:testAndroidHostTest :androidApp:compileDebugKotlin :desktopApp:compileKotlin --no-daemon --console=plain --max-workers=2
```

XML counts were independently checked after downloading artifact `11463938768`
(seven-day retention), ZIP SHA-256
`b8ee3090f89edbef78f71b00a2ef268b1d0598df6a7a491dc0d1315048d93435`.
Temporary feature-specific automation is removed from the final tree. Compilation
and host/headless checks do not replace the physical/graphical checklist above.

### Spatial-transition follow-up verification

[Final follow-up run](https://github.com/SirOtter0/INFINILECT/actions/runs/37625944475)
at `7ec959c3d004c85064d9d6fb4c59a5951d675c20` passed focused Desktop (79 cases,
2m27s) and Android host (71 cases, 1m23s) before the final all-app regression:
852 Desktop + 807 Android host, zero failures/errors/skips, and successful
Android/Desktop application compilation (`BUILD SUCCESSFUL in 52s`).
Production and tests are unchanged after that run; the final commit records this
evidence and removes temporary automation. The broader app run was justified by
moving logical page establishment from navigation request to validated settle.
No repository-wide clean matrix or signing/APK work was performed in this follow-up.

Each focused target used these filters (plus the flags below):
`--tests '*PageReader*Test' --tests '*PageTransitionTest' --tests '*PageSettingsPersistenceTest' --tests '*FilePageReaderSettingsStoreTest' --tests '*PageProgressDurabilityTest'`.
Targets were `:app:desktopTest` and `:app:testAndroidHostTest`; the final command was:

```sh
./gradlew :app:desktopTest :app:testAndroidHostTest :androidApp:compileDebugKotlin :desktopApp:compileKotlin --no-daemon --console=plain --max-workers=2
```

Twelve deterministic transition cases cover directions, threshold/velocity,
bounds, zoom exclusion, coalesced/interrupted turns, stale decode/callbacks,
failure/cancellation/progress/reopen, Back, resize and mode changes. Eight real
headless Compose layout/input cases include three new drag/dual-artwork,
short-return/zoom-pan, and failed/rapid-turn/disposal cases. Existing controller,
center-tap/accessibility, settings, file-progress and app regressions remain green.
The first development run exposed one incorrect boundary-test expectation;
it was corrected before the successful runs.

Artifact `11484516372` (seven-day retention) XML totals and focused selection were
independently checked; ZIP SHA-256:
`90d1684a0c21c5c93b56d4c66b9bf523db5026c5a3558d21bae1bb813a80fef9`.
`git diff --check` and tracked signing-file/private-key/attachment-path checks
passed. The final tree contains no feature-specific workflow or signing material.
The final spatial transition subsequently received the user-reported physical
Android acceptance recorded above. Automated evidence remains distinct from that
report; native Desktop graphical acceptance, including Windows/Linux/Wayland/niri,
remains pending. No Desktop physical/graphical verification is claimed.

## Double-page presentation (PR #23)

Single page remains the default. In paged LTR/RTL, Settings → Page layout offers
Single page and Double page using the existing preference store and writer. Double
is retained but ignored in Vertical/Webtoon; those modes keep their individual-page
layout, fraction progress, spacing and three-slot window. No orientation-based
automatic selection is implemented.

`PageSpread(anchor, second?)` contains logical indices, not copied PageEntry metadata
or pixels. Grouping scans the logical sequence once: page 0 is always alone (fixed
cover policy); source **width > height** is wide and always alone; other pages,
including square pages, pair with the next non-wide page. A page immediately before
a wide page remains alone. Pairing restarts after each wide page. Unmatched final
pages remain alone. Thus portrait/wide sequence P,P,W,P,P becomes `[0],[1],[2],[3,4]`.
Geometry comes from the existing verified preparation/preflight pipeline. There is
no image analysis, splitting, joining or cover-content heuristic.

For logical pair A then B, LTR places A left/B right; RTL places B left/A right.
Logical numbering, Previous/Next semantics and publication identity do not reverse.
Pairs use one common fit scale, a fixed 2dp gutter and adjacent aspect-preserving
page rectangles; their combined rectangle is centered with no default crop.
Wide/single pages use the full viewport. The indicator
uses logical 1-based numbers (`2–3 / 10` or `1 / 10`). Navigation advances between
non-overlapping spreads, with safe first/last boundaries.

PR #21's authoritative spatial pager now moves the entire spread as one unit:
current spread follows the finger and incoming spread starts one viewport away;
release threshold/velocity and the 180ms settle remain unchanged. Side taps,
accessibility actions and semantic Previous/Next share that transition. The center
40% toggles controls; side zones remain 30% each. Rapid requests coalesce into one
latest target. Pair pages have no independent animation or zoom state. The complete
canvas zooms/pans at 1–4×. Above 1×, one-finger movement pans first; deliberate
horizontal excess at a real pan boundary can hand off to the same spatial pager.
Multi-pointer/pinch input cannot turn a spread, and zoomed side taps do not navigate.

### Progress and interruption

A spread's anchor is its first logical page in reading order, regardless of RTL
placement. Progress remains `ReadingLocator.Page` with existing identity/schema.
Request, prefetch, decode, conversion and animation start do not save. All pages'
current raster stamps and successful bitmap conversions must authorize the target;
only the complete spread composed at rest can acknowledge its logical anchor. If
either page fails, the target never commits and the previous spread/progress remain.
Conversion failure uses the same controlled unavailable behavior, never half-success.

Reopening a saved second page reconstructs its containing spread, but normalization
alone does not write progress. After successful presentation the anchor can be saved.
Single → Double resolves the containing spread; Double → Single retains the anchor.
Settings change, Back/close or viewport width/height change retire the old ticket,
transition offset and obsolete work. Old callbacks cannot commit after such a change.
Rotation/resize never changes the saved layout preference. Activity recreation still
requires reopening the publication; automatic reader-session restoration is deferred.

### Explicit ownership bounds

| Application ownership | Single / continuous | Double paged |
| --- | --- | --- |
| Controller decoded raster slots | 3 | 4 |
| Current converted bitmap identities | 3 | 4 |
| Spread artwork layers | Single paged: 2 | 2 (current + incoming) |
| Paged artwork image children | 2 | 4 |
| Source/read/decode jobs inside native permit | 1 globally | 1 globally |
| Bitmap conversions inside native permit | 1 globally | 1 globally |

Four slots are sufficient for current pair + latest target pair. At rest the next
spread may occupy target slots; there is no additional previous/next spread cache.
Existing identities are borrowed by both presentation layers, never converted twice
for layering. Navigation speed and publication length (still ≤512 pages) cannot
increase the slot count. Input/cache bounds are encoded in the converter and window;
100 rapid replacement requests, 8/512-page documents, cancelled non-cooperative work,
identity reuse and Back/reopen serialization are tested.

During cancellation/handover, one converter may borrow its retiring input of at most
four rasters while the controller holds four new rasters and one serialized decode
produces a result: **at most nine application-held raster identities**, including
transient work (Single-only bound seven). The current bitmap cache plus its one
conversion result is at most **five** identities. Allowing four borrowed identities from a retiring reader owner gives
**nine bitmap identities during owner handover** (Single-only seven). Conversion
byte scratch is a separate bounded buffer. `collectLatest` cancels and joins before
replacing conversion work; upstream change signals contain stamps only, with latest rasters read after
retiring work joins, so pending signals cannot retain an extra intermediate window.
Shared decode/conversion mutexes also serialize across Back/reopen owners.
Cancelled mutex waiters do not become another cache. These are ownership/active-work
bounds, not an exact process-heap/GC/GPU ceiling or immediate native reclamation claim.
Provider decode buffers, bitmap-conversion scratch, encoded read buffers and framework
snapshots/native/GPU overhead remain additional bounded pipeline/allocator costs.

Paired native pages use factor-two source sampling in both dimensions before ARGB
allocation (Android BitmapFactory / Desktop ImageIO). Pair pages share the fitted spread;
standalone pages keep the original decode policy. Sampling is opt-in for PageReader;
EPUB and other raster callers retain their existing decode path. Original source
preflight still enforces 2 MiB encoded, 2048 per dimension and 1,048,576 source pixels;
no global image/source limit increases. Odd source sizes allow codec rounding only
within half-size floor/ceil. Injected decoders may return the original already-bounded
size, preserving the decoder contract/test adapters; native adapters sample pairs.

Preferences keep the same private path, atomic writer and fixed 56-byte record.
Single writes the unchanged canonical v1 mode 0–3; Double uses v2 choice 4–7 (mode
plus four). Both forms read into the same model; unknown/noncanonical schema/choices
fail closed. This is a preference-format extension, not a publication/progress schema
migration. No dependency, Android permission, signing or release change is required.

### Limitations and future physical acceptance

Physical Android acceptance for PR #23 is **pending**. PR #21's acceptance above
covers its original single-page transition only. Native Desktop graphical acceptance
is separately pending. Automated host/headless checks do not establish either.

Future Android checklist:

1. Verify Single remains default and its navigation/rendering still work.
2. Select Double; verify first page alone, portrait pair, unmatched final page and
   wide source page alone; verify pairing resumes after the wide page.
3. Check LTR and RTL placements, logical Previous/Next and first/last boundaries.
4. Drag forwards/backwards; verify complete spreads follow the finger, short drags
   return and flings/side taps/buttons settle smoothly. Navigate rapidly both ways.
5. Zoom/pan the whole spread; pinch and one-finger pan must not turn pages. Reset
   to 1× and confirm navigation resumes.
6. Center-tap controls, settings and logical page-range indicator remain usable.
7. Rotate portrait/landscape during a turn and at rest; check stale offsets/work.
8. Close/reopen; verify logical-anchor progress and retained layout preference.
9. Check failed-page handling preserves the previous spread/progress.
10. Check Vertical and Webtoon regressions, then return to paged Double.

Deferred: automatic orientation layout, cover heuristics beyond first-page-alone,
pairing offsets, source double-page splitting/joining, crop/margin removal, brightness,
contrast/color filters/enhancement/image processing, new Webtoon behavior, OCR,
translation, panel detection and guided view. CBZ preparation, import ownership,
Library/History/Search, EPUB/PDF/TEXT, acquisition and storage identity are unchanged.

### Initial automated PR #23 verification

Production/test revision `e4b02795e31a72b9d380b8dbdebbb0061e7f1bb0` passed
all app regressions: **906 Desktop + 851 Android-host tests**, zero failures,
errors or skips. PageReader-focused coverage comprises 147 Desktop cases within
that Desktop run and a separate 125-case Android-host focused run. This includes
all existing PR #21 assertions plus grouping, both placement directions, complete
spread progress/failure handshakes, mode/reopen behavior, rapid/obsolete work,
conversion/decode ownership, persisted preferences, real PNG/JPEG sampling and
headless paired layout/drag/zoom/pinch/resize checks.

```sh
./gradlew :app:desktopTest :app:testAndroidHostTest :androidApp:compileDebugKotlin :desktopApp:compileKotlin --no-daemon --console=plain --max-workers=2
```

Both application compilation targets passed. The final command reused the current
successful full Desktop result and ran the full Android-host suite. Core is unchanged,
so unrelated core suites were not repeated. XML totals were checked independently;
`git diff --check`, the final scope review and signing-secret/generated-artifact scan
passed. Headless native-library setup was external to the repository; no temporary
CI, signing configuration, dependency or generated acceptance artifact was added.
The subsequent commit only records this evidence. Physical Android and native
Desktop graphical acceptance remain pending.

### Physical Android pre-fix findings and UX follow-up

**USER-REPORTED PHYSICAL ANDROID PRE-FIX FINDINGS:** The user tested the PR #23
APK and `PR23-double-page-acceptance.cbz`. Intended Double grouping/navigation
otherwise behaved as expected, but landscape pairs had excessive internal page
separation and zoom >1× prevented page/spread navigation by drag. The user supplied
a screenshot as spacing reproduction evidence. These findings triggered this
follow-up; they are not final physical acceptance. No device model/version or other
individual checklist observations are inferred.

The old pair fit each page separately inside half the viewport. Height-limited
pages therefore left a large artificial gap. Now a common scale is
`min((viewportWidth - gutter) / (widthA + widthB), viewportHeight / max(heightA, heightB))`.
Scaled rectangles sit directly adjacent in a centered row, with a **2dp gutter**
(capped at 10% of width only for exceptionally tiny viewports). Each page is vertically
centered; unused space lies outside the pair. LTR/RTL placement and grouping are
unchanged; standalone artwork still uses ordinary fit-page.

Zoomed pan bounds now use the actual fitted content rectangle: horizontal/vertical
half-range is `max(0, (fittedExtent * zoom - viewportExtent) / 2)`. A 0.5-pixel
near-edge epsilon avoids float jitter. One finger consumes available pan first;
only additional outward horizontal movement reaches the pager, in the same gesture.
Horizontal intent must exceed touch slop and dominate vertical movement by 1.2×;
vertical-first gestures remain pan-only. Content narrower than the viewport has zero
horizontal pan range, allowing deliberate overscroll without fake pan distance.
Reversal consumes pager displacement first, then resumes pan inside the content.

The existing authoritative transition/ticket, target preparation and presentation
handshake are reused. Thresholds remain **25% viewport excess**, or **at least 5%
excess plus agreeing velocity ≥0.9 viewports/second**; settle remains **180ms**.
Zoomed side taps stay non-navigating; center tap and explicit Previous/Next retain
their semantics. Another pointer cancels the handoff and gives pinch exclusive
ownership for the remainder of the gesture. Successful turns present the new spread
at fit/1×; returned, reversed or failed turns retain current zoom/pan. Resize/mode
change retires old tickets and restores fit, as in the existing reader. No progress
is saved during pan, overscroll, target preparation or animation; only the validated
complete target composed at rest can persist its logical anchor. Resource/cache
bounds, decoding, grouping, progress identity and continuous modes are unchanged.

**AUTOMATED FOLLOW-UP VERIFICATION:** Production/test revision
`35d9e89722238e1f2fd54893fbd3164d0ce9fb49` passed **172 focused Desktop +
140 focused Android-host PageReader tests**, then **931 full Desktop app +
866 full Android-host app tests**, all with zero failures/errors/skips. Android
application (`:androidApp:compileDebugKotlin`) and Desktop application
(`:desktopApp:compileKotlin`) compilation passed. Existing PR #21/#23 assertions
remain intact; two pan fixtures now zoom enough to have real fitted-content pan
range. Core and dependencies are unchanged; no unrelated core suites were run.
The complete incremental diff was reviewed and `git diff --check` passed. The
repository scan found no signing secrets, attachment paths, generated acceptance
artifacts or temporary CI; native headless test setup remains external. The
subsequent commit only records this verification and adjusts documentation wrapping.

**Physical Android re-acceptance is PENDING. Native Desktop graphical acceptance
is PENDING. PR #23 remains DRAFT and must not be merged.**

Focused re-acceptance with the same CBZ:

1. **Landscape pair:** inspect [2,3]/[5,6]; tiny gutter, centered complete pair, no
   giant gap; check both LTR and RTL.
2. **Pan:** zoom >1× and drag within real pan range; content pans without turning.
3. **Next/Previous edges:** pan to the appropriate boundary and continue outward;
   short excess returns, qualifying excess turns, in both directions.
4. **Cancel:** begin an edge turn and reverse/release below threshold; current
   spread and coherent zoom/pan remain. Successful turns reset the new spread to 1×.
5. **Pinch:** multi-touch/pinch must not turn, including after handoff starts.
6. **Regression:** 1× drags/side taps, center controls and grouping
   [1] [2,3] [4] [5,6] [7]; wide PAGE 4 remains alone.

### Fast zoomed edge swipe follow-up

**USER-REPORTED PHYSICAL ANDROID:** After testing the updated APK, the user
reports that compact Double positioning, zoomed edge navigation and other
previously tested reader behavior work as expected. Very fast zoomed swipes
sometimes move the spread and return; approximately two attempts may return
before a third turns. This is reported behavior, not final physical acceptance.

A timed headless **touch** reproduction confirmed one cause of this pattern:
`calculatePan()` excludes a lifted pointer, dropping actual movement delivered
on pointer-up. At an existing edge, down at 0ms, move 20px at 8ms and up at 80px
at 16ms retained only 20px, below the 32px minimum on a 640px viewport. Two such
attempts returned, while a third with 180px before up turned. The same test
now turns on all three attempts. A physical event trace would be needed to
attribute every reported failed swipe to this specific cause.

Zoomed one-finger input now consumes the primary pointer's measured delta,
including up, through the unchanged pan-first bounds/handoff before release.
Pager velocity uses measured, controller-clamped overscroll positions, rather
than full finger/pan positions: signed recent displacement / elapsed time, at
most eight scalar samples over 100ms. Pan-only samples reset the zero seed;
a mixed pan/edge segment credits only excess over its measured time. Reversal
discards earlier outward velocity; a 40ms stationary tail yields zero velocity.
Two timed samples suffice; equal/backwards timestamps or non-finite inputs do
not invent velocity. Grabbing an animation seeds its offset without crediting
inherited animation movement as finger velocity.

Completion still requires **25% displacement**, or **at least 5% actual pager
displacement plus agreeing ≥0.9 viewports/second velocity**, with **180ms** settle.
No threshold was lowered and no distance or velocity boost was added. 1× input
and its existing VelocityTracker, pan bounds, compact layout, grouping, modes,
side-tap/pinch exclusion, tickets/animation and presentation-only progress remain
unchanged. Successful turns reset to fit; cancelled/failed turns retain zoom/pan.
The raster/bitmap/job bounds are unchanged; the scalar history owns no artwork.
The separate orientation/menu state-reset issue is outside this follow-up.

**Physical Android re-acceptance of this fast-swipe fix is PENDING. Native
Desktop graphical acceptance remains PENDING. Keep PR #23 DRAFT and unmerged.**
