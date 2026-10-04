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
reports the top visible line, or EOF at the bottom. Throttled 2s saves/Back flush
and fixed save-failure diagnostics remain unchanged. See [PROGRESS.md](PROGRESS.md).

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
are serialized briefly around at-most-8-KiB local IO; there is no source mutex held
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

## Additional source evidence in PR #11

Gutenberg TEXT uses this exact preparation/window API, without another reader,
charset detector or size change. `text-utf8` is its stable logical progress key.
The reviewer reports physical PR #10 Android large-TEXT opens, bidirectional
scrolling and process-restart progress success; that is human-reported evidence,
not a Codex device test. Gutenberg host/live evidence and pending device plan
are in [GUTENBERG.md](GUTENBERG.md) and [VERIFICATION.md](VERIFICATION.md).
