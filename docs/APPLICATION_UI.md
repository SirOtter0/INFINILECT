# Application interface

The application uses shared Compose Material components on Android and Desktop.
This presentation layer does not own publications, acquire resources or save
reader positions. `ApplicationSession` remains the navigation/session owner.

## Screen audit and changes

| Existing surface | Before | Current presentation |
| --- | --- | --- |
| Application root | Import button and three equal action buttons above every destination | Quiet app header; Library, History, Search and Settings in one adaptive navigation hierarchy |
| Search/source browsing | Large branding, source buttons, plain result rows | Restrained heading, existing source choices and query semantics, shared publication cards and bounded scrollable feedback/results |
| Library | Plain rows and separators | Cover-first portrait grid, direct resume, title gradient, known progress edge, contextual actions and confirmed removal |
| History | Plain newest-first rows, TXT-only empty wording | Compact thumbnail rows, author/last-opened metadata, Continue reading, contextual actions, existing newest-first ordering and clear-history confirmation |
| Publication metadata | Inline catalog metadata only; no separate details screen | Focus-managed, scrollable metadata dialog; shows only available author/language/format/source/rights information |
| Local import | Plain busy/error text | Consistent validation/loading/error card, Cancel, original picker and importer unchanged |
| Publication opening | Spinner or plain failure column | Shared loading/error surfaces with Back and the existing retry contract |
| Application settings | No application-level settings screen | System/Light/Dark application appearance; existing reader settings stay inside readers |
| History confirmation | Standard confirmation | Shared theme/shape, accessible actions; same deletion contract |
| Permissions | Platform document picker; no custom permission screen | Unchanged platform picker/permissions |
| Content readers | EPUB, CBZ/images, PDF, TEXT | Existing controls, rendering, lifecycle, preferences and progress contracts preserved |

There is no separate home dashboard, download manager or permission explanation
screen in the current code. This PR does not invent those destinations or
redesign discovery/source functionality planned for PR #29.

## Shared visual language

`app.ui` supplies two restrained, high-contrast palettes, Material typography,
8/16/24 dp spacing, rounded surfaces, labeled navigation, publication cards,
feedback cards and metadata/settings presentation. Standard buttons retain
keyboard activation and at least 48 dp targets. Navigation uses labeled tabs
rather than unexplained icon-only controls. There are no new dependencies,
network fonts, cover downloads, image caches or custom rendering systems.

Below 840 dp window width, destinations use bottom navigation. Wider windows use
a 176 dp labeled side rail. Content is centered within a 1120 dp maximum width;
collections use adaptive 340 dp minimum columns. Font scaling and short windows
keep content scrollable. Search retains its independently bounded header and lazy
result viewport; import feedback can use at most half the available content height.
Alt+1/2/3/4 select Library/History/Search/Settings on Desktop; Tab/Enter activate
normal controls and Escape dismisses details. Android Back dismisses the platform
dialog first, cancels an active import, returns from a reader to its origin, or
returns a secondary destination to Search. Root Back retains system exit behavior.

Search query/results/source remain session-owned. Library/History grid positions
are separately remembered/saveable across navigation and reader return. Details
read metadata already held by the UI; opening still resolves current source-owned
metadata/resources through the existing validation path.

## Honest metadata and progress

The core publication/snapshot model has no cover field. A separate app-owned
presentation provider borrows existing private local imports; identity and progress
schemas stay unchanged. It does not fetch artwork from catalog/source URLs.
Metadata dialogs omit missing authors/languages/rights. Known formats come from
validated import metadata or legitimate progress records, never BOOK/DOCUMENT type.

### Covers and contextual actions

Library uses two columns at ordinary phone widths (320–559 dp), one below 320 dp,
and a growing column count above that, within the existing 1120 dp content cap.
Portrait tiles are 2:3; artwork uses aspect-preserving **Fit**, without cropping or
stretching. Titles sit on a dark bottom gradient. Unknown progress stays absent.
History uses one-column compact rows with 56×84 dp thumbnails, known metadata,
locale-formatted last-opened time and an explicit Continue reading action.

Primary tap/Enter resumes directly through the existing source/session checks.
Long press, right-click, Menu/Shift+F10, the visible 48 dp actions button and
screen-reader custom actions expose details/resume/removal. Context menus request
focus and dismiss with Escape/Back. Removal requires confirmation and removes only
the selected collection entry; imported bytes and reading positions are retained.
Committed removals invalidate shared artwork; failed removals retain it. History
clear invalidates the displayed history's cached thumbnails only after success.

| Format/source | Local cover support | Intentional fallback |
| --- | --- | --- |
| Imported EPUB3 | Declared manifest `cover-image`, PNG/JPEG | Missing/ambiguous declaration, unsupported art (including SVG), rejected image |
| Imported EPUB2 | OPF `meta name="cover" content="manifest-id"`, PNG/JPEG | Same; no guessed first-spine artwork |
| Imported CBZ | First PNG/JPEG in the reader's natural filename order | Missing/rejected image |
| Imported PDF | Known format, typographic cover | No native first-page rendering/resource lifetime added |
| Imported TEXT | Local title initial, existing author and deterministic palette | No network artwork |
| Remote/catalog publication | Typographic cover from held metadata | No new network/acquisition or remote cover contract |

Only immutable, digest-checked imported payloads authorize archive cover reads.
The provider borrows the existing payload instead of preparing/copying a book.
EPUB container/OPF parsing uses existing bounded hardened XML and URI resolution;
ZIP traversal/collision/count/ratio/expanded-size checks remain in force. Selected
entries verify CRC, exact length and EOF. Encryption/signature declarations fail
closed. CBZ retains its stricter ZIP/directory policy and exact reader ordering.
Only the declared/chosen image is decoded, with the existing passive PNG/JPEG
preflight: encoded ≤2 MiB, source dimension ≤2048, source pixels ≤1,048,576. Larger
or unsupported images safely use the fallback, rather than relaxing reader limits.

### Thumbnail ownership and budgets

One application-owned LRU is shared by Library and History. It has **24 slots**,
including negative, pending and visible/pinned entries. Leases share the same
bitmap; no bitmap is copied for a second screen. If every slot is pinned, extra
visible items use the typographic fallback until revisited. Old unleased slots are
evicted and their artwork flows cleared; revisiting recent positive/negative results
avoids archive parsing. Expired leases cannot pin evicted bitmap references.

One load/decode/conversion job runs at a time, with at most 24 bounded entries
awaiting work and a 10-second load timeout. Leaving the viewport cancels unused
work; identity checks discard invalidated/obsolete results. App close cancels and
joins work, clears artwork references before releasing the private import owner.
Local SHA-based identities naturally separate changed payloads/publications.

Native decoding uses power-of-two source subsampling before pixel materialization,
within **192×288 / 55,296 pixels per thumbnail**. Maximum cache-held RGBA artwork is
**5,308,416 bytes (~5.06 MiB)**. This is retained bitmap payload, not total process
heap/native memory: the one in-flight job also owns ≤2 MiB encoded image bytes,
small bounded ZIP/OPF metadata, a transient raster and conversion/native buffers.
Existing ZIP metadata (≤512 entries), XML (≤1 MiB) and import (≤32 MiB streamed hash)
limits apply. Framework garbage collection ultimately reclaims retired bitmaps.
**Thumbnail disk budget: 0 bytes**; no temporary thumbnail files or second copy
of a publication are created. Cold digest/metadata work is serialized on IO and
can still be noticeable with very large imports; device performance is unmeasured.

Local supplementary checks used the five user-supplied EPUBs without copying them
into the repository. Desktop ImageIO produced cover thumbnails for Art of War,
Montecristo, Analects and Meditations. Seneca fell back safely: its declared PNG
contains unsupported ancillary chunks and an invalid iCCP CRC (independent ZIP/PNG
inspection). Cold archive-metadata/decode checks were 15–96 ms in this managed
JDK 21 host, excluding private-import hashing/UI/device work; these are not Android
performance measurements or acceptance results.

Progress bars use only the existing writer's bounded, read-only recent records,
matched by the full publication identity and newest timestamp. They appear after
a session has submitted a legitimate position. Persisted progress is still restored
by the reader; this PR does not enumerate progress files or fabricate 0% for an
unopened publication. Percentages can therefore be absent after restarting until
a reader supplies a known position. Library/History schemas are unchanged.

## Appearance and lifecycle

Application appearance is independent of EPUB/PageReader preferences. One
application-owned worker loads and coalesces a single choice. Edits during loading
win over disk, failures remain visible with retry, and final application close
drains the pending choice. One fixed 40-byte checksummed record uses private
persistent storage and atomic replacement, outside the collections database and
resource cache. Cancellation cleans up its temporary file. Existing preference
records and reader settings are not migrated or overwritten.

Android draws the current opaque application background behind transparent system
bars before consuming safe drawing/IME insets. Icon contrast follows the resolved
appearance. EPUB's existing reader override takes precedence and clears on exit;
the other readers retain their original light surface/override. System follows
OS appearance; explicit Light/Dark affect only application screens. Navigation-bar
contrast enforcement is disabled on API 29+ because the opaque backdrop supplies
contrast. OEM behavior, gesture and three-button navigation require physical tests.

## Verification and limitations

Headless Compose tests cover compact/wide windows, large fonts, selected tabs,
keyboard shortcuts, dialogs, collection feedback, imports, history confirmation,
reader return and grid/progress preservation. Common/JVM tests cover preference
loading/edit races, coalescing, close/drain, persistence failure/retry, fixed-size
records, corrupt records/symlinks, exact progress identity and palette contrast.
Existing reader, parser/security, acquisition and persistence tests remain required.

Final verification: 1,219 Desktop app tests and 1,073 Android-host app tests,
zero failures/errors/skips; Android and Desktop compilation passed with JDK 21.
Focused cover/collection verification passed 61 Desktop / 30 Android-host tests.
This follow-up adds 48 unique tests (30 shared/host and 18 Desktop, including
9 additional collection UI cases); existing assertions remain intact. Full suites
include all four reader-continuity layout tests and existing EPUB/CBZ/PDF/TEXT regressions. Test execution uses the existing
Skiko library and a writable external cache in this managed headless environment;
no temporary test workflow/configuration is committed.

Native Android/desktop graphical acceptance is pending. English remains the
existing UI language; no new localization infrastructure is introduced. PDF/remote covers, undeclared EPUB/SVG cover heuristics, cross-restart collection
progress summaries and discovery redesign remain outside this PR.

## Manual Android acceptance

1. Visit Library, History, Search and Settings; use Back and return from each reader.
2. Import TEXT/EPUB/CBZ/PDF; cancel the picker/import and try a rejected file.
3. Open details, inspect known metadata/rights, dismiss with Back, then open a book.
4. Tap covers to resume; open actions by button/long press, inspect details and cancel/confirm removal. Clear History and confirm Library/progress/imported bytes remain.
5. Choose System/Light/Dark, restart, and check bars with gesture/three-button navigation.
6. Open an EPUB with a different reader appearance, then exit; the application theme returns.
7. Read at a noninitial position, return and reopen; position/preferences remain intact.
8. Rotate/resize and increase system font size; actions and lists remain reachable.
9. Check EPUB2/EPUB3 declared covers and CBZ first-page artwork; TEXT/PDF/missing/rejected artwork uses readable typographic covers.
10. Scroll a larger Library, switch History/Library, rotate/resize and resume; no position resets or layout jumps.
11. On Desktop, resize across 840 dp, use Alt+1..4, Tab/Enter, right-click and Menu/Shift+F10; dismiss menus/details with Escape.
