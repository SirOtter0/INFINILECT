# Local library and reading history

## Scope and identity

Library/history are local user metadata, separate from sources, disposable resource
cache and persistent reading position. No sync, telemetry, contents, downloads,
bookmarks or favorites. Core defines PublicationSnapshot, LibraryEntry,
HistoryEntry, LibraryRepository, ReadingHistoryRepository and LocalStoreResult.
It has no SQLDelight, filesystem, Android or Compose dependencies.

PublicationSnapshot preserves ID, title, authors, semantic PublicationType,
languages, optional canonical/source URL and original rights statement. It contains
no PublicationResource, acquisition URL or reading locator. Rights are display
metadata, never permission to acquire current bytes. Identity is the composite
(source_id, local_id); titles, authors and URLs are not keys. Authors/languages
are ordered child rows, preserving commas, newlines, Unicode and duplicates without
delimiter encoding. IDs are SQL bound values, never filenames.

## Source resolution and navigation

```text
LibraryEntry / HistoryEntry → PublicationId → existing owning PublicationSource
→ getPublication() → existing TEXT selection → ResourceLoader → ResourceContent
→ bounded strict UTF-8 TextDocument → TextReader + ReadingProgress restoration
```

Only IDs are used to reopen saved entries. Stored resources are deliberately
absent. A current source must return valid fresh publication details before normal
acquisition. Internet Archive still requires public CC0 metadata and revalidation,
item-scoped redirects, size/MIME/EOF checks and revision=null cache bypass.
Gutenberg now re-resolves fresh RDF and acquires compatible UTF-8 TEXT; see [policy](GUTENBERG.md). Unavailable publications produce safe errors while
retaining the user's entry; an unknown/unsupported source does not trigger HTTP.

ApplicationSession owns small Search/Library/History navigation and temporary
collection-reader sessions. ReadingSession/SearchController/opening logic are
reused. Reader Back returns to its origin; Library/History Back returns to Search.
Opening from a collection leaves the selected Search source/query/results intact,
even when the saved entry belongs to the other source. Android Back uses the same
logic. No navigation/registry framework or simultaneous-source search is added.

The reader can add/remove its fresh publication snapshot. Membership changes only
after a successful committed repository operation; failure is visible with fixed
messages. Lists query storage after mutations. History is recorded only after
Ready, with fresh metadata, never after a failed/cancelled opening. Reopens update
one source-scoped row and timestamp. Newest first, then source/local ID breaks ties.
Library lists newest additions first with the same deterministic tie-breaker;
metadata updates preserve original added time. Last-opened time updates for an
existing library entry without implicitly adding it.

History Clear requires an explicit confirmation. Clearing/removing history does
not touch library, progress or cache. Library removal likewise affects only library.

## Catalog Library actions (PR #9)

Search results now offer Add to Library / Remove from Library without opening the
publication. Every currently displayed source can save metadata, including
Gutenberg results; saving does not imply that a reader/acquisition is
supported. Unsupported saved opens keep the entry and show the existing safe error.
The reader retains its secondary Library action.

CollectionsController owns one bounded list/membership snapshot (at most the
repository's 1,000 Library entries), not one database query per result. Membership
uses PublicationId, never title: source/local IDs remain distinct even with equal
titles. Loading/unknown actions are disabled; known saved/unsaved state is explicit.
Storage unavailability disables mutations and offers Retry Library. A pending
mutation disables only that publication across both Search and Reader. Repeated
taps on its source-scoped ID are ignored, not queued as opposing writes.

Actions use PublicationSnapshot.from(publication) and the existing repositories.
Committed Success changes membership; failure retains the previous membership and
shows a fixed message. Lists re-query storage afterward. Generation checks and
cancellation reject stale refreshes; cancelled writes re-query storage because a
commit can race delivery of the return value. Disposing the controller cancels its
jobs and clears loading/busy state. There is no optimistic durable RAM substitute.

Saving/removing catalog metadata does not call getPublication/loadResource, fill
resource cache, create reading progress, or record History. Only successful reader
opening records History. Search/source/query/results/page token and logical Back
destinations are retained. Library/History show title, authors, source, Open and
Remove, clear empty states and existing confirmed History Clear. Buttons can wrap
on narrow search layouts; no cover networking or visual redesign is introduced.

Schema v1, SQLDelight/dependency versions, platform storage paths, IO transactions,
busy_timeout/max_page_count query handling and debug-only diagnostics remain
unchanged. Source re-resolution/current acquisition authorization, independent
progress/cache and Internet Archive revision=null behavior remain unchanged.
The user reports corrected PR #8 collections survived physical Android restart;
that does not verify the new catalog UI. Host SQLite restart tests and the new
physical plan below provide separate evidence.

## SQLDelight and schema

SQLDelight **2.4.0**, Apache-2.0, generates the app-only SQL access layer; Android
uses AndroidSqliteDriver and the OS SQLite, Desktop JdbcSqliteDriver/Xerial SQLite.
[Official release](https://github.com/sqldelight/sqldelight/releases/tag/2.4.0),
[official multiplatform setup](https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/),
[dependency/license evidence](DEPENDENCIES.md).

Schema version **1** has library_entries, reading_history and four normalized
ordered author/language tables with cascading foreign keys. No resource bytes,
resource references, progress or arbitrary serialized Publication objects. Library
and history use independent tables/operations in one database; their lifetime is
separate from the progress/cache stores. All multi-row mutations are transactions.
Synchronous FULL durability and foreign keys apply to every connection, including
short-lived JDBC connections. Queries/mutations, initialization and close run on IO,
never the Android UI thread. Cancellation before commit rolls the transaction back.

Future schema changes add ordered SQLDelight .sqm migrations and a historical SQL baseline that can generate
a build-only verification database; never silently drop user metadata. SQL
definition verification stays enabled. Migration replay is disabled for this
initial schema with no historical baseline; the first schema change must provide
the baseline and enable replay. No generated SQLite file is committed. Fresh v0 initialization is transactional on Desktop; Android uses its
SQLiteOpenHelper transaction. Unsupported future schemas/corruption are unavailable,
not destructively recreated. Android's default deleting corruption handler is
explicitly overridden. This initial schema has no historical migrations to exercise;
v1/future-version/reopen/foreign-key tests establish the first baseline.

## Bounds and failure behavior

- Up to **1,000** library entries; new additions at capacity fail visibly, existing
  entries can update. Up to **500** history entries retained; older history is pruned.
- History UI queries **50** entries; repository requests must be **1–100**.
- Source ID 128 and local ID 1,024 UTF-16 units; title 2,048; 64 authors each 512;
  32 languages each 128; canonical URL 2,048; rights 8,192. Nonblank/no NUL,
  nonnegative timestamps. Snapshots are copied/revalidated at repository entry.
- Database page allocation is capped at **64 MiB**, with existing oversized files
  rejected. SQLite journals/temporary transaction work require additional finite
  OS storage; this is not a combined filesystem quota. Busy timeout is **3 seconds**.
- No optimistic durable RAM layer. LocalStoreResult.Success follows transaction
  commit; unavailable/invalid/capacity results never masquerade as successful saves.
- Failed updates roll back and retain the previous committed record where SQLite
  can still access it. Corrupt files remain for user recovery, not automatically
  deleted. Storage errors are fixed UI messages with no paths, SQL or private data.

## Platform storage and ownership

Database name is fixed: **infinilect-collections.sqlite**. Android obtains it from
applicationContext.getDatabasePath(), normally under the private **databases/**
directory; never cacheDir or external storage, no new permissions. The lazy driver
retains only applicationContext, never Activity. Opening is deferred to IO.

Desktop uses the parent of the established reading-progress directory: Linux
XDG_DATA_HOME/org.infinilect.app (or ~/.local/share/org.infinilect.app), macOS
~/Library/Application Support/org.infinilect.app, Windows LOCALAPPDATA/org.infinilect.app (or the
existing safe per-user fallback). The database is a sibling of reading-progress-v1,
not inside it or resource-cache-v1. Relative/unsafe paths use the existing safe
fallback or make storage unavailable; no cwd/repository fallback. JDBC uses an
encoded file URI so punctuation in a legitimate data directory cannot alter URL
options. POSIX directories/files are private; other platforms inherit user ACLs.

ApplicationSources owns the deliberately named ApplicationCollections component.
It exposes only library/history repositories and bounded successful-open capture.
The actor has 32 pending events, serializes history writes, and uses barriers before
list/remove/clear so earlier queued opens cannot reappear after Clear. Overflow is
reported as a save failure. Its snapshots contain no reader/Activity references.
UI jobs cancel on session disposal; application close cancels sessions first,
then drains captured history and closes the lazy driver on IO idempotently.
Desktop uses its existing bounded shutdown wait. Android destruction initiates
nonblocking cleanup; abrupt process death cannot guarantee completion of a pending
transaction. Committed transactions survive normal restart. No background source
requests result from persistence.

ReadingProgress remains the unchanged PR #7 versioned file store under filesDir /
reading-progress-v1 (or Desktop data path). It is not migrated to SQLDelight.
Deleting cache does not delete any user metadata. Corrupt progress cannot invalidate
library/history, and clearing them cannot delete reading position.

## Verification and limitations

Offline real file-database tests discard drivers/repositories and reopen new ones;
RAM cannot satisfy restart tests. Rollback/failure, foreign keys, quotas, ordering,
Unicode, concurrency, cancellation, storage separation and unknown schema tests
complement shared session/navigation tests. [Actual results](VERIFICATION.md).
Host tests do not prove Android OS driver behavior or visual layout; a physical
Android A–J plan remains required. Database recovery/export/sync and other readers
are outside this slice. ReadingProgress still restores only after normal source
acquisition and strict bounded TEXT decoding.

## Required physical Android smoke test (not yet performed)

A. Search Internet Archive → open readable TEXT → Add to Library → Back →
open Library → publication appears.

B. Completely terminate INFINILECT → relaunch → Library → publication still exists.

C. Open saved Library publication → confirm normal source acquisition occurs →
reader opens → previous ReadingProgress restores.

D. Open another publication → History contains it newest-first. Reopen it → no
duplicate, timestamp/order updates.

E. Terminate/relaunch → History survives.

F. Remove item from Library → History and ReadingProgress remain.

G. Clear History after confirmation → Library and ReadingProgress remain.

H. Clear Android CACHE ONLY → Library + History + ReadingProgress remain.

I. Existing Search → Reader → Back behavior still preserves search/results.

J. No storage/save failure messages during normal operation.

The small existing example is identifier:gmb-2015-93040. IA resources still have
revision=null: every reopen must run the usual fresh acquisition checks, including
when progress or saved metadata exists. Do not clear app data/storage for step H.

## Android result-returning PRAGMA correction — 2026-10-03

Physical testing of the original PR #8 APK at cae78d92145604c336f1ccc56e9a0ac2c6cd34f9
found Library/History unavailable while PR #7 progress survived the update. That
report overrides any inference of Android durability from JDBC/host tests above.

The Android onConfigure callback used execSQL for busy_timeout=3000. This PRAGMA
returns a row; Android's non-query execution rejects SQLITE_ROW. Every operation
also used SQLDelight execute for max_page_count assignment, which likewise returns
a row. AndroidSqliteDriver 2.4.0 implements execute via executeUpdateDelete, so
that call is a second independent blocker after configuration is corrected.
JDBC accepts these result-returning statements via its generic execute path;
previous host tests did not exercise the Android callback/non-query contract.

Official evidence:

- [AOSP API 26 native executeNonQuery](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.0.0_r1/core/jni/android_database_SQLiteConnection.cpp):
  SQLITE_ROW throws; nativeExecute and nativeExecuteForChangedRowCount share it.
- [AOSP API 26 SQLiteConnection](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.0.0_r1/core/java/android/database/sqlite/SQLiteConnection.java):
  result-returning PRAGMA assignments use executeForLong rather than execute.
- [SQLDelight exact Android driver](https://github.com/sqldelight/sqldelight/blob/2.4.0/drivers/android-driver/src/main/java/app/cash/sqldelight/driver/android/AndroidSqliteDriver.kt):
  AndroidPreparedStatement.execute calls executeUpdateDelete; executeQuery uses a cursor.
- [SQLite max_page_count](https://www.sqlite.org/pragma.html#pragma_max_page_count)
  explicitly says both forms return the maximum page count;
  [busy_timeout](https://www.sqlite.org/pragma.html#pragma_busy_timeout) queries/sets the handler.

Fix: query busy_timeout through SupportSQLiteDatabase.query, consume exactly one
3000-ms value and close the cursor. Query max_page_count through SqlDriver.executeQuery
inside the same transaction/connection, and require the returned count to equal
the intended 64-MiB page limit. No quota, timeout, foreign-key, synchronous FULL,
atomic transaction or corruption policy is dropped. Cursor/driver cleanup remains
explicit. No schema/version/storage path changes, no RAM fallback, and the original
user-visible error remains. Existing progress/source/cache implementations are intact.

CollectionsStorageFailure carries only fixed operation/stage/reason enums. Stage
markers cover storage path, driver, configure, schema, transaction, page limits,
queries/mutations, commit and close. Errors/cancellation still return/propagate as
before; diagnostic failures cannot change the outcome. Android logs these enums
under INFINILECTCollections only when ApplicationInfo.FLAG_DEBUGGABLE is set;
release logs nothing. No exception messages/classes, IDs, titles, URLs, SQL, paths,
contents or telemetry cross that boundary. Capture failure-only debug output with:

```sh
adb logcat -s INFINILECTCollections:W '*:S'
```

Offline regressions invoke the production callback through a JDBC-backed
SupportSQLiteDatabase/Cursor seam that rejects result-returning commands like
AOSP, and exercise store transactions with the same strict command contract.
New store/driver restart tests prove committed bytes, not RAM restoration. These
are **host simulations**, not Android OS/provider execution. No device log was
available to independently confirm the first exception on the user's device;
source evidence identifies two concrete incompatible calls in the tested path.
The new APK must repeat the physical A–J plan, including terminate/relaunch,
cache-only deletion, progress preservation and normal fresh source acquisition.

## PR #9 catalog-action physical test plan

New catalog interactions have not been tested on a physical device/emulator by
the implementation environment. Repeat this plan with the PR #9 debug APK:

A. Search Internet Archive. Add a result to Library **without opening it**.
Open Library: the publication appears.

B. Completely terminate INFINILECT. Relaunch: the Library entry remains.

C. Search for the same publication: it is shown as already in Library.

D. Open it: the reader shows Remove from Library. Existing reading progress,
if any, restores normally. Normal fresh source acquisition still occurs.

E. Back to Search: the selected source, query/results and page state remain.

F. Remove the publication directly from Search: Library no longer contains it.

G. History is unchanged by Add/Remove Library operations.

H. Successfully open a publication: History records it. Back to Search:
Library membership remains correct.

I. Clear Android **cache only**, never app data/storage. Restart:
Library + History + ReadingProgress remain.

J. Completely terminate/relaunch once more: no Library/History/progress storage
errors appear.

Internet Archive remains public CC0-only and revision=null; Library membership
never skips its fresh metadata/acquisition boundary. Gutenberg now re-resolves fresh RDF and acquires compatible UTF-8 TEXT; see [policy](GUTENBERG.md).
No persisted record or publication content is logged during this test.
