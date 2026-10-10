# Application interface

The application uses shared Compose Material components on Android and Desktop.
This presentation layer does not own publications, acquire resources or save
reader positions. `ApplicationSession` remains the navigation/session owner.

## Screen audit and changes

| Existing surface | Before | Current presentation |
| --- | --- | --- |
| Application root | Import button and three equal action buttons above every destination | Compact wordmark/import icon; Home, Library, History, Search and Settings with icons and labels |
| Search/source browsing | Large branding, source buttons, plain result rows | Restrained heading, existing source choices and query semantics, shared publication cards and bounded scrollable feedback/results |
| Library | Plain rows and separators | Cover-only portrait grid, details on tap, title gradient, known progress edge and contextual multi-selection |
| History | Plain newest-first rows, TXT-only empty wording | Compact thumbnail rows grouped by actual last-opened dates; cover opens details, row resumes, trailing removal keeps Library and progress |
| Publication metadata | Inline catalog metadata only; no separate details screen | Responsive details page/dialog with fixed close, safe optional EPUB synopsis, contextual reading action and source/rights information |
| Local import | Plain busy/error text | Consistent validation/loading/error card, Cancel, original picker and importer unchanged |
| Publication opening | Spinner or plain failure column | Shared loading/error surfaces with Back and the existing retry contract |
| Home/profile | No Home, profile or first-run setup | Local greeting, real resume/recent Library sections, optional nonblocking local setup and Settings → Profile |
| Application settings | No application-level settings screen | System/Light/Dark appearance and local profile; reader settings stay inside readers |
| History confirmation | Standard confirmation | Shared theme/shape, accessible actions; same deletion contract |
| Permissions | Platform document picker; no custom permission screen | Unchanged platform picker/permissions |
| Content readers | EPUB, CBZ/images, PDF, TEXT | Existing controls, rendering, lifecycle, preferences and progress contracts preserved |

Home is now the normal startup destination. There is no download manager, online
account or custom permission screen. Search/source behavior is retained; discovery
and recommendation backends belong to PR #29.

## Shared visual language

`app.ui` supplies two restrained, high-contrast palettes, Material typography,
8/16/24 dp spacing, rounded surfaces, labeled navigation, publication cards,
feedback cards and metadata/settings presentation. Standard buttons retain
keyboard activation and at least 48 dp targets. Navigation combines original outline icons with concise accessible labels. There are no new dependencies,
network fonts, cover downloads or custom rendering systems. The bounded local thumbnail cache is described below.

Below 840 dp window width, destinations use bottom navigation. Wider windows use
a 176 dp labeled side rail. Content is centered within a 1120 dp maximum width;
Library uses adaptive portrait-cover columns; History remains a compact single-column list. Font scaling and short windows
keep content scrollable. Search retains its independently bounded header and lazy
result viewport; import feedback can use at most half the available content height.
Alt+1/2/3/4/5 select Home/Library/History/Search/Settings on Desktop; Tab/Enter activate
normal controls and Escape dismisses details. Android Back dismisses the platform
dialog first, cancels an active import, returns from a reader to its origin, or
returns a secondary destination to Home. Root Back retains system exit behavior.

Search query/results/source remain session-owned. Library/History grid positions
are separately remembered/saveable across navigation and reader return. Details use held metadata plus a bounded, read-only refresh of optional local EPUB descriptions and one saved-progress summary. Opening still resolves current source-owned metadata/resources through the existing validation path.

## Honest metadata and progress

The core publication/snapshot model has no cover field. A separate app-owned
presentation provider borrows existing private local imports; identity and progress
schemas stay unchanged. It does not fetch artwork from catalog/source URLs.
Metadata dialogs omit missing authors/languages/rights. Known formats come from
validated import metadata or legitimate progress records, never BOOK/DOCUMENT type.

### Optional publication synopsis

`PublicationDescriptions` is an application-owned, read-only presentation cache.
Details requests optional metadata only for an existing digest-addressed private
local EPUB import, including imports made before this change. The original private
payload is the durable source of the description: no import-record, Library,
History, profile, identity or reading-progress migration is needed. No new files,
publication copies or source/network requests are made. Clearing Android cache
only requires metadata to be read again; it does not remove private publication
or profile records. Reading details never writes a locator.

The loader checks payload integrity using the existing streamed SHA-256 and
archive ownership guards. It reads only `mimetype`, `META-INF/container.xml` and
the referenced OPF, not chapters/images. Existing EPUB2/3 ZIP traversal/collision,
size/ratio/entry, path, CRC, encryption/signature and strict XML/entity/DTD guards
remain. Only namespace-correct `dc:description` values are accepted, in metadata
order; empty values are skipped and identical values deduplicated. Paragraphs
are retained. Escaped/static markup is projected to **plain text**, with a fixed
safe entity allowlist and valid numeric scalars decoded once. Active-element
bodies are discarded; no HTML execution, links, WebView, SVG, remote resources or
summary generation is introduced.

Limits: **8 description values / 16,384 UTF-16 units in aggregate**, existing
**1 MiB XML**, **20,000 XML nodes / depth 32 / 8,192 text units per XML element**.
Optional malformed/unsupported/oversized metadata is omitted without blocking
reading or changing an existing record. The LRU keeps **8 results**, including
absence, for at most **131,072 retained text units (~256 KiB UTF-16 payload)**,
plus one bounded container/OPF parse. There is one load at a time with a **10-second
budget**; closing details cancels its request, load/storage exceptions are not cached,
and final owner close cancels work and clears entries. Absent/unsupported immutable metadata may be cached. Disk cache budget is **0**.
Immutable content identities make cached metadata stable; a different payload has
its own ID. No good cached value is overwritten by empty metadata.

PDF's current native reader API exposes no descriptive metadata; CBZ's strict
image-only contract does not admit ComicInfo.xml; TEXT has no structured metadata.
Those formats, remote catalogs and EPUBs without descriptions omit Synopsis.
Broader metadata support is deferred rather than loosening reader/import security.
Supplementary OPF inspection of the five user-supplied books found a description
only in Analects; the other four legitimately have no `dc:description`. Books are
not redistributed or committed.

Details uses a full-width page below **600 dp**, otherwise a centered **600 dp**
maximum dialog. The Close control stays fixed; all content/actions/rights notices
scroll within safe viewport insets. Long titles/authors wrap. A **600-unit excerpt**
and Read more/Show less preserve paragraph breaks and Unicode. No synopsis means
no empty heading. Start reading/Continue reading follows an actual saved record,
including a bounded one-publication lookup after restart; both use the unchanged
reader open/resume path. Back/Escape dismisses without acquiring bytes or saving
progress. Cover artwork has no invented action.

Home now uses 8 dp vertical edge padding, 4 dp greeting/subtitle spacing, 16 dp
section spacing and two-line titles on fixed 144×216 dp carousel covers. Existing
fit artwork, gradient contrast, subtle progress and shared thumbnail leases remain.
The toolbar uses a labeled 48 dp document/import icon, standard focus/keyboard
activation and a Desktop hover tooltip. Branding still requires an official logo;
the repository's launcher vector explicitly identifies itself as provisional.

### Covers and contextual actions

Library uses two columns at ordinary phone widths (320–559 dp), one below 320 dp,
and a growing column count above that, within the existing 1120 dp content cap.
Portrait tiles are 2:3; artwork uses aspect-preserving **Fit**, without cropping or
stretching. Titles sit on a dark bottom gradient. Unknown progress stays absent.
History uses compact rows with 48×72 dp thumbnails, available title/author, and
known semantic page/progress information. A saved page locator displays its actual
1-based page index without inventing a total. EPUB locators have no stored chapter
label/ordinal, so element/window paths never become fabricated chapter/page numbers.
Unknown progress stays absent. Rows group the existing **last-opened** timestamps
in newest-first order: Today, Yesterday or locale-formatted calendar dates in the
current timezone. They do not pretend to measure every later reading interaction.
The existing 50-entry recent-history query remains unchanged.

### Details, selection and removal

Normal Library covers have no format badge, ellipsis or management button. Tap/Enter
opens the focus-managed details dialog, with artwork, known metadata, Start reading/Continue reading
reading and the repository-backed Add/Remove Library action. Opening still resolves
current source ownership and restores the existing locator. Adding the same full
publication identity uses the existing repository key, not a duplicate record.

Long press selects the initial cover. Subsequent taps toggle selected publications;
selected covers have a border/check and announced selection state. A compact action
bar shows the count, Select all, Clear selection and Remove from Library. It reserves
space above the grid and keeps bottom navigation reachable. Library and History
have accessible pane titles without redundant visible screen headings; History
retains meaningful date-group headings. The same lazy grid state and stable publication keys
preserve the browsing position. Selection is session presentation state, bounded by
the existing **1,000-entry Library** capacity. Leaving the destination or opening a
reader clears it. Android Back exits selection before navigating away; Escape does
the same even when focus is on the application root. Right-click or Menu/Shift+F10
opens a keyboard-focused context menu with Select, Details, Continue and Remove;
TalkBack has equivalent custom actions. No permanent menu trigger is needed.

Batch removal confirms a snapshot of the selected IDs, then performs **one sequential
batch** without an accumulating queue. Only committed IDs leave Library; failures
remain selected with a fixed count/error message and can be retried. Duplicate requests
are ignored while busy. Cancellation reconciles membership from storage; one final
change notification/list refresh avoids repeatedly rescanning the entire Library for
every removed item. Committed removals invalidate artwork; failures retain it.

**Supported batch action: Remove from Library. Mark as read/unread is deferred.**
The current model has no separate organizational read flag. Implementing it requires
a deliberate persistence/schema contract; rewriting the semantic locator or percentage
would corrupt resume behavior. This PR leaves identity, schemas and progress unchanged.

History cover tap opens details; tapping the remaining row resumes directly. Its
48 dp trailing trash action confirms removal of **only that history entry**. Individual
Library removal, batch Library removal, History removal and clear History keep original
files, private imported copies and saved positions. Removing History never removes
Library membership; removing Library never clears History. Destructive list changes
are not file deletion. Details/menu dismissal and selection do not acquire publication
bytes or write reading progress. Reader functionality is unchanged.

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

One application-owned LRU is shared by Home, Library, History and details. It has **24 slots**,
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

Progress bars use only legitimate saved positions, matched by the full publication
identity and newest timestamp; unknown progress stays absent. Home can retrieve
cross-restart summaries for **at most eight recent History publication identities**.
This app-only read API reuses the existing progress record format and IO/monitor/OS
lock: scan cap 4,096 directory entries, ≤16 KiB per checksummed record, one record
at a time, at most eight results. It checks the identity-derived filename and does
not follow symlinks, open source content, or write/normalize locators. The existing
1,024-record / 16 MiB storage quota is unchanged. One cancellable, generation-checked
Home lookup with a five-second budget is retired on destination change, reader open
or app close. Recent in-session positions win over older summaries. Collection
progress indicators retain the existing recent-session contract; Home summaries
do not become a new durable position owner.

## Home and local profile

Home has a vertically scrolling greeting, **Continue reading** from actual History
and saved progress, and **Recently added** from real Library insertion timestamps.
Each section shows at most eight distinct full publication identities. Covers use
the existing shared pipeline; Continue opens/resumes directly, Recently added opens
details. Links lead to full History/Library. No fake dates, EPUB pages, genres or
recommendations are generated. Empty Home explains local import and provides an
import action. Home uses held/local metadata offline and never reacquires content;
a previously saved remote/catalog item still uses its existing source contract
when explicitly opened. Section composition is an extension point for future
source-supplied discovery, without a speculative provider framework.

The normal startup destination is Home; opening/returning from a reader retains
its originating Home/Library/History/Search destination and scroll/session state.
A five-destination compact bar fits normal phone widths; wide Desktop windows use
the same icons and labels in a side rail. The global import action is a 48 dp
icon button with an accessible label. Asset inspection found only an explicitly
provisional Android launcher-book vector, not an official INFINILECT logo. The
wordmark remains; neither an invented logo nor the mascot substitutes for branding.

The local profile contains an optional trimmed name (≤80 UTF-16 units; no control
characters), optional known ISO 3166-1 alpha-2 residence code and optional `en`
interface preference. Null language follows the existing English interface, which
has no localization framework yet. The editor shows English as the only available language, without nonfunctional choices; editing name/country preserves the stored null/`en` preference. Country names use the device locale; the selector
searches real country names/codes without geolocation. Country is required to save
a completed profile, but **Set up later** persists a deferred setup and never blocks
local reading. The nonblocking Home setup prompt appears only until completion or
deferral; Settings → Profile remains editable. No email, password, account, backend,
network request or legal eligibility claim is introduced. Residence is user-declared;
future jurisdiction rules must be enforced by discovery/acquisition, not this UI.
No existing residence preference was found to migrate.

## Appearance and lifecycle

Profile and appearance share the existing application-owned preference worker and
private `application-appearance-v1/appearance.preferences` file. There is no second
settings system, profile table or collection/progress schema change. Legacy **40-byte
INF1** appearance records load unchanged with an empty profile; the first profile
edit atomically writes **INF2, at most 512 bytes**, including the existing appearance.
The name is optional, setup deferral survives restart, and changing appearance keeps
the profile. Reads validate length, checksum, fields and EOF; symlinks/invalid records
fail safely. Private permissions, atomic replacement and temporary-file cleanup are
retained. Independent profile/appearance edits during initial load preserve the
other stored field. One conflated pending record, retry feedback and close/drain
avoid competing writers. Profile editing waits for preferences to load. Upgrades
leave Library, History, reader preferences and semantic reading positions untouched.


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

Final refinement verification: **1,294 Desktop app tests / 1,120 Android-host
app tests**, zero failures/errors/skips. Android `assembleDebug` and Desktop
`compileKotlin` passed with JDK 21 and the existing Gradle configuration. Focused
metadata/profile/import/UI verification: **77 Desktop tests**, all green. This
refinement adds **23 tests**: 15 common/JVM-shared and 8 Desktop, in four files.
They cover repeated/absent/escaped/Unicode/long/malformed descriptions, CRC and
XML/archive rejection, cache limits/cancellation/retry/close, immutable old-import
records across restart, contextual saved-position resume, synopsis expansion,
small/wide details and legal-notice scrolling, keyboard import, language-preference
preservation, long/missing metadata and original previews. Existing Library/History,
profile migration/persistence, system appearance, EPUB/CBZ/PDF/TEXT, import, reader
continuity, progress and acquisition/security assertions remain intact.

The production/test/build fingerprint matches the verified source; `git diff
--check` and signing-secret/generated-artifact scans pass. Core, reader engines,
Gradle/dependencies, schemas, permissions, signing and release configuration are
unchanged. Tests use the installed Skiko library and an external writable cache;
no temporary configuration/workflow, books or generated artifacts are committed.

USER-REPORTED PHYSICAL ANDROID, before this refinement: the user confirmed Home/Library/History/Settings navigation, local profile/greeting, real EPUB covers, Library/History persistence after closing and clearing Android cache, publication details and compact History. These are reported observations, not acceptance of the new synopsis/details changes. Physical Android re-acceptance and native Desktop graphical acceptance remain pending. English remains the
existing UI language; no new localization infrastructure is introduced. Organizational read/unread flags, PDF/remote covers, undeclared EPUB/SVG cover heuristics, general cross-restart Library/History progress enumeration and discovery redesign
remain outside this PR. Home has the bounded restart lookup described above.

## Manual Android acceptance

1. Start on Home. Visit all five icon destinations; use Back and return from each reader to its originating screen.
2. Import TEXT/EPUB/CBZ/PDF; cancel the picker/import and try a rejected file.
3. Open details, inspect known metadata/rights, dismiss with Back, then open a book.
4. Tap a Library cover for details, then Start reading/Continue reading. Cancel and confirm its Library removal; files, History and saved position remain.
5. Long press a cover, toggle more covers, Select all and Clear. Exit with Back/Escape without losing scroll; cancel then confirm a batch and inspect retry feedback if storage fails.
6. In History, tap a cover for details and the title/remaining row to resume. Check Today/Yesterday/older dates and actual page/progress summaries. Cancel/confirm the trailing trash action and Clear History; Library, files and saved position remain.
7. Choose System/Light/Dark, restart, and check bars with gesture/three-button navigation.
8. Open an EPUB with a different reader appearance, then exit; the application theme returns.
9. Read at a noninitial position, return and reopen; position/preferences remain intact.
10. Rotate/resize and increase system font size; actions and lists remain reachable.
11. Check EPUB2/EPUB3 declared covers and CBZ first-page artwork; TEXT/PDF/missing/rejected artwork uses readable typographic covers.
12. Scroll a larger Library, switch History/Library, rotate/resize and resume; no position resets or layout jumps.
13. On Desktop, resize across 840 dp, use Alt+1..5, Tab/Enter, right-click and Menu/Shift+F10; dismiss menus/details with Escape.

14. On first launch/upgrade, open profile setup, skip the name, search/select a country, save and restart. Confirm no account/network requirement. Alternatively defer setup, restart, and verify local reading remains available without repeated onboarding.
15. Edit name/country through Settings → Profile; check personalized/generic greetings and appearance persistence. Cancel/Back/Escape must discard unsaved edits.
16. On Home, resume a saved deep passage, return and reopen after restart. Recently added covers must open details; View History/Library reach their destinations. Empty Home must show import guidance without empty carousels.

Headless previews use only original synthetic artwork and sample profile data. They
are generated outside the repository; no reference screenshots, books, thumbnails,
APK, signing material or preview artifacts are committed. They demonstrate shared
Compose layouts, not physical Android system bars, TalkBack/IME or native Desktop
window acceptance. Those checks remain pending.

### Final Android polish checklist

1. Upgrade/restart with the existing profile, Library, History and deep reading positions intact; repeat the reported Android cache-clear check (not Clear storage).
2. Open an old imported EPUB with description (Analects if available) and one without: the latter has no Synopsis heading. No new import or identity is needed.
3. Expand/collapse a long synthetic synopsis; on a small screen/larger font scroll to source rights and the legal notice. Close/Back remains reachable. Test light/dark and Desktop resize/Escape/Tab/Enter.
4. Check Start reading for a new publication and Continue reading for a saved one after restart; details alone must not reset progress. Cancel/confirm Library removal without deleting originals or saved positions.
5. Check compact Home greeting, long two-line carousel titles, cover fit, progress and horizontal scrolling; import icon accessibility/keyboard/tooltip. Profile exposes only the implemented English language and preserves residence/name.
6. Resume EPUB/CBZ/PDF/TEXT and return; verify reader settings, semantic position and Android system bars remain correct.
