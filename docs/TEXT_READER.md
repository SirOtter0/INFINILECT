# Bounded indexed TEXT reading

PR #10 replaces the first reader's 512 KiB whole-document materialization with
one strict, incremental preparation pass and a private, session-local UTF-8 file.
This is plain TEXT infrastructure, not an EPUB/PDF engine or a Download feature.

```text
PublicationSource → ResourceLoader → ResourceContent
                                      │ sequential bounded reads
                                      ▼
                           FileTextPreparer / sparse index
                                      │
                                      ▼
                         TextDocument.window(index)
                                      │ suspend, local IO
                                      ▼
                         lazy shared Compose TextReader
                                      │ logical code-point offset
                                      ▼
                        unchanged ReadingProgressStore
```

Core, source adapters, revision-aware ResourceCache, Library/History database and
progress persistence formats are unchanged. Saved metadata still re-resolves its
PublicationId through its owning source before opening. Internet Archive remains
public CC0-only with fresh metadata, item-scoped redirects and revision=null;
opening/reopening always retains its acquisition checks and cache bypass.

## Size and memory policy

- **TEXT preparation maximum: 16,777,216 bytes (16 MiB), inclusive.** Require known,
  positive, stable size and exact EOF; probe at most one excess byte. This offers
  32 times the former byte limit while bounding local disk, full validation/index
  CPU and Android preparation costs. It is deliberately below IA's unchanged
  **67,108,864-byte (64 MiB)** source acquisition ceiling. Source limits and reader
  working memory are distinct; neither limit is unlimited.
- Acquisition requests are at most **8,192 bytes**, with an **8,196-byte** input
  buffer (up to three carried UTF-8 bytes plus room) and **8,192 UTF-16 units** in
  the decoder output buffer. No whole-resource ByteArray or String is allocated.
- Each display window contains at most **2,048 Unicode code points**: at most
  **8,192 UTF-8 bytes** and **4,096 UTF-16 units**. End at a newline after 1,024
  points when possible; otherwise cut at 2,048 even in a huge unbroken line.
  No code point/surrogate pair is split. This may introduce a visual line break
  inside a very long paragraph/word; exact wrapping is not promised.
- FileWindows caches at most **eight** decoded windows with approximate LRU access
  ordering: at most **32,768 UTF-16 units** (64 KiB character payload), plus bounded
  sparse window-location arrays and objects. Compose retains visible/lazy layout
  windows as needed, rather than one Text containing the entire publication.
- Two primitive IntArray indexes store byte and code-point starts. Grow by doubling
  capped at **16,385 entries**, determined by the 16 MiB bound / 1,024 points.
  Retained index payload is at most **131,080 bytes**; growth temporarily retains
  the previous arrays too (at most roughly 192 KiB total index payload). There is
  no index entry/object per character. Preparation remains O(bytes); logical
  offset-to-window lookup is O(log windows), with bounded window-local scans.

The complete resource is acquired/validated/indexed before Ready. Large opens have
latency and temporary disk cost; progressive reading before validation completes
is not implemented. Existing 60s opening/source deadlines are retained, so a slow
transfer can still time out below the size cap. Scrolls use local indexed access
and never refetch the source. Filesystem work uses the IO dispatcher in production;
tests inject a deterministic dispatcher.

## UTF-8 and progress

The incremental decoder reports malformed input, overlong encodings, surrogate
encodings and truncated sequences. It carries incomplete sequences between source
reads. Remove exactly one initial U+FEFF from logical text; keep interior U+FEFF.
BOM-only, empty, all-whitespace and NUL-bearing resources remain unreadable.

Window indexes count global Unicode code points after BOM removal, including line
breaks. Supplementary characters count once. Current layout indices within a
window are converted from UTF-16 safely, rounding surrogate midpoints down.
Persisted `ReadingLocator.Text(codePointOffset, documentCodePoints)` and
`ReadingProgressId(PublicationId, resourceKey, TEXT)` are unchanged: **no migration**.
Restore the same count's offset exactly in logical space; changed lengths retain
PR #7's proportional approximation. Locate the indexed window and restore to the
containing line in the current layout. Viewport pixels never become durable state.
Restoration does not write a new rounded location; subsequent actual scrolling
reports the top visible line, or EOF only at a bottom with the actual final window
laid out. Throttled 2s saves/Back flush
and fixed save-failure diagnostics remain unchanged. See [PROGRESS.md](PROGRESS.md).

## Stable viewport and bidirectional loading — PR #14

Physical Android testing reported much faster backward than forward scrolling.
The PR #10 lazy reader kept a complete, stable list of window indices; there was
**no prepend, append, reindexing or custom fling velocity**. The problem was its
geometry: each newly composed item started as one line of “Loading text…” while
`produceState` dispatched `document.window(index)` to IO. This happened even with
a warm FileWindows cache. An evicted earlier multi-line window therefore briefly
became one line. Reverse measurement could cross many earlier windows in one
pixel delta before their real heights returned.

The official [Compose foundation 1.12.1 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.12.1/foundation-desktop-1.12.1-sources.jar),
`commonMain/androidx/compose/foundation/lazy/LazyListMeasure.kt`, add each previous
item's **currently measured** main-axis size while resolving a negative scroll
offset. A deterministic regression uses previously measured 1,000px windows:
200px backward from item 10 should land at item 9/800px; the old 20px placeholders
instead reach item 0. Those numbers are a host simulation, **not device timing or
measured Android typography**. This establishes the code-level defect; confirming
gesture symmetry on the reported device remains necessary.

The shared reader now has these invariants:

- Keep every indexed window in the lazy list; key it by its global start code point
  inside a document-generation `key`. Loading never changes another item's identity.
- Retain the measured height of each visited window for the current width/font/
  density. Reloading a cold slot reserves that same height instead of collapsing.
  Heights are UI-only, never progress records. A layout change gets new geometry.
  An unvisited pending slot reserves one viewport as an explicit estimate until
  measured; it is not a promise of exact unseen content geometry.
- `TextWindowLoader` has one UI-owned worker and one conflated wakeup. Prioritize
  composed windows near the first visible item, then prefetch **two windows on
  each side** of that composed range. No per-item IO jobs or network requests.
- Cache at most **eight** decoded windows in the UI coordinator, keeping useful
  neighbors immediately available at attachment. Composed items retain their own
  ready window until disposal, even if the eight-window cache evicts it. The
  existing eight-window FileWindows LRU is unchanged. These caches commonly share
  objects; their conservative combined character-payload bound is **128 KiB**,
  plus viewport/beyond-bounds layout windows (<=4,096 UTF-16 units each). Disposed
  windows are released, not accumulated across the publication. A primitive height
  array adds at most **65,536 bytes** (16,384 Ints); no full-book decoded cache.
- Requests carry a range generation. Changed ranges, close, cancellation or a
  replaced document cannot apply a late result/failure to the new reader. A
  non-cooperative local read may finish its bounded IO; it cannot publish afterward.
- Only initial logical restoration uses `scrollToItem`, once the initial window
  has a real layout. User scrolling starts afterward. Progress reports never drive
  programmatic scrolling. A pending visible window reports nothing; EOF needs an
  actually laid-out final window and the scrollable bottom, not placeholder geometry.

There is no change to fling physics, acquisition/index rebuilding, 2,048-point
windows, 16 MiB ceiling or persisted progress schema. Per-change work uses only
composed slots and four neighbors, independent of total document size. Ordinary
neighbor hits avoid loading labels; extreme flings, unseen regions or slow local
storage can still display a correctly sized loading slot. Combining sequences or
CRLF may straddle a window cut; reconstruction preserves all code points, although
visual grapheme/paragraph continuity across that artificial cut is not guaranteed.

Offline tests cover collapsed-height reproduction, forward/backward anchor
arithmetic, 500 alternating transitions, eviction/reload, stable keys, 1,000
coalesced direction changes, late non-cooperative reads/failures, close/cancellation,
false EOF, complete owner/persistent-store restart, mixed Unicode, combining/CRLF
cuts and exact document/window boundaries. They test the model and measurement
seam, **not Compose gesture/rendering or Android filesystem/device behavior**.

## Presentation continuity (PR #24)

One TextReadingProgress owns the live Unicode code-point position even when durable
storage is unavailable. Width, density/font-scale or typography changes retire the
old list/layout jobs and restore the containing line from that live position after
measurement. A new presentation does the same; old pixel offsets cannot drive
reflow. Presentation restoration alone does not report a new reading position.
The application session/document survives Android configuration recreation. Existing
window/resource bounds and code-point progress schema are unchanged; restoration
is to a containing line, not an exact pixel or grapheme boundary. Without durable
storage, session continuity works but exit/reopen cannot restore prior progress.

## Private temporary storage and lifecycle

Android extracts `applicationContext.cacheDir/reader-text-v1`. No Context is retained,
no storage permission is added. Desktop follows private per-user cache conventions:

- Linux: absolute `$XDG_CACHE_HOME/org.infinilect.app/reader-text-v1`, otherwise
  `$HOME/.cache/org.infinilect.app/reader-text-v1`.
- macOS: `$HOME/Library/Caches/org.infinilect.app/reader-text-v1`.
- Windows: absolute `%LOCALAPPDATA%/org.infinilect.app/reader-text-v1`, otherwise
  `$HOME/AppData/Local/org.infinilect.app/reader-text-v1`.

Relative/unusable locations fail with a fixed preparation-storage error, never a
cwd/repository fallback. The OS-provided private base may have legitimate aliases
(e.g. Android /data/user/0); canonicalize it on IO, rejecting a symlink at the
cache-owned namespace. Restrict that namespace to owner-only access where the
filesystem exposes POSIX attributes (query the view directly, never getFileStore);
Windows uses the per-user directory's inherited ACL. Session directories and files use random UUID names, never
publication IDs, resource keys, titles or arbitrary source URLs.

One FileTextPreparer is application-owned; one open document owns its indexed
backing. A separate owner lock distinguishes live session directories from stale
process leftovers. Scan at most 128 directory entries on owner initialization;
only reserved session/payload/lock names under an unlocked owned directory are
cleaned, without following symlinks. Unrelated files and ambiguous/locked remnants
are left untouched. Cleanup is best effort (Windows may retain a tiny stale lock
file while it is held); no stale data is ever reopened as a prepared document.

ResourceContent always closes after preparation, including failure/cancellation.
Only a fully validated file/index can become Ready. Cancellation is checked around
every source read and decoder buffer; no silent truncation. Dispatch handoff or
later progress-lookup cancellation also closes a completed unclaimed document.
Back, replacement and session/application close flush progress and close the
reader handle idempotently. close marks handles unavailable synchronously and
schedules private-file removal off the UI thread. The application drain waits for
preparer cleanup as well as persistent metadata writers. Concurrent window reads
use a cancellable Mutex around at-most-8-KiB local IO; the short cache/close guard
never holds filesystem IO, and cancellation/closed state is checked before publishing.
Close clears decoded windows without waiting for an IO operation; there is no
source mutex held
while scrolling. Normal UI ownership has one reader; no persistent document registry.

OS cache deletion, IO failures or missing/truncated or invalid UTF-8 backing files produce fixed
storage errors; a fresh open can prepare again. Prepared files are disposable,
**not ResourceCache entries, Downloads, or authority to acquire content**.
Clearing cache removes neither filesDir ReadingProgress nor Library/History DB.
No contents go into those stores or logs. No new network behavior, schema,
permission, dependency or telemetry is introduced.

## Verification and physical plan

See [VERIFICATION.md](VERIFICATION.md) for actual commands/results and the real
CC0 large-TEXT check. Host tests are not Android OS/device or graphical UI tests.

A. Install the debug APK over PR #9 without clearing data; verify existing
   Library, History and ReadingProgress remain.
B. Open a previously working small TEXT; reading/Back remain normal.
C. Search Internet Archive for `identifier:stcrt-2015-37219`; open its compatible
   TEXT (589,899 bytes, above the former 524,288-byte ceiling), if still permitted
   by current source metadata. No permission/CC0 policy override is allowed.
D. Scroll substantially forward through the large publication.
E. Scroll backward to an earlier section.
F. Wait at least 3s, Back, reopen and verify approximate logical progress restore.
G. Completely terminate/relaunch; search/open the same item and verify restore.
H. Library and History work normally for the large publication (metadata only).
I. Clear **Android cache only**, not app data. Library/History/ReadingProgress remain;
   reopening freshly acquires/rebuilds temporary reader data.
J. Confirm no storage/progress/library/history errors in normal operation.

The current UI is foundational. This PR does not declare v0.0.1 complete.

## Gutenberg correction in PR #11

The TEXT reader/preparation/progress implementation is unchanged. Gutenberg's new
OPDS2 development catalog is experimental and does not enable reading. The prior
unmerged RDF/direct TEXT path has been removed pending a verified current contract.
IA remains the real TEXT source. Human-reported PR10 physical large-TEXT success
is not a Codex device test. [Corrected source evidence](GUTENBERG.md).
