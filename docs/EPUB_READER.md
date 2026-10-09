# Passive EPUB reader — bounded EPUB2/EPUB3 subset

The existing [EPUB preparation](EPUB.md) proves structure, ownership and bounded
local resource access. Merged PR #15 added the initial reader; Draft PR #16 improves
its session presentation and bounded local media. This reader adds a separate **passive presentation** boundary.
It supports a narrow EPUB2/EPUB3 XHTML subset on Android and Desktop with the same
Compose UI. It does not claim generic EPUB compatibility or a completed v0.0.1.

## PR #27: immersive reading controls

Merged PR #26 is the engine baseline. The user reports physically on Android that
EPUB performance is much better. Initial PR #27 physical testing found a functional
reader but three UX defects: navigation required opening chrome, Controls/percentage
remained permanently visible, and Android bars stayed light in dark reading mode.
These are user-reported pre-fix observations; acceptance of this follow-up is pending.

Normal reading now shows **only publication content**. Short, stationary, unconsumed
single-pointer taps use the actual viewport width: left 25% moves backward, center
50% toggles chrome, right 25% moves forward. Center includes the exact 25%/75% dividing
coordinates. Lateral navigation scrolls **85% of the viewport**, keeping **15% overlap**;
it does not jump an entire chapter. At a measured scroll boundary, the current ticket
requests the next/previous semantic window, or the adjacent spine document when the
actual chapter boundary is reached. Backward chapter entry restores its ending
passage. Publication endpoints do nothing safely. Existing serialized preparation,
prefetch/cache, global paragraph keys and rolling rebase are reused.

Links, text selection, long presses, vertical drags and multi-pointer input get first
refusal through the final pointer pass; no page-turn drag gesture is introduced.
Opaque toolbar/warning areas consume even blank/disabled-control touches. Native
panels own their input. Pending navigation and active viewport scrolls do not acquire
an unbounded tap queue. Callback registration follows the current ticket, and tap
navigation validates visible global keys against the current model before using
scroll bounds. A controlled pending-window test exposed a previous end-of-buffer
sentinel being treated as chapter completion: after rebase, it could alternate
lookahead direction and saved position without new input. Only a real chapter end
now reports the chapter's final block; temporary window edges keep the measured
first-visible semantic locator and cannot trigger that oscillation.
Requests and failed targets never become progress: only the
existing measured/presented semantic locator can be saved. Errors retain the previous
passage and expose the existing controlled Retry action.

Hidden chrome has no persistent Controls button, percentage or reserved button inset.
Nonvisual accessibility actions toggle controls or move by a viewport. **F10** also
exposes controls on Desktop. Shown top/bottom toolbars offer contextual Back, Contents,
Settings, **Previous/Next spine-section navigation**, and existing whole-book progress.
The indicator says **Section X of N**, never a window count or fabricated page number.
Alt+Left/Right keeps the existing semantic window/section navigation. Up/Down and
Page Up/Down scroll; Escape dismisses a panel, then chrome, then returns to the opening
screen. Tab/Shift+Tab and Enter operate controls. Modified selection shortcuts retain
child handling.

Toolbars overlay the centered maximum-760dp reading column; opening/closing chrome
or panels changes no list geometry, presentation ticket or locator. Visible toolbars
can temporarily cover edge text. Settings/Contents are compact scrollable native modal
panels, constrained to the viewport and a 480dp maximum width. Android Back closes the
panel before chrome; closing a panel restores opener focus. Icon controls have labels
and minimum 48dp targets. Library membership uses the same action inside Settings;
other readers retain their UI.

Text uses platform serif with system fallback, headings use the UI family, and
preformatted text remains monospace. Light/dark paper-and-ink palettes preserve 4.5:1
normal text/link contrast. Existing font size, spacing, margins and System/Light/Dark
preferences use the same global persistence. Typography and viewport changes use
canonical semantic restoration, including deep long-chapter positions. The 12-entry
text-layout lookup pins the first visible paragraph; borrowed composed-text layouts
retire with lazy composition. Semantic/index, disk, parse, image and bitmap budgets,
publication/session ownership, ZIP/XML security and progress schemas are unchanged.

### Android system bars

A presentation-only appearance callback reports the EPUB paper color and resolved
System/Light/Dark mode while the reader is mounted; disposal restores application
appearance. MainActivity draws that opaque color **before** its safe-area padding,
behind transparent bars. The existing Activity edge-to-edge helper applies explicit
light/dark SystemBarStyle, including matching icon contrast. It does not depend on
setting deprecated status/navigation bar background colors, which Android 15+ can
ignore when edge-to-edge is enforced. Insets remain consumed once. On API 29+ the
navigation contrast scrim is disabled for EPUB because the opaque backdrop provides
contrast, for both gesture and three-button navigation. Supported API 26–28 uses the
Activity compatibility implementation. Exiting EPUB restores the existing light
application appearance; Dark is not forced on other destinations.

Automated tests verify shared appearance synchronization, theme resolution, remount
and disposal. Compilation verifies the Android integration. Native bar pixels/icons,
OEM-enforced scrims, gesture/three-button navigation and TalkBack need physical checks;
headless Android-host tests cannot establish these graphical observations. This is
color/inset integration, not system-bar hiding or a fullscreen redesign.

Follow-up coverage adds **14 tests** (6 common boundary/theme/gesture tests and
8 headless shared-UI tests), retaining prior assertions and adapting removed-control
interactions to accessibility/keyboard actions. Focused suites passed **511 Desktop /
460 Android-host**; the loading-overlay regression failed before its barrier and
passed afterward. Final full app regression passed **1,146 Desktop /1,031 Android-host**,
zero failures/errors/skips. Android and Desktop compilation passed with JDK 21.
Core suites were not rerun because core is unchanged. Diff and secret/artifact checks
passed. Native system bars are not covered by the host graphical tests.
Labels follow the existing English-only pattern; no font downloads, publisher CSS,
new dependency, parser/cache redesign or localization system is added. Physical
Android re-acceptance and native Desktop graphical acceptance remain pending.

Manual Android re-acceptance:

1. Open a short EPUB and Montecristo: default chrome/progress is absent. Tap left,
   center and right; check viewport overlap and discovery/dismissal of controls.
2. Traverse long windows in both directions and actual chapter boundaries; verify no
   omitted/duplicate text, rapid-tap corruption or position loss. Overlay Previous/Next
   should navigate actual spine sections; TOC/internal deep links still work.
3. Scroll, select/copy, long-press and use links/multi-touch. They must not trigger tap
   navigation; buttons, panel backgrounds and disabled/blank toolbar areas must not
   pass taps to the passage.
4. Switch System/Light/Dark, including system appearance changes. Check bar backgrounds
   and icon contrast, rotation and safe areas, gesture and three-button navigation;
   leave the reader and confirm normal application appearance returns.
5. Deep in a chapter, change typography, rotate, close/reopen and restart after saving.
   Confirm semantic progress/preferences, readable pending content, Retry and TalkBack
   actions/focus. Desktop separately: F10, keys, resize, mouse selection and links.

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
alt/unsupported text. Only manifest-owned resources are opened. Narrow static SVG
cover wrappers around a single owned PNG/JPEG use that same image path/model;
the SVG itself is not rendered. No SVG/GIF/WebP decoding,
responsive `srcset`/picture selection, remote or data URLs, external fonts or browser.

Not supported: arbitrary CSS, publisher fonts, fixed-layout fidelity, RTL layout
policy, scripting, audio/video/forms, SVG/MathML rendering, remote resources,
DRM, annotations or Downloads. EPUB2/NCX follows the PR #22 contract. Foreign
namespace/active content fails except the explicitly validated raster-cover wrapper.
No generic EPUB compatibility or production acquisition claim.

### Reading settings and semantic position

A compact, height-bounded scrollable Settings dialog controls font size (14–30sp), line spacing (120–200%),
horizontal margins (8–40dp) and system/light/dark reading theme. Settings affect only
EPUB presentation. They are global user preferences, restored across reader exit,
other EPUBs, process restart and cache deletion; TEXT remains unchanged. Reading text remains real
accessible text; image alt labels, theme controls and navigation are labelled.

Changes in settings, width, density or font scale preserve the latest semantic
locator, invalidate previous layout tickets, and restore its block/containing line
using the new layout. They never persist pixels/indices, reparse the chapter or save
loading layouts. Theme/style changes do not mutate chapter content. Restoration is
approximate within a passage; stable 200dp image space prevents delayed decoding from
moving the viewport. Whole percentages remain chapter-weighted approximations.
PR #24 also retickets a newly mounted presentation from the controller’s latest
live locator, rather than its older chapter-entry position. Configuration recreation
retains that controller/document; no chapter reparse or progress reload is required.

### Durable global EPUB preferences (PR #16 physical-test follow-up)

The initial PR #16 implementation kept settings in each reader; physical acceptance
passed its media/navigation/progress behavior but identified unwanted resets. The
follow-up adds `EpubReaderSettingsStore` behind the application-owned
`EpubSettingsPersistence`; no filesystem, database or Compose persistence types
enter the controller. `FileEpubReaderSettingsStore` is the current replaceable adapter.
Settings are user preferences, **not ReadingProgress, ResourceCache or Library/History**.
There is no publication identifier/content, telemetry, network or schema migration.

One fixed **68-byte** big-endian record (`settings.preferences`) contains an 8-byte
magic, version1, four Int fields (font/spacing/margin/theme), a Long choice timestamp,
and SHA-256 of the first36 bytes. The loader reads only this exact length, validates
checksum/version/ranges/theme/nonnegative ordering timestamp, and otherwise uses
current defaults:18sp/150%/16dp/SYSTEM. All invalid/out-of-range values are rejected
as a record, never trusted as layout values. Missing/future records also use defaults.
The checksum detects corruption, not authenticity against an attacker with app-data access.

Android selects `applicationContext.filesDir/epub-reader-preferences-v1` (not cacheDir);
only the path is retained. Desktop uses a sibling of reading-progress-v1 in the
existing absolute per-user persistent app-data root: Linux XDG_DATA_HOME or
~/.local/share, macOS Application Support, Windows LOCALAPPDATA or AppData/Local,
under org.infinilect.app. Relative/unsafe paths never fall back to cwd. Cache deletion
cannot remove preferences; application data deletion can.

IO is off the UI thread. A process monitor and short OS file lock serialize recreated
owners; NOFOLLOW_LINKS rejects root/record/lock symlinks. The fixed record is written
to a same-directory unique temp, forced, then atomically replaces the target. Failed,
unsupported or cancelled pre-commit operations leave the previous committed record;
no destructive fallback. Only exact owned stale temp names are cleaned under lock;
unrelated files survive. POSIX attributes use the Android-compatible path view,
never getFileStore. Successful rename provides atomic visibility and normal-restart
durability, not a power-loss guarantee for unsynced directory metadata.

The application writer owns one latest pending record and serializes saves with a
**300ms coalescing interval** (continuous adjustments cannot postpone saving forever).
Back/reader close/onStop request an immediate flush; application close drains its
independent worker; Desktop joins it before intentional process exit, and
awaitProgressClosed also joins it. Each storage operation has a5s
coroutine deadline; non-cooperative OS IO can delay cancellation. No write occurs for
viewport-only changes. A current-reader lease rejects old-reader edits; immutable
record timestamps use a process-monotonic clock and store comparisons reject older
draining-owner writes. The latest in-memory choice is useful for the next reader,
but only committed file bytes prove restart persistence. A failed save shows the
fixed message “Reading settings could not be saved on this device.”; a later successful
save clears it. Saving choices does not change semantic locator/progress schemas.

As with progress, Android process death provides no guaranteed final callback:
allow a short moment for the periodic save. Host tests exercise real files and fresh
owners but do not establish Android-device correctness by themselves. The user
subsequently physically verified the follow-up at HEAD
`d365e59d3868c674de062168fe88db5c007cad75`: leaving/reopening and process restart
preserve settings, and the persistence fix works correctly. This is user-reported
Android evidence, not Codex/device testing.

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

Image references use the bounded [EPUB URI resolver](EPUB.md): safe relative
parents and single UTF-8 percent decoding are supported; root escape, encoded
separators/dot segments, schemes, query and fragments remain rejected. Undeclared
resources remain rejected. The controller independently
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
Fragments use bounded ASCII content tokens, including recoverable numeric IDs;
missing anchors produce a fixed reader error. Exact standard XHTML/NCX declarations
are removed before SAX, and XHTML `nbsp` is a fixed character alias. Custom/internal
DTDs, other named entities, PI/XInclude/xml:base remain forbidden. Required SAX
hardening fails closed. Scripts/forms/objects/iframes/events/active media and foreign
namespaces cannot reach Compose except the validated one-raster cover projection.
CSS is never interpreted or fetched.

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
| Semantic window / individual block text | 65,536 / 8,192 UTF-16 units |
| Blocks / text-append events per window | 128 / 8,192 |
| Window descriptors / links / anchors per XHTML document | 512 / 512 / 4,096 |
| Retained semantic windows / blocks / text | 2 / 256 / 131,072 UTF-16 units |
| Legacy whole-model API blocks / text / append events | 2,048 / 262,144 / 8,192 (unchanged) |
| TOC entries / nested list depth / label | 256 / 16 / 512 UTF-16 units |
| Transient Compose text layouts | 12 |
| Chapter navigation parse jobs | One serialized parse; latest request wins; identical requests coalesce |
| Private semantic cache | 2 indexed chapters × 4 MiB; 8 MiB/document including construction; 16 MiB/preparer even after failed deletion |
| Open/preparation / chapter navigation deadline | 60 seconds / 15 seconds |

One parse transiently holds at most 1MiB XML bytes (bounded read helper additionally
uses up to another 1MiB in chunks plus 8KiB buffer), strict decoded XML ≤2MiB, and
one bounded tree with ≤20,000 nodes. Ordered SAX content coalesces character events;
parts grow with element transitions, never with each character. Direct/ordered
text copies are each bounded by XML text. The complete tree is discarded after
one window/nav model is built; standard-declaration normalization can temporarily
hold another decoded XML copy. Two windows retain at most 131,072 UTF-16 units in
run text and the same amount in assembled block text (about 512KiB character
payload), plus explicitly bounded blocks/runs/anchors/TOC metadata.
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

## PR #16 Android acceptance checklist — user-reported verification

The user reported successful physical acceptance of the initial reader, then
successful leave/reopen and process-restart settings persistence on the follow-up
HEAD above. This checklist remains a reference; automated host tests are separate
and Codex did not perform Android device testing.

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
14. Fully terminate/relaunch, reopen; chapter/passage and all four global EPUB settings restore.
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

## EPUB2 compatibility (PR #22)

EPUB2 NCX and EPUB3 XHTML nav both produce the same owned `EpubTocEntry` list.
The existing TOC UI, ordered-spine chapter navigation and semantic progress path
are reused without a second reader. Missing/malformed NCX is a controlled preparation
failure, not an empty-successful TOC. Valid literal-space/percent-encoded-space and
NFC UTF-8 resource names resolve to manifest-owned entries; image references may
use safe document-relative parent segments. XML/TOC/chapter/image ownership limits
and passive/no-network presentation remain unchanged. See [preparation policy](EPUB.md)
for exact supported subset, bounds, URI rules and physical acceptance checklist.

### User-reported physical Android acceptance

The user reports successful installation of the PR #22 development APK and
manual testing of all four supplied positive EPUB fixtures without reported
errors. See [the exact physical evidence and unclaimed checklist observations](EPUB.md#user-reported-physical-android-acceptance).
This does not claim individual TOC/order/image/progress/import/Library/History or
TXT/CBZ/PDF manual observations. Automated evidence remains separate, and native
Desktop graphical acceptance (Windows/Linux/Wayland/niri) remains pending.

## PR #25 manual Android acceptance

The five supplied originals were inspected locally; see the precise
[classifications and remaining limits](EPUB.md#pr-25-real-world-compatibility).
No original publication is committed. Automated host results are separate from
physical Android acceptance, which remains pending for this change.

1. Import Sun Tzu, Séneca, Analectas and Meditaciones through the Android picker;
   verify titles, opening, local covers, Contents and chapter navigation.
2. Repeat one import with a misleading extension or generic provider MIME; byte
   validation should give the same publication and deduplicate owned content.
3. Read a noninitial passage, change typography/rotate, exit/reopen from Library
   and History; confirm semantic position continuity from PR #24.
4. Import Montecristo and open its cover. Large chapters currently report the
   supported size/reading limit; this PR does not claim complete reading support.
   A failed chapter must not replace the last successful saved locator.

Invalid/encrypted, hostile-input and failed-import cleanup cases are covered by
synthetic automated tests; they do not require hostile files on the device.

The reader preserves existing semantic locators, publication identity, progress
schema, two-chapter retention and raster/bitmap ownership. It still does not provide
arbitrary SVG/CSS/font rendering, all named XHTML entities, UTF-16 XML, media
fallback/overlays, DRM or universal EPUB conformance. Native Desktop graphical
acceptance remains separate and pending.

## PR #26: long chapters with bounded semantic windows

The historical PR #25 Montecristo LIMIT result above is superseded by this
reader change. The user reports PR #25 was subsequently merged and physically
accepted on Android; that evidence established opening Montecristo, not complete
reading of every chapter.

USER-REPORTED PHYSICAL ANDROID: long chapters now work, but window boundaries and
Previous/Next pause for several seconds. This prompted the performance follow-up;
no physical-device profiling was supplied. Android re-acceptance remains pending.

### Parsing and ownership

The old production reader called the full-model `chapter()` API, which rejects
the entire chapter on its 2,049th block. Splitting that already built list would
still retain the whole semantic chapter and its XML tree. The reader now calls
`window()` instead; the historical full-model API and its negative limit tests
keep their original guards.

A prepared document now builds a bounded, session-private semantic index on its
first request for a chapter: read and validate the entire owned XHTML with hardened
SAX, then emit each scratch block to a private file in **one** semantic scan.
Only window checkpoints, anchors and ordinals stay in memory. After the complete
scan succeeds, read the requested window and release the XML tree. Warm Block/End
or anchor requests seek directly to a checkpoint; another locator scans record
headers with an 8KiB scratch buffer, rather than retaining a whole-chapter address
index or re-parsing XHTML. Text order, styles and global locators remain identical.
A window stops at 128 blocks, 65,536 UTF-16 units or 8,192 append events. No text is
truncated; a single oversized block still gives LIMIT, including in later content.

The file LRU holds **two chapters, at most 4MiB each / 8MiB per document**, including
incomplete construction. Evict before building; failed deletions prohibit quota
reuse. The preparer shares four file reservations across its two documents: at most 16MiB
additional session disk, including construction and failed-deletion files after
a document retires. Such reservations are released only after deletion succeeds.
Metadata is capped per file at 512 window descriptors, 4,096 anchors and 4,096 anchor ordinals.
There is no retained chapter text/tree, whole-book index, whole-file serialization
buffer or persistent cache schema. IO uses 8KiB buffers; individual encoded strings
are bounded by the existing 8,192-unit block (at most 32KiB UTF-8). Adjacent inline
runs now use linear StringBuilder coalescing instead of repeatedly copying growing
strings; whitespace/heading predicates are reused.

Files have random owned names inside the existing locked **private application
session**, never publisher-selected paths. No ZIP extraction or external content
exposure. In-memory file/window SHA-256 checksums, strict lengths/UTF-8/field limits,
NOFOLLOW_LINKS and manifest/spine ownership checks guard reads. Corrupt/missing files
are discarded and rebuilt from validated source. Cache files are never trusted or
reused after restart. Document close cancels work, drains serialized IO and deletes
owned files before releasing ZIP/session ownership; unlocked stale sessions are
cleaned under the existing owner-lock policy. OS deletion failures are best-effort,
as with prepared ZIPs; they cannot allow unbounded new cache files. Capacity/storage
failure uses the original bounded two-pass selection instead of rejecting supported
content. Capacity failures are remembered only for that document's owned spine paths.

This remains bounded **tree parsing on a cold chapter**, not streaming XML: 1MiB
source XML, 20,000 nodes/depth32 and individual-block limits remain unchanged. Cold
publication preparation still verifies archive CRCs and structural XHTML; reopening
builds a fresh index. One serialized reader parse and one serialized document-cache
operation permit no unbounded navigation queue. No new dependency.

The controller retains two windows: at most 256 blocks, 131,072 text units and
16,384 append events/runs. During preparation, these two windows may coexist with
one incoming window and one scratch block: at most 385 semantic block objects and
204,800 text units in this working set (about 800KiB character payload counting run
and assembled-text copies). Temporary concatenation/building allocations and object
headers are additional, bounded by the individual block/XML limits; these numbers
are structural ownership bounds, not measured Android heap/RSS. A scan keeps at most
512 window descriptors, 4,096 anchor positions and 4,096 anchor ordinals; offsets
are bounded by the 20,000 XML nodes. Retained windows keep at most 8,192 anchor records.
The transient source-byte/chunk/decoded-text/tree budgets in the table still apply.
Image work is unchanged: one provider decode, two decoded images and two distinct
UI bitmaps, with existing byte/pixel limits. Windowing creates no image buffers.

### Navigation and semantic continuity

LazyColumn presents the current and one neighboring window with stable global
block keys. Scroll direction replaces the old neighbor while preserving the same
visible paragraph and local pixel position. An explicit index rebase is necessary
because Compose's nearby-key lookup can miss removal of 128 items. That rebase may
end an ongoing inertial fling; physical scroll feel still needs device acceptance.
There is no window indicator or permanent window control. Existing Previous/Next
traverse windows (previous ends at the preceding window's final block), then the
ordered spine at the actual chapter boundary.

A failed load preserves the current passage and its media, including chapter/link
navigation. A delayed (180ms) thin loading overlay does not move the passage or blank
the reader. Retry repeats the failed destination; it never sends the reader to chapter
one. Repeated identical pending requests coalesce; requesting an active lookahead
promotes that job instead of cancelling/restarting parsing. Recent destinations use
the two-window RAM cache synchronously or the bounded file LRU without XHTML work.

Lookahead starts when a window becomes current, providing a neighboring window of
runway, and also prepares the adjacent spine at a long chapter's boundary, using the
existing chapter-entry destination of Previous/Next. Reflow/remount changes UI tickets without discarding still-valid semantic IO. Rolling updates
keep the scroll observer alive and reuse annotated text; old local indices cannot
save until their global paragraph keys agree with the new window. Loading/failed
or prefetched destinations never overwrite the last successful locator.

TOC/NCX, EPUB3 nav and owned internal links select the window containing their
anchor, including late numeric IDs. Global element paths, segment offsets and
Unicode code-point logical starts remain identical to the old parser. Durable
locators still store canonical spine path + element path + code-point offset +
whole-chapter progression. Window ordinal and pixels are never persisted. Old
locators restore directly; a missing element uses whole-chapter progression, and
a removed spine path retains the established first-chapter fallback. Publication
identity, progress encoding and storage schemas are unchanged.

During an active session, the last presented locator owns reading progress; an
unacknowledged navigation target owns only restoration intent. A presentation
remount or typography/viewport change preserves the appropriate semantic locator
and retires obsolete callbacks. Buffered, requested, decoded and failed windows
never advance progress. A new long-chapter target is acknowledged only after the
UI restores/measures it and any initial image is ready or has a controlled fallback.
The established whole-small-chapter navigation contract remains compatible.
Close cancels work, flushes legitimate pending progress, releases windows/media and
closes the owned document. Cancellation or stale generations cannot publish/save.

PR #25 declaration/NBSP/numeric-ID/static raster-cover compatibility is unchanged,
as are ZIP/collision/expansion bounds, single URI decoding, owned-only resources,
DTD/entity restrictions and encryption rejection. No browser, JS, arbitrary SVG
renderer or network/resource fallback is introduced. Broader EPUB/CSS/font/encoding
conformance remains outside the supported subset.

### Automated original-book verification

The five supplied originals were accessible. An external, uncommitted diagnostic
on both Desktop and Android-host imported each file, entered every spine document,
walked all windows forward/backward, resolved its TOC and restored a deep saved
locator through fresh preparation. Every block, run, style, offset and anchor was
compared with an external whole-chapter reference derived from the PR #25 visitor.
Only that diagnostic reference lifts the whole-model limits to build the oracle;
production limits and existing test assertions are not relaxed. No original book,
artwork, external harness or generated artifact is committed.

| Original EPUB2 publication | Spine documents | Semantic blocks | Windows across all documents | Result on both hosts |
| --- | ---: | ---: | ---: | --- |
| El conde de Montecristo | 26 | 63,190 | 512 | Complete semantic traversal; all 22 formerly oversized documents pass |
| El arte de la guerra | 15 | 453 | 15 | Pass |
| De la brevedad de la vida | 22 | 48 | 22 | Pass |
| Analectas | 50 | 1,344 | 50 | Pass |
| Las meditaciones de Marco Aurelio | 3 | 506 | 7 | Pass |

Montecristo's largest document produces 3,269 semantic blocks including its heading.
Comparison proves no missing/duplicated semantic content at boundaries; it does not
claim publisher-layout fidelity, device rendering or measured memory/performance.
Automated object-count/text/job bounds pass; physical memory profiling remains pending.

### Initial PR #26 automated verification

Verified production/test revision: `fa63da856bef2ac63d2396a3f9746c4202a9d32d`.
The following evidence update changes documentation only. Forty-three original
synthetic tests were added: parser16, controller23, headless Compose3 and owned-import
integration1. The initial 3,001-paragraph reader reproduction failed with LIMIT
before correction. Existing whole-model limit/security assertions remain intact;
the integration failure fixture now uses an oversized individual block because
2,049 legal paragraphs are readable.

- Final focused EPUB/navigation/security/import/continuity: **329 Desktop / 315
  Android-host**, zero failures, errors or skipped tests.
- Complete app regression: **1,072 Desktop / 984 Android-host**, zero failures,
  errors or skipped tests, counted from JUnit XML without the external book harness.
- Android `:androidApp:compileDebugKotlin` and Desktop `:desktopApp:compileKotlin`
  passed. Core is unchanged; unrelated core tests were not rerun.
- The separate external original-book diagnostic passed **5/5 on each host** on
  the same verified production/test revision, including complete reference comparison.
- Full diff review, `git diff --check`, clean working tree and exact signing-secret,
  private-key, attachment-path and generated-artifact scans passed. No new dependency,
  schema, permissions, signing configuration or temporary CI workflow.

### Performance diagnosis and host measurements

At reviewed HEAD `b8e075af018b6e0218f5636a59d12cddf780dcb0`, every uncached window
re-read/validated the complete XHTML and scanned all semantics twice. Rolling
lookahead evicted the just-read neighbor; Next could cancel an equivalent prefetch,
reflow cancelled semantic work, and ticket changes rebuilt text/restarted its scroll
observer. Four deterministic controller tests reproduced the initial wasted-work paths
before correction; another failed on inverse lookahead targeting End rather than
the existing Previous button's chapter entry. Session-wide file reservations also
prevent failed cleanup from freeing quota for subsequent documents. The host did **not** reproduce several-second pauses or
establish a physical Android CPU/GC/Compose breakdown; those pauses are user-reported evidence.

External, uncommitted diagnostics used the actual Montecristo spine document23
(3,269 blocks), Linux amd64 / AMD EPYC 9V74 / JDK21.0.12.1. Eight warm-ups, 30 window/
cold-index/anchor/locator samples and 15 controller turns per direction. Android-host
is another JVM test worker on this Linux host, not Android-device performance. Times
include coroutine dispatch; no wall-clock pass/fail threshold is imposed on tests.
Cold means an evicted semantic index in an already-prepared document; OS file caches
and JIT are warmed, not a first-ever application launch. A separate headless Skia
700×600 probe compared the old/new UI functions with the same current controller
and original synthetic 5,200-block models, excluding parser IO: 8 warm-ups /30 turns,
16.308 ms median (p95 37.228) before versus 10.312 ms (p95 19.499) after. This isolates
presentation work, not complete old/new app latency, Android frames or graphical
Desktop acceptance; benchmark order/JIT/render-backend differences remain limits.

| Path, median (p95) ms | Desktop before | Desktop after | Android-host before | Android-host after |
| --- | ---: | ---: | ---: | ---: |
| Window request, previously full scans / now indexed | 26.759 (45.105) | 0.911 (1.189) | 36.770 (48.525) | 0.938 (1.717) |
| Cold index + selected window | same uncached path | 16.602 (25.997) | same uncached path | 16.647 (31.562) |
| Controller Next, same rapid protocol | 25.032 (40.783) | 0.371 (1.167) | 26.053 (37.268) | 0.880 (2.935) |
| Controller Previous | unmeasured | 0.424 (1.524) | unmeasured | 0.774 (9.316) |
| Next after completed prefetch | unmeasured | 0.040 (0.458) | unmeasured | 0.051 (0.910) |
| Prefetch completion | unmeasured | 0.483 (1.291) | unmeasured | 0.800 (2.158) |
| Warm distant anchor | unmeasured | 0.836 (1.075) | unmeasured | 0.694 (1.144) |
| Warm deep locator | unmeasured | 3.891 (11.244) | unmeasured | 2.265 (4.787) |

The initial Desktop median stages were resource read 1.089ms, SAX5.892ms,
validation2.018ms, full index scan7.935ms and second materialization scan7.637ms;
Android-host respectively 1.283/7.437/2.286/10.523/10.602ms. Warm indexing removes
those source/XML/full-semantic operations. The measured two chapter files occupied
511,167 bytes; after repeated turns the reader retained 256 blocks /9,609 text units.
Deterministic tests also enforce the maximum RAM/disk/job budgets through eviction,
construction, cancellation and close. These are ownership/work-count bounds, not
measured Android heap/RSS or a promise of unchanged total process memory. Host cold
median and p95 improve in the final run; earlier measurements showed tail variability.
Scheduling, JIT/GC, disk and device capabilities matter; <100ms cached navigation and imperceptible
prefetch are engineering targets, with physical measurement still required.

### Overnight follow-up verification

Verified production/test revision: `bfd201b78d941ece6824fa8bb5f5052c067c025c`.
The following commit changes only
architecture/measurement/evidence documentation. Thirty-three deterministic tests
were added in this follow-up: cache19, navigation/recovery12, headless Compose2.
Five controller performance reproductions failed before correction; existing
PR #21–26 assertions were not weakened or disabled.

- Focused EPUB/import/continuity/progress: **478 Desktop /455 Android-host**,
  zero failures/errors/skips.
- Complete app: **1,105 Desktop /1,015 Android-host**, zero failures/errors/skips,
  counted from JUnit XML with external diagnostics excluded. Existing EPUB2/EPUB3,
  ZIP/XML/URI security, owned import/progress and CBZ/PDF/TEXT tests are included.
- Android `:androidApp:compileDebugKotlin` and Desktop `:desktopApp:compileKotlin`
  passed. Core is unchanged; unrelated core suites were not rerun.
- Full incremental diff review, `git diff --check` and exact signing-secret,
  private-key, attachment-path and generated-artifact scans passed. No dependency,
  schema, permissions, signing configuration or temporary CI change.

The five original books passed again on both hosts, with full forward/backward
reference comparison, every spine/TOC target, deep fresh-owner reopen and private
cache cleanup. No originals, generated diagnostic artifacts or external harnesses
are committed. Android re-acceptance and native Desktop graphical acceptance remain
pending; current host performance is not physical acceptance.

### Manual Android re-acceptance — pending

1. In Montecristo, scroll through several consecutive long-window boundaries and
   use Previous/Next in both directions; note cold versus revisited response times.
   Check chapter endings and no missing/duplicated paragraphs.
2. Use Contents/internal links to distant passages; return to recent passages.
   Tap rapidly while loading; no repeated work, blank screen or stale destination.
3. If a destination is delayed, the old passage remains readable with only a subtle
   loading line. If a recoverable error occurs, Retry reaches the same destination
   and the last valid progress remains intact. No hostile fixture is needed.
4. Deep in a long chapter, change font/margins and rotate; retain the semantic passage.
   Close/reopen via Library/History, restart after a save interval, and open another
   book and return. Fresh preparation/indexing is expected after reopen.
5. Repeat a short chapter and the other four originals. Check local covers/Contents
   and existing CBZ/PDF/TEXT behavior. During an extended session, observe responsiveness
   and available device memory; no device memory/performance claim is made here.

The rolling index rebase can still end an inertial fling; physical scroll feel,
Android performance/memory and native Desktop graphical acceptance remain pending.
