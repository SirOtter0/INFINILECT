# Application interface

The application uses shared Compose Material components on Android and Desktop.
This presentation layer does not own publications, acquire resources or save
reader positions. `ApplicationSession` remains the navigation/session owner.

## Screen audit and changes

| Existing surface | Before | Current presentation |
| --- | --- | --- |
| Application root | Import button and three equal action buttons above every destination | Quiet app header; Library, History, Search and Settings in one adaptive navigation hierarchy |
| Search/source browsing | Large branding, source buttons, plain result rows | Restrained heading, existing source choices and query semantics, shared publication cards and bounded scrollable feedback/results |
| Library | Plain rows and separators | Adaptive publication grid, metadata placeholders, author/source hierarchy, Open/Remove/Details, helpful empty/storage-error states |
| History | Plain newest-first rows, TXT-only empty wording | Same cards/grid, all-reader empty wording, existing newest-first ordering and clear-history confirmation |
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

The publication model currently exposes no cover resource. Cards use a neutral
format-independent type mark instead of claiming a real cover. Metadata dialogs
omit absent authors, languages, rights and source URLs. A BOOK/DOCUMENT type does
not imply EPUB/PDF. If no actual resource format or known progress record exists,
format is explicitly checked when opening; no format is guessed from a title.

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

Final verification: 1,171 Desktop app tests and 1,043 Android-host app tests,
zero failures/errors/skips; Android and Desktop compilation passed with JDK 21.
Focused application verification passed 66 Desktop / 12 Android-host tests and
all four existing reader-continuity layout tests. Test execution uses the existing
Skiko library and a writable external cache in this managed headless environment;
no temporary test workflow/configuration is committed.

Native Android/desktop graphical acceptance is pending. English remains the
existing UI language; no new localization infrastructure is introduced. Actual
covers, cross-restart collection progress summaries and discovery redesign remain
outside this PR.

## Manual Android acceptance

1. Visit Library, History, Search and Settings; use Back and return from each reader.
2. Import TEXT/EPUB/CBZ/PDF; cancel the picker/import and try a rejected file.
3. Open details, inspect known metadata/rights, dismiss with Back, then open a book.
4. Save/remove Library entries; clear History and confirm Library/progress remain.
5. Choose System/Light/Dark, restart, and check bars with gesture/three-button navigation.
6. Open an EPUB with a different reader appearance, then exit; the application theme returns.
7. Read at a noninitial position, return and reopen; position/preferences remain intact.
8. Rotate/resize and increase system font size; actions and lists remain reachable.
9. On Desktop, resize across 840 dp, use Alt+1..4 and Tab/Enter, dismiss details with Escape.
