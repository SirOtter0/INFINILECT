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

ReadingLocator.Text(codePointOffset, documentCodePoints) is the first typed variant.
Add EPUB href/locator, PDF page or PAGES index variants when those readers exist;
no engine/Compose/platform dependency belongs in core. The file implementation
currently supports TEXT only. Progression must be finite in [0,1]; timestamps are
nonnegative and older saves cannot replace newer committed state.

## TEXT location and approximation

Offsets count Unicode code points in the decoded String **after** stripping one
leading UTF-8 BOM. A surrogate pair counts as one point; a UTF-16 layout index inside
it rounds down. The persisted offset never splits a pair or a UTF-8 sequence.
A sparse index checkpoints every 256 code points so scroll reports do not rescan
the whole document. The existing strict UTF-8/BOM/exact EOF and 512 KiB byte cap
are unchanged; this does not detect other encodings or change text normalization.

0 is the beginning; offset == length is EOF; empty text maps to 0/0 progression
(the loader still rejects empty reading documents). Negative/invalid persisted
locators are rejected. Transient layout inputs clamp to document bounds. If the
new document has the same code-point length, restore the logical offset. If its
length changed, restore the saved normalized proportion in the new length.
This is an approximation: same-length edits can move passages, and no checksum or
fabricated source revision is inferred. A resource-key change does not transfer it.

Compose translates the offset to the containing line's top using the **current**
TextLayoutResult, clamped to the current scroll range. Reports use the top visible
line's text offset. Pixels exist only during layout and are never persisted.
Viewport/density/wrapping changes can alter the exact line; no exact pixel promise.
Restoration itself does not overwrite a stored position with its rounded line.
At the scrollable bottom report EOF (100%); there is no separate completion flag,
reading-history or elapsed-reading policy. The reader shows whole percentages.

## Persistence format and bounds

App/jvmSharedMain uses FileReadingProgressStore with standard JDK/Android NIO and
SHA-256. Schema v1: magic INFPROGR, version, bounded body length, length-prefixed
UTF-8 identity fields, typed TEXT tag, offset/observed length, progression,
timestamp, and SHA-256 of the body. The digest of the structured identity yields
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
permissions; Windows inherits the user's directory ACLs.

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
no Activity or live reader. Android onStop flushes pending state; onDestroy closes.
Recreation loses query/results/document, but the saved locator restores after a new
explicit search/open and valid acquisition. Desktop window close awaits draining up
to 3 seconds before exit. Abrupt process death may lose the most recent window or a
pending write; no final callback guarantee. Periodic saves reduce that loss.

No new dependencies or persistence/network permissions. Host tests verify algorithms
and real temporary-file restart/corruption behavior; actual Compose layout, Android
filesDir/filesystem/process lifecycle and device restart smoke remain manual checks.
See [verification](VERIFICATION.md) and [ADR 0015](adr/0015-persistent-reading-progress.md).
