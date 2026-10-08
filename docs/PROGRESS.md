# Persistent reading progress

Reading progress is local **user state**, separate from the automatic resource
cache. Deleting cache, OS cache eviction or changing a byte revision cannot delete
a saved position. Progress contains no publication contents, search results,
title/author metadata, URLs, credentials or network policy. Saving never requests
a source or sends data to another service. No history/library/bookmark/sync API.

```text
PublicationSource / ResourceLoader → publication bytes → Reader
                                                        │
                                                 logical locator
                                                        │
                                                        ▼
                                               ReadingProgressStore

Resource cache ≠ Reading progress
```

## Domain and identity

Pure core defines ReadingProgressId(PublicationId, resourceKey, format),
ReadingLocator and ReadingProgress(id, locator, progression, updatedAtEpochMillis).
PublicationId is the existing SourceId + source-local ID pair. Resource key and
format distinguish representations/chapters; changing either starts without that
other resource's position. Title, authors, URL, MIME and revision are **not** user
identity. Source identifiers/resource keys must remain stable to preserve positions.

Revision changes do not erase user state. Current Internet Archive revision=null
resources can retain progress, but still bypass disk-cache lookup/fill and perform
fresh getPublication/acquisition permission/location/size checks on every open.
Progress restoration happens **after** full successful bounded acquisition/decoding.
It cannot authorize content, bypass permissions, or enable offline reading.

ReadingLocator.Text(codePointOffset, documentCodePoints) remains the compatible TEXT
variant. PR #15 adds ReadingLocator.Epub(spinePath, elementPath, codePointOffset,
chapterProgression): canonical ZIP spine identity, bounded element-child ordinals
(rooted at body with a synthetic ordinal0), and Unicode position within the semantic
block. No pixels/lazy indices or engine/platform object. PAGE now uses the separate typed locator below; PDF remains future work. [Exact EPUB semantics/security/development status](EPUB_READER.md). Progression must be finite in [0,1]; timestamps are
nonnegative and older saves cannot replace newer committed state.

## TEXT location and approximation

Offsets count Unicode code points in decoded text **after** stripping one leading
UTF-8 BOM. PR #10 prepares a disk-backed indexed document instead of a whole String.
The persisted identity/locator/format is unchanged: **no migration**. A global
sparse byte/code-point index locates a bounded display window; its small UTF-16
index checkpoints every 256 points. A surrogate pair counts once and a layout
index inside it rounds down. No whole-document rescan/string is needed to restore.
Strict UTF-8/BOM/exact EOF semantics remain, with a separate 16 MiB preparation cap;
see [TEXT_READER.md](TEXT_READER.md). No other encoding or normalization is inferred.

0 is the beginning; offset == length is EOF; empty text maps to 0/0 progression
(the loader still rejects empty reading documents). Negative/invalid persisted
locators are rejected. Transient layout inputs clamp to document bounds. If the
new document has the same code-point length, restore the logical offset. If its
length changed, restore the saved normalized proportion in the new length.
This is an approximation: same-length edits can move passages, and no checksum or
fabricated source revision is inferred. A resource-key change does not transfer it.

Compose selects the indexed window, then translates its local offset to the
containing line's top using that window's **current** TextLayoutResult/lazy viewport. Reports use the top visible
line's text offset. Pixels exist only during layout and are never persisted.
Viewport/density/wrapping changes can alter the exact line; no exact pixel promise.
Restoration itself does not overwrite a stored position with its rounded line.
PR #14 preserves visited slot heights through eviction/reload and uses stable
window-start keys within one document generation. Loading earlier/later windows
is not a progress event. Only real visible layouts report a line offset; pending
slots preserve the last meaningful position. Initial restoration scrolls once,
with scrolling enabled afterward; subsequent reports never cause scrollToItem.
At the scrollable bottom with the actual final window laid out report EOF (100%);
there is no separate completion flag,
reading-history or elapsed-reading policy. The reader shows whole percentages.

## Persistence format and bounds

App/jvmSharedMain uses FileReadingProgressStore with standard JDK/Android NIO and
SHA-256. Schema v1: magic INFPROGR, version, bounded body length, length-prefixed
UTF-8 identity fields, typed TEXT tag, offset/observed length, progression,
timestamp, and SHA-256 of the body. TEXT stays byte-for-byte v1. EPUB uses v2/tag2,
length-prefixed canonical spine path, 1–32 validated child ordinals, code-point
offset and chapter fallback; readers accept only matching schema/tag/format. No
migration, deletion or SQL schema change. The digest of the structured identity yields
`p-<64 hex>.progress`; untrusted identifiers never become paths or delimiter joins.

- Maximum encoded identity: 8 KiB; record: 16 KiB.
- Default committed quota: 1024 records and 16 MiB, configurable in offline tests.
- A directory scan is bounded to 4096 entries; excess disables that write safely.
- Quota **refuses new writes**, it never evicts a user's saved position. Updates to
  existing records may continue if within the byte budget; no automatic LRU/expiry.
- One small temp record can transiently add at most 16 KiB; owned crash remnants
  are removed under the operation lock before a later save. Unrelated files remain.

Reads require no-follow regular files, exact header/length/version, strict UTF-8,
valid typed values/identity and checksum. Missing/corrupt/truncated/oversized/future
schema records are absent progress, never an exception exposed to reading. Corrupt
records can be replaced on the next save; they count toward quota until replaced
or explicitly removed. Unsafe/unavailable storage returns null/false.

Write one private temp file, flush its descriptor, check cancellation, and use
same-directory ATOMIC_MOVE + replacement. If atomic replacement is unavailable or
fails, **keep the previous committed record** and report save failure. No destructive
rename fallback. Atomic rename is not a guarantee against every power-loss/device
filesystem failure. Only reserved progress/temp names are managed; root/record/lock
symlinks fail closed and no outside target is read or modified. Removing an owned
symlink name during temp cleanup removes only that link, never its target. POSIX owner-only
permissions are applied through a no-follow **path** PosixFileAttributeView; Windows
inherits the user's directory ACLs when that view is absent. No FileStore probe.

### Android compatibility correction — 2026-10-03

The first PR #7 APK failed durable saves on a physical Android device even though
same-process restoration worked through ProgressPersistence's `recent` RAM map.
The original permission helper called Files.getFileStore(path). Android's official
[API 26 LinuxFileSystemProvider](https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/sun/nio/fs/LinuxFileSystemProvider.java)
and [current provider](https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/sun/nio/fs/LinuxFileSystemProvider.java)
explicitly throw SecurityException("getFileStore") because filesystem-wide information
is unavailable under Android SELinux policy. This occurred at directory permissions,
before the lock or any record was created; operation() reduced the exception to false.
Host JVM tests used the desktop provider, which allows that query.

The corrected helper obtains the path's PosixFileAttributeView with NOFOLLOW_LINKS
and sets the same owner-only permissions directly. Android's official
[UnixFileSystemProvider](https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/sun/nio/fs/UnixFileSystemProvider.java)
supports this view and [UnixFileAttributeViews](https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/sun/nio/fs/UnixFileAttributeViews.java)
uses fchmod for no-follow access. Permissions are not silently ignored on POSIX.
FileChannel locking/no-follow access/descriptor force remain unchanged. Same-directory
ATOMIC_MOVE remains required: official Android
[UnixCopyFile](https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/sun/nio/fs/UnixCopyFile.java)
implements it with rename, with no copy/delete fallback in the atomic branch.
A failed commit preserves the previous file; temporary cleanup cannot mask the
original diagnosed failure or report an already committed record as failed.
These guarantees cover normal process restart and interrupted precommit writes,
not every power-loss/storage-device failure. No destructive fallback was added.

Internal diagnostics identify operation/stage/reason (directory creation/permissions,
lock, encoding/read/decode, quota, temp open/write/sync, commit and cleanup). They
contain no paths, publication IDs, content or exception messages. Android debug
builds log these enum values under `INFINILECTProgress`; release builds do not log
them. A typical original failure is `SAVE/DIRECTORY_PERMISSIONS/SECURITY`. The UI's
save-failure warning is retained and clears only after a later successful store save.
No telemetry or persistence-triggered network access is added.

Regression tests include an Android-like host filesystem provider that rejects
getFileStore but permits real path attributes, locking and atomic writes; it is
not an Android device. They verify final .progress files and restoration through
new store **and** writer instances, discarding all `recent` state. RAM restoration
after failed save is explicitly tested as non-durable. Repeated platform-owner
recreation and cache-only deletion are covered. Physical retesting of the corrected
APK remains required; see [verification](VERIFICATION.md).

## Storage and concurrency

- Android: `applicationContext.filesDir/reading-progress-v1`, persistent internal
  app-private storage. This is **not cacheDir**. Only the Path is retained; no
  Activity/Context, external/shared storage or storage permission. App uninstall
  or explicit app-data removal still deletes it; manifest backup remains disabled.
- Linux: absolute `$XDG_DATA_HOME/org.infinilect.app/reading-progress-v1`, otherwise
  `$HOME/.local/share/org.infinilect.app/reading-progress-v1`.
- macOS: `$HOME/Library/Application Support/org.infinilect.app/reading-progress-v1`.
- Windows: absolute `%LOCALAPPDATA%/org.infinilect.app/reading-progress-v1`, otherwise
  `$HOME/AppData/Local/org.infinilect.app/reading-progress-v1`.

Relative environment/home paths never become cwd/repository storage; missing/invalid
safe paths disable persistence and reading continues with a save-failure indication.
The resource cache uses its existing separate cache namespace/location unchanged.

Every file operation runs on Dispatchers.IO. A small process-wide monitor and a
short exclusive OS file lock serialize record operations across recreated owners;
no lifetime lock/open descriptors. Another process holding the lock causes a
best-effort failed operation, not waiting on the UI. Older timestamp writes are
ignored; conflicting equal timestamps fail rather than silently overwrite.
A process-wide monotonic timestamp generator orders updates across recreated owners;
a restored timestamp is also respected. Cross-device synchronization is out of scope.

## Save and lifecycle policy

One TextReadingProgress belongs to one Ready generation. On movement it updates
in-memory logical state and schedules a **fixed 2-second window**: later movements
coalesce without extending the deadline. Thus continuous scrolling still saves;
there is no disk write per pixel. Unchanged positions and simply opening/restoring
cause no save. Back/open another/source switch closes that generation, cancels its
timer and captures its latest pending record. Late callbacks are ignored.

ApplicationSources owns ProgressPersistence independently of Compose's cancelled
session scope. It coalesces a bounded queue of 32 identities, retains at most 64
recent submitted positions for immediate reopen, and serially saves on a background
scope. Each store operation has a 5-second coroutine timeout; synchronous filesystem
calls cannot be forcibly interrupted on every OS. A failed/quota/timeout write
shows a fixed safe message in the reader and does not block reading. Failures are
not silently claimed durable; recent in-memory restoration is not proof of disk save.

Closing cancels source/session work first, submits final progress, closes the write
queue for draining, then releases existing cache/source clients. Idempotent close
never blocks the Android main thread. Background draining retains records/path only,
no Activity or live reader. Android onStop flushes pending state; final ViewModel
clearing closes sources. Configuration recreation retains the session/document;
process death restores durable progress after a new valid open. Desktop window close awaits draining up
to 3 seconds before exit. Abrupt process death may lose the most recent window or a
pending write; no final callback guarantee. Periodic saves reduce that loss.

No new dependencies or persistence/network permissions. Host tests verify algorithms
and real temporary-file restart/corruption behavior; actual Compose layout, Android
filesDir/filesystem/process lifecycle and device restart smoke remain manual checks.
See [verification](VERIFICATION.md) and [ADR 0015](adr/0015-persistent-reading-progress.md).

## EPUB approximation and lifecycle (Draft PR #15)

Same spine/element structure restores the block and containing line using current
layout. Offsets clamp to block bounds; changed element structure falls back by
chapter progression. Removed spine paths start at chapter1. Whole-book progression
is chapter-weighted, (spine ordinal + chapter fraction)/spine count. No exact pixel,
page or word-count claim. Unicode surrogate pairs count once. Initial restore does
not save its rounded line; real scrolling/internal navigation report semantic
positions. One fixed 2s throttle saves during continuous reading and Back/application
close flush pending state. Closed readers/old navigation tickets cannot submit late
updates. Full restart tests discard all ProgressPersistence recent RAM, reopen the
actual checksummed files, rebuild prepared EPUB and restore. Cache-only deletion
never removes this user state. Production EPUB acquisition remains disabled.

## EPUB presentation changes (Draft PR #16)

Locator identity and binary schemas are unchanged. Session settings/width/font-scale
changes restore the latest code-point/element-path locator under a new layout ticket;
late callbacks from the previous layout are ignored. Initial/loading image presentation
cannot report saved positions. Ready text reports semantic line starts only after user
scrolling. Image references retain the historical bounded alt-placeholder code-point
semantics and cumulative parent offset, so splitting inline images into display blocks
does not itself invalidate an existing paragraph locator. A whole image restores to
its semantic block, not an internal pixel. List numbering never enters locator text.
The expanded development fixture appends its showcase after existing passages, keeping
historical passage element ordinals; added content may change approximate percentages.
The initial PR #16 session-reset policy was superseded after physical acceptance.
Font/spacing/margin/theme now persist globally through a separate
EpubReaderSettingsStore, never through ReadingProgressStore. They survive cache
deletion/restart without changing locator identity or the TEXT/EPUB binary progress
schemas. [Preference policy](EPUB_READER.md#durable-global-epub-preferences-pr-16-physical-test-follow-up).

## Comic page locator (Draft PR #17)

ReadingLocator.Page(pageKey, pageIndex, pageProgression) uses a stable declared page
resource key, logical ordered-document index fallback and finite intra-page fraction
[0,1]. Index range is 0–99999; key is nonblank and ≤512 UTF-16 units. The current
adapter caps actual sequences at 512 and identifies user state by structured
(PublicationId, "page-sequence", PAGES). No revision, pixel, URL or LazyListState is
persisted. Stable keys win on reorder; removed keys fall back to a clamped ordinal.

Paged navigation records page start; vertical/webtoon approximate the fraction within
the first visible page. Initial restoration never overwrites it. Mode/viewport changes
retire old tickets and retain semantic page/fraction; closed readers reject callbacks.
The existing fixed 2s throttle and Back/close flush use ProgressPersistence. Cache
removal, normal restart and mode preference changes do not invalidate progress.

PAGE records use schema3/tag3; **TEXT v1 and EPUB v2 encoding remains byte-for-byte
unchanged**, independently tested against their historical serializers. No SQL
migration or schema change to Library/History. Corrupt/future/malformed locators remain
absent progress. Global page reading mode persists in its own preferences record,
not ReadingProgressStore. [Page policy/restoration limits](PAGE_READER.md).

Draft PR #18 CBZ uses this same PAGE progress contract. Its prepared page keys are
ordinal and path-independent, so progress persists no archive path, ZIP metadata or
transport locator. Re-preparing a CBZ maps the same natural page order back to the
existing synthetic keys; no progress schema migration is introduced. Closing/clearing
temporary CBZ cache storage leaves ReadingProgress untouched.

Local import (PR #19) preserves all existing TEXT/EPUB/PAGE records and settings.
Source `local-imports` + the committed byte digest supplies a stable publication ID
and resource key `content`; identical bytes resolve to the same progress identity
regardless of external filename or location. Reader/session recreation, original
file deletion and cache cleanup retain the owned publication and semantic progress.
Library row removal/History clearing do not delete imports or progress. No progress
serialization or SQL schema migration is introduced. [Ownership](LOCAL_IMPORT.md).

## Presentation continuity (PR #24)

An active reading session outlives its presentation. ApplicationSources owns one
ApplicationSession and UI coroutine scope; Android's ViewModel retains only
application-context sources across configuration changes. The Activity, picker and
Compose UI are recreated. Desktop retains the same owner until window close.
Desktop supplies the standard coroutines Swing Main dispatcher (the same 1.11.0
version as existing coroutines); the session never retains a scene dispatcher.

| State | Owner / lifetime | Presentation change |
| --- | --- | --- |
| Publication identity / owned document | Active session | Retained; no reopen or source reacquisition |
| Semantic position | Reader controller / TextReadingProgress | Live state wins over older persisted progress |
| Visual spread / line layout | Current presentation | Reconstructed from semantic position |
| Gesture, zoom, transition tickets | Presentation/controller generation | May reset; obsolete callbacks cannot commit |
| Durable locator | Existing progress writer/store | Loaded on a new open; valid progress flushes on exit |

CBZ keeps its exact session page/fraction separately from the visual Double anchor.
Switching back restores that exact page unless real navigation replaced it; a
Double spread still persists only its validated logical anchor. EPUB restores the
latest semantic locator on remount/reflow. TEXT restores the line containing its
Unicode code-point offset after reflow. PDF keeps its page and existing fit-only
render; it has no intra-page pan position. Identity and persistence schemas do not
change, and preparation/resource bounds remain unchanged.

Confirmed regressions before the fix: Double normalization discarded the second
page/fraction; App remount closed the reader; EPUB remount restored chapter entry
instead of its newer locator; TEXT width reflow retained pixels and changed the
visible code-point region. Stable Single-mode horizontal/vertical changes did not
reset to page zero in the controlled reproduction. The user's Android symptom is
evidence to investigate, not proof that every reader/mode shares one root cause.

Final platform-owner close flushes legitimate pending progress before cancelling
owned work and draining writers. Replacing UI flushes without closing the session.
A requested/decoded/animated target remains insufficient for comic progress; the
existing complete-presentation validation and stale-ticket rejection still apply.
Full process death is distinct from configuration recreation: durable progress is
restored on explicit reopening (local PDF retains its existing saved-state reopen
path). Abrupt death can lose uncommitted writes. No automatic non-PDF process-death
reopen, new storage schema, global session, or platform rendering engine is added.

Physical Android and native Desktop graphical acceptance of PR #24 are pending.
Headless layout tests do not establish Activity/device lifecycle correctness.
