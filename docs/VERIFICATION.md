# Verification

## PR #9 catalog Library actions — 2026-10-03

Verified starting main: **bb7dc10728c8560df47f8d21e4bf8535439e7686**, the PR #8
merge containing the Android result-returning PRAGMA correction. New branch
feature/catalog-library-actions; no reuse/rewrite of prior branches/history.
The user reports PR #8 physical Library/History process-restart persistence passed.
That is user-provided evidence, not an implementation-environment device test.

### Scope and invariants

Shared CollectionsController now provides one bounded source-scoped membership
set to catalog results and the reader, with per-publication pending actions,
durable-result updates and generation-checked authoritative refreshes. Unknown
membership disables mutations; failures retain prior labels/state and fixed safe
errors. Cancellation before a launch starts cannot leave membership loading.
Search/Library/History gain small label/spacing/source-name/empty-state improvements
and narrow-screen wrapping of result actions. Existing Back destinations and
retained Search source/query/results/page token are unchanged.

Catalog saves use PublicationSnapshot.from(publication): no getPublication,
ResourceContent, network acquisition, History, ReadingProgress or cached bytes.
Later saved opens still source-resolve IDs and run all existing acquisition checks.
No schema/migration/core/dependency/version/platform-storage change. PR #8 SQLite
PRAGMA handling, IO/durability/corruption/diagnostics and all source/cache/TEXT/
progress implementations remain untouched. No new ADR is needed for this action
within the existing controller/repository ownership.

### Commands and actual results

```sh
./gradlew :app:desktopTest :app:testAndroidHostTest \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

Targeted run passed in 1m 6s, 27 actionable tasks (13 executed / 14 up-to-date).
Two preliminary clean runs passed; the clean suite was repeated after adding
scope-start cancellation and failure-label regressions. The **final** clean run:
**BUILD SUCCESSFUL in 2m 11s**, **146 actionable tasks: 138 executed / 8 up-to-date**.
SQLDelight generation/definition verification, core/app/Desktop builds, Android
host tests, Android lint and debug APK assembly pass. Lint: **No issues found,
0 errors / 0 warnings**. No compiler/deprecation/packaging warnings, test failures
or intermediate failed build. Launcher unit task remains NO-SOURCE; generated
resource tasks may be NO-SOURCE/SKIPPED, not skipped test cases.

| Final test task | Executions | Failures / errors / skipped |
| --- | --- | --- |
| core:jvmTest | 38 | 0 / 0 / 0 |
| app:desktopTest | 332 | 0 / 0 / 0 |
| core:testAndroidHostTest | 38 | 0 / 0 / 0 |
| app:testAndroidHostTest | 312 | 0 / 0 / 0 |
| Total | **720** | **0 / 0 / 0** |

**396 unique cases** after JVM/Desktop suffix normalization. **27 new cases**:
18 shared catalog controller/state tests and nine shared real SQLite/application
integration tests (54 executions across both host targets). Existing tests are
unchanged. Coverage includes snapshot loading/no row lookups, source/local identity
and equal titles, pending unrelated rows/repeated taps, safe durable failures,
retry, stale/cancelled responses, scope closure, Reader/catalog consistency,
new repository/driver/owner restart, failed put/remove without RAM false success,
no source/content/history/progress/cache side effects, retained query/page state,
Library/History Back origin, current source re-resolution, progress restoration,
unavailable saved items and cache-only deletion/repeated owner recreation.
Android-host SQLite uses real JDBC files and a host JVM, not Android OS SQLite.

### APK and inspection

```sh
aapt2 dump badging androidApp/build/outputs/apk/debug/androidApp-debug.apk
aapt2 dump permissions androidApp/build/outputs/apk/debug/androidApp-debug.apk
aapt2 dump xmltree androidApp/build/outputs/apk/debug/androidApp-debug.apk --file AndroidManifest.xml
apksigner verify --verbose --print-certs androidApp/build/outputs/apk/debug/androidApp-debug.apk
sha256sum androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb devices -l
```

Task :androidApp:assembleDebug; artifact
**androidApp/build/outputs/apk/debug/androidApp-debug.apk**.
Size **11,585,646 bytes**; SHA-256
**bd46c0933c0e2c35cc82701792ff33085cc2fcc1ccbc03f19e811d8af8c9359e**.
Manifest/badging and debug v2 signature pass. The debug certificate matches the
previous PR #8 review APK; generated DEX contains the new catalog membership/action
code. Package org.infinilect.app, INFINILECT label, minSdk 26, target/compileSdk 37.
Only INTERNET plus the existing app-scoped AndroidX signature permission;
cleartext/backup disabled, no storage permission.

Both diff checks, local Markdown links and new-code GPL-3.0-or-later headers pass.
Full diff inspected against main; no generated APK/database/cache/progress/build
file or secret is committed. Dependency inventory/schema/platform/source/cache/
progress diffs are empty. No repository workaround or Maven 429; existing verified
/tmp JDK/SDK/Gradle cache and proxy/trust settings were reused.

### Limits and required human review

No live source requests/checks were run. No graphical Desktop or Android device/
emulator smoke test was performed; adb listed no device. APK compilation and host
tests do not verify touch layout, Android SQLite execution or process restart.
Run the exact [PR #9 A–J plan](LIBRARY_HISTORY.md#pr-9-catalog-action-physical-test-plan)
with the new APK: save without opening, terminate/relaunch, membership from Search,
Reader consistency/Back, History independence and cache-only deletion. Current IA
revision=null still fresh-acquires on open. Saving Gutenberg metadata is supported;
opening it remains unavailable while Gutenberg is search-only. No covers/formats/
settings/downloads/sync or visual redesign are included. PR remains unmerged.

## PR #8 blocking Android collections correction — 2026-10-03

Continued feature/local-library-history / PR #8 from the physically tested
cae78d92145604c336f1ccc56e9a0ac2c6cd34f9. Fetched main remains
8a89ef407b01fc9046b5c7c43cadaa1d0a5ecbb2. No branch/history rewrite or merge.
The user's Android report confirms existing PR #7 progress survives an update,
but Library/History are unavailable. The original host results below are historical
and did not verify Android OS SQLite durability.

### Root cause and exact correction

Two result-returning PRAGMA assignments used non-query calls: busy_timeout=3000
in Android onConfigure via execSQL, and max_page_count in every store transaction
via SqlDriver.execute. AOSP rejects SQLITE_ROW in its non-query path;
AndroidSqliteDriver implements execute using executeUpdateDelete. JDBC's generic
execute permits such results, so the old JDBC-backed host tests passed.
See [official source evidence and detailed guarantees](LIBRARY_HISTORY.md#android-result-returning-pragma-correction--2026-10-03).
No device exception trace was available; this investigation identifies concrete
incompatible calls from official source and reproduces their contract offline.

The callback now queries/validates the timeout and closes its cursor. The store
queries/validates the page limit on the transaction's connection. Fixed enum-only
operation/stage/reason diagnostics cover configuration/schema/query/commit/close,
with INFINILECTCollections logging only in debuggable Android builds. Release
logging is disabled and tested. No private data, exception messages or SQL is logged.
No error suppression, RAM persistence fallback, database deletion/recreation,
schema/path/version/dependency change or relaxed durability/security policy.
ReadingProgress, resource cache, core and both source adapters are unchanged.

### Actual final verification

```sh
./gradlew :app:desktopTest :app:testAndroidHostTest \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

Targeted run: **BUILD SUCCESSFUL in 1m 6s**, 27 actionable tasks: 14 executed /
13 up-to-date. Final clean run: **BUILD SUCCESSFUL in 2m 10s**, **146 actionable
tasks: 138 executed / 8 up-to-date**. SQLDelight generation/definition checks,
Desktop/shared/core builds, Android host tests, lint and debug assembly pass.
No compiler/deprecation/packaging warnings. Android lint: **No issues found,
0 errors / 0 warnings**. Launcher unit task remains NO-SOURCE, not a skipped case.

| Final test task | Executions | Failures / errors / skipped |
| --- | --- | --- |
| core:jvmTest | 38 | 0 / 0 / 0 |
| app:desktopTest | 305 | 0 / 0 / 0 |
| core:testAndroidHostTest | 38 | 0 / 0 / 0 |
| app:testAndroidHostTest | 285 | 0 / 0 / 0 |
| Total | **666** | **0 / 0 / 0** |

**369 unique cases** after normalizing JVM/Desktop suffixes. **14 new cases**
in this correction: nine shared driver/store contract tests (18 executions) and
five Android-host callback/diagnostic tests (five executions). They test rejected
old PRAGMA commands, correct query/cursor lifecycle, retained foreign-key/FULL/
timeout/page limits, fresh store/driver restart from committed SQLite data,
failed/ineffective page limits, rollback/cancellation, failure-stage propagation,
close idempotence, future schema preservation, non-destructive corruption handling,
and release/debug log gating. All previous cases remain intact.

Android-host tests invoke the production callback through a JDBC-backed
SupportSQLiteDatabase/Cursor seam; shared tests simulate Android's non-query
contract. **Neither is an Android OS/provider or physical-device test.**
Local Markdown links, SPDX and both diff checks pass; complete diff inspected.
No generated APK/database/cache/progress/build file or secret is committed.
No new dependency, source live check, Maven 429 or repository workaround.
Existing /tmp JDK/SDK/Gradle cache and proxy/trust settings were reused.

### Fresh debug APK and physical verification still required

Task: :androidApp:assembleDebug. Path:
**androidApp/build/outputs/apk/debug/androidApp-debug.apk**.
Size: **11,585,646 bytes**.
SHA-256: **1e1b86d77b2461cd6eacec9016705361acc512ae6569d03373bac0db4b94f158**.
aapt2 badging/manifest/permission inspection and apksigner verification pass
(debug v2 signature). Package org.infinilect.app; minSdk 26, target/compileSdk 37.
Only INTERNET plus the existing app-scoped AndroidX signature permission;
no storage permission. Cleartext/backup remain disabled.

adb devices -l listed no device/emulator; no graphical Desktop smoke test was
performed. **Do not merge until the new APK passes the physical A–J plan** in
[LIBRARY_HISTORY](LIBRARY_HISTORY.md#required-physical-android-smoke-test-not-yet-performed),
including process restart, cache-only deletion, retained PR #7 progress and normal
fresh IA acquisition. Debug failure-only capture:

```sh
adb logcat -s INFINILECTCollections:W '*:S'
```

Reader-only Add to Library remains unchanged. A metadata-only action from catalog
results is documented as a v0.1 follow-up in [ROADMAP](ROADMAP.md), not implemented
as part of this correction.

## PR #8 original local library/history verification — 2026-10-03

Starting/current fetched main: **8a89ef407b01fc9046b5c7c43cadaa1d0a5ecbb2**, merged
PR #7. Fresh feature/local-library-history branch; prior branches/history unchanged.
SQLDelight 2.4.0 (stable, Apache-2.0) is the only new direct dependency family.
Kotlin 2.4.20 / Compose 1.12.1 / Gradle 9.7.1 / AGP 9.3.1 / JDK 21.0.12.1+1 /
compile/target 37, min 26 are unchanged. No ReadingProgress migration.

### Actual checks and corrections

SQL code generation and Desktop/Android compilation were exercised first. The first
compile rejected a member-extension reference in row mapping; explicit lambdas
fixed it. New controller tests found a genuine history-list observer startup race
(drop(1) could discard an already committed change); collecting the current counter
fixed it. A queue barrier before history list/remove/clear prevents an earlier
queued successful open from reappearing after Clear. Targeted tests then passed.

The first clean full build stopped at SQLDelight migration verification: this
initial v1 has no historical database baseline. SQL definition checking remains
enabled; migration replay is disabled only for the initial schema, with historical
baseline/replay required at the first schema change. Fresh/reopen/future-version/
foreign-key real database tests pass. No generated SQLite baseline/runtime file
is committed. The complete clean command was rerun after that configuration fix.

```sh
./gradlew :app:generateSqlDelightInterface --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew :app:compileKotlinDesktop :app:compileAndroidMain --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew :core:jvmTest :app:desktopTest :app:testAndroidHostTest --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

Final **BUILD SUCCESSFUL in 2m 52s**, **146 actionable tasks: 137 executed,
9 up-to-date**. Includes SQLDelight interface generation and definition verification,
core/app builds, Desktop launcher, Android libraries/host tests/lint/debug APK.
No compiler/deprecation/packaging warnings. Lint: **No issues found, 0 errors /
0 warnings**. androidApp:testDebugUnitTest is **NO-SOURCE** (launcher has no unit
cases); this is not a skipped test. Resource-generation NO-SOURCE/SKIPPED tasks
are expected, not skipped test cases.

| Final test task | Executions | Failures / errors / skipped |
| --- | --- | --- |
| core:jvmTest | 38 | 0 / 0 / 0 |
| app:desktopTest | 296 | 0 / 0 / 0 |
| core:testAndroidHostTest | 38 | 0 / 0 / 0 |
| app:testAndroidHostTest | 271 | 0 / 0 / 0 |
| Total | **643** | **0 / 0 / 0** |

**355 unique cases**, after normalizing JVM/Desktop target suffixes. **62 new**:
core metadata/identity 7; real file SQLite repository/schema/rollback/restart tests
23; shared collection state/history ordering/confirmation tests 12; shared saved-open/
navigation/source-resolution/progress tests 13; Desktop actual driver/path tests 5;
Android persistent-database path tests 2. Android host SQL tests use the JDBC driver,
not an Android OS emulator. All existing source/cache/UTF-8/BOM/size/progress and
cancellation tests remain, and pass on their existing targets.

Durable restart tests discard driver/repository state, reopen entirely new objects,
and read committed SQLite data. Failed/cancelled writes retain the previous commit;
failed first writes leave no optimistic durable item. Tests cover normalized comma/
newline/Unicode authors/languages, source-scoped identity, deterministic ordering,
history deduplication/retention, quotas, unsupported schema/corrupt database,
concurrent transactions, independent cache/progress, fixed errors, source detail
and acquisition failures, cancelled/duplicate opens, Library/History Back origin
and retained Gutenberg/Archive Search. No acquisition resources persist in metadata.

### Dependency and artifact inspection

An external init script inspected actual Gradle resolutionResult graphs:

```sh
./gradlew -I /tmp/infinilect-android-inventory.init.gradle androidDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

Passed in 10s, one actionable task. It only observes graphs and does not replace
repositories. [Inventory](ANDROID_DEPENDENCIES.md) has 367 licensed external
components across runtime/test/tooling, including metadata/BOM components. Core
remains stdlib/annotations only. No previously resolved dependency/version changed;
new SQLDelight/Xerial/AndroidX SQLite components and tooling are separately listed.
JDBC native/test/tooling components are excluded from Android product runtime.
No Maven HTTP 429, repository workaround or new build-environment workaround.
Existing /tmp JDK/SDK/Gradle cache and proxy/trust configuration were reused.

```sh
aapt2 dump badging androidApp/build/outputs/apk/debug/androidApp-debug.apk
aapt2 dump permissions androidApp/build/outputs/apk/debug/androidApp-debug.apk
aapt2 dump xmltree androidApp/build/outputs/apk/debug/androidApp-debug.apk --file AndroidManifest.xml
apksigner verify --verbose androidApp/build/outputs/apk/debug/androidApp-debug.apk
sha256sum androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb devices -l
```

APK: **androidApp/build/outputs/apk/debug/androidApp-debug.apk**, **11,569,262 bytes**.
SHA-256: **af74ec49b5c848957d0471b92a9062d6ac1ea2e5770155b9e234bcc2e7468d1f**.
Package org.infinilect.app, label INFINILECT, minSdk 26, target/compileSdk 37.
Debug signature verifies (APK Signature Scheme v2). Manifest still denies cleartext
and backup; only INTERNET plus the existing app-scoped AndroidX signature permission
is requested. No storage/device-data permission. The AndroidX debug receiver's
DUMP attribute is a required caller permission, not a requested app capability.
Generated APK/DB/cache/progress/build files are untracked/ignored, never committed.
New code/schema headers use GPL-3.0-or-later; no GPL-3.0-only declaration, no changed
third-party licensing or secret/token artifact. Local Markdown links pass.

### Limits / required human verification

No live Gutenberg/Internet Archive/OAPEN check was run. Normal tests are entirely
offline; network access was only for official dependency/license evidence and build
resolution. Source/security/cache/progress implementations are unchanged. IA
revision=null still requires fresh acquisition; saved metadata/progress is no bypass.

**No graphical Desktop, Android device or emulator test was performed.** adb listed
no devices. Android host tests do not prove OS SQLite, Activity teardown or new UI
layout. APK compilation/manifest inspection are verified; physical A–J tests remain
pending in [LIBRARY_HISTORY](LIBRARY_HISTORY.md#required-physical-android-smoke-test-not-yet-performed).
In particular terminate/relaunch and cache-only deletion must preserve user metadata.
Pending history work may be interrupted by abrupt process termination; committed
transactions are the persistence guarantee. Corrupt database recovery/export and
historical migrations are future work, not silently destructive fallbacks.

## Blocking Android progress-persistence correction — 2026-10-03

Continued existing feature/persistent-reading-progress / PR #7 from
fde75c4ee938e14d6691b89f01868fa844094c62. Fetch confirmed current main remains
b45649da30553b1317090bf12eda460b82144ba2 (merged PR #6); no main/branch/history rewrite.
The user physically tested the **original** PR #7 APK: same-process Back/reopen
restored, complete terminate/relaunch did not, and the reader showed a save failure.
That report supersedes any inference of Android persistence from earlier host tests.

### Root cause, evidence and fix

Original FileReadingProgressStore.operation created the progress directory then
privatePermissions called Files.getFileStore(path). Official Android API 26 and
current libcore providers explicitly throw SecurityException("getFileStore") under
SELinux policy; failure occurs at DIRECTORY_PERMISSIONS before lock creation or
record writing. operation swallowed the exception as false. ProgressPersistence's
recent RAM map masked that failure during same-process reopen. Desktop/Android
**host** suites used a desktop JVM provider which permits FileStore queries.
[Official source links and exact guarantees](PROGRESS.md#android-compatibility-correction--2026-10-03).

Replace the filesystem-wide probe with the supported no-follow path POSIX attribute
view, preserving owner-only permissions. Keep private filesDir storage, locking,
force(true), same-directory ATOMIC_MOVE/REPLACE_EXISTING, checksums, quotas and old-file
preservation. No destructive move fallback. Add safe operation/stage/reason diagnostics,
with enum-only Android debug logging under INFINILECTProgress. No identifiers/paths/
contents/exceptions logged; release logging disabled. UI failure reporting remains.
Temp cleanup now cannot mask an original failure or a completed durable commit.

### Actual commands and results

Targeted command executed twice while adding regressions:

```sh
./gradlew :app:desktopTest :app:testAndroidHostTest \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

The first new Android factory restart test mixed auto-advancing test time with a
real IO lookup and hit its artificial timeout; place that production-factory lookup
on the real Default dispatcher (no production timeout change). Removed a redundant
conversion warning in test code. The second targeted run passed without warnings.

Complete final command:

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

**BUILD SUCCESSFUL in 1m 46s**, **144 actionable tasks: 136 executed, 8 up-to-date**.
No compiler/deprecation/packaging warnings; lint **No issues found (0 errors/warnings)**.
Existing toolchain/SDK/proxy/trust/cache configuration unchanged; no new dependencies,
permissions, versions, Maven 429 or permanent repository changes.

| Suite | Executions | Failures / errors / skipped |
| --- | --- | --- |
| core:jvmTest | 31 | 0 / 0 / 0 |
| app:desktopTest | 243 | 0 / 0 / 0 |
| core:testAndroidHostTest | 31 | 0 / 0 / 0 |
| app:testAndroidHostTest | 221 | 0 / 0 / 0 |
| Total | **526** | **0 / 0 / 0** |

**293 unique cases**, **11 new for this correction** (10 shared durability/diagnostic
cases, 1 Android factory-owner recreation case). androidApp:testDebugUnitTest remains
NO-SOURCE; Gradle task SKIPPED labels are not skipped test cases. All normal tests offline.
Existing source/acquisition/cache/TEXT/Unicode/lifecycle regressions remain green.

New tests use actual temporary files and fresh writer/store instances with no recent
map shared. They verify final .progress records, process-restart simulation, repeated
owners, cache deletion, RAM-only restoration after failed save, no false durability,
failure clearing after a successful commit, retained previous records after commit
failure, exact permission-stage diagnostics and observer isolation. The Android-like
provider throws on getFileStore exactly as documented while delegating path attributes,
locks/writes/rename to real host files: it is a compatibility fixture, **not Android OS**.
Reintroducing the old probe fails the positive commit/restart test.

### New APK and physical retest required

APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Size: **11,421,462 bytes**.
SHA-256: **`b239eea2ceff934a94d611f38d2525eb7b5a459ac6d8430d9aa349570e3b1ccf`**.
Build task: `:androidApp:assembleDebug`; official SDK aapt2 dump badging/permissions
and apksigner verify --verbose pass. Package org.infinilect.app, min 26 / target 37 /
compile 37, INFINILECT label, debug v2 signature. Permissions unchanged: INTERNET
and the existing AndroidX internal app-scoped signature permission; no storage permission.

No new live source checks/requests were made. Official Android documentation and GitHub
access are separate research/publication traffic. adb devices -l is empty; no display
or device/emulator is available. **The corrected APK has not been physically tested.**
Original user-reported failure remains documented; passing host fixtures cannot prove
physical acceptance. Retest on the same device using the same app data:

1. A: open TEXT, scroll, wait at least 3 seconds, Back, reopen. Restore approximately
   with **no save-failure message**.
2. B: open/scroll/wait at least 3 seconds/Back; completely terminate, relaunch,
   search/open the same publication. Restore with **no save-failure message**.
3. C: clear **cache only**, preserving app data/storage; relaunch/search/open. The
   saved logical position must remain.
4. D: normal fresh Internet Archive acquisition must occur on every reopen;
   revision=null/cache bypass and all source/security checks are unchanged.

If a device save still fails, debug logcat tag INFINILECTProgress identifies the
storage stage/reason without private paths or contents. No error UI suppression.
Normal process restart guarantees do not promise every power-loss/storage-device
failure; abrupt death can still lose the latest unsaved window. Atomic commit
failure preserves the prior valid record. No source/cache/core-policy changes,
APK/progress/cache artifacts, secrets, merge, squash, rebase or force push.


## Persistent reading progress — 2026-10-03

Fetched/pulled main before creating feature/persistent-reading-progress. Exact main
**b45649da30553b1317090bf12eda460b82144ba2**, PR #6 merge; GitHub also confirms PR #6
merged with that SHA. Core/source/cache/reader contracts were inspected first.

### Actual final command and results

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

**BUILD SUCCESSFUL in 1m 48s**, **144 actionable tasks: 136 executed, 8 up-to-date**.
Core/app/Desktop builds and Android compilation/lint/debug assembly completed.
No new dependencies/plugins/versions, repository substitutions or Maven 429.
Existing JDK 21.0.12.1+1/SDK/proxy/trust/dependency cache configuration outside Git
was used as documented below. No compiler/deprecation/packaging warnings;
Android lint: **No issues found (0 errors, 0 warnings)**.

| Suite | Executions | Failures / errors / skipped |
| --- | --- | --- |
| core:jvmTest | 31 | 0 / 0 / 0 |
| app:desktopTest | 233 | 0 / 0 / 0 |
| core:testAndroidHostTest | 31 | 0 / 0 / 0 |
| app:testAndroidHostTest | 210 | 0 / 0 / 0 |
| Total | **505** | **0 / 0 / 0** |

**282 unique cases**, **63 new**: pure domain/identity 8, logical Unicode/current-layout
mapping 10, real temporary-file store 22, persistence/session lifecycle 16, Desktop
paths 5, Android files/cache separation and injected platform factory 2. Shared tests
execute on both host targets; unique counts normalize KMP target suffixes.
androidApp:testDebugUnitTest is NO-SOURCE, not an executed launcher/instrumented suite.
Gradle configuration/resource tasks marked SKIPPED/NO-SOURCE are not skipped tests.

New tests cover namespace/resource identity, unsafe IDs/digest names, normalized/
malformed locators, emoji/surrogate/checkpoint boundaries, beginning/middle/EOF/empty,
changed document lengths, production line-mapping with different synthetic viewports,
restart/multiple records/newer timestamps, atomic replacement and precommit failure/
cancellation, checksums/truncation/unknown versions/oversized state, quotas without
user-state eviction, unrelated files/symlinks/concurrent owners/removal, cache deletion
and progress corruption independence, null-revision user-state persistence, fixed
save windows/flush/repeated close/immediate reopen, save failure/timeout, bounded
pending records, stale callbacks/submissions/source switch and fresh reacquisition.
All existing Gutenberg/Archive redirect/legal/acquisition/cache/TEXT UTF-8/BOM/size/
cancellation regressions remain green. Their policies/manifest/dependencies are unchanged.

Intermediate verification: the first targeted run failed one existing TextDocument
value assertion because its new progress identity was absent from the expectation;
updated the assertion without weakening it. The first complete build passed. The
next clean run was interrupted by managed-environment restart during APK packaging;
source files/tests remained intact. The final full rerun above completed successfully.
Initial sandbox Gradle launch could not create its local daemon socket; reruns used
approved execution permissions, no network/proxy bypass or repository change.

### APK inspection and manual limits

Standard task: `./gradlew :androidApp:assembleDebug`.
APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Size: **11,405,078 bytes** (about 10.88 MiB).
SHA-256: **64088e1469aee039b511ad9e81bcef803972e11b95aebc270912b71b755fba9a**.

Used SDK build-tools 36.0.0 aapt2 dump badging/permissions and apksigner verify --verbose.
Package org.infinilect.app, name INFINILECT, compile/target 37/min 26; standard debug
v2 signature verifies. Only INTERNET + AndroidX internal app-scoped signature permission;
no storage permission. Merged manifest retains cleartext=false and backup=false.

No source live checks were run: **0 Gutenberg/Archive/OAPEN requests or content bytes**.
Normal tests are offline. GitHub publication/Gradle tooling access is separate from
source integration testing. No DISPLAY/WAYLAND or Android device/emulator: adb devices
-l returned an empty list. **UI compiled; graphical/device progress-restoration smoke
not performed.** Android host tests use the host JVM/filesystem, not Android OS.
macOS/Windows path selection is simulated on Linux; native filesystem/ACL/atomic move
behavior is not verified there. No unresolved test/build failures are hidden.

Manual smoke pending: install/open → Archive search → open TEXT → scroll → Back →
reopen → approximate restore; then fully close/restart, search/open again and restore.
Also check rotation/back/background and clear **cache only**, retaining position.
Normal fresh Archive acquisition must still occur each time. Abrupt death may lose
up to the latest 2s save window plus an in-flight write; no universal power-loss or
pixel-perfect restoration guarantee. Atomic-move/quota/storage failures preserve
previous committed state and show a fixed save-failure message.

Local Markdown links, GPL-3.0-or-later SPDX, core purity, unchanged dependency/license
inventory and complete source/generated-artifact/secret diff inspection pass. No APK,
cache or progress records are committed. Policies and exact bounds are in
[PROGRESS.md](PROGRESS.md), [ADR 0015](adr/0015-persistent-reading-progress.md) and
[CACHE.md](CACHE.md). No merge/rebase/squash/history rewrite/force push.

## First persistent resource cache — 2026-10-03

Started from exact main HEAD **f47f42757cacf2670f01333e4c1306ce18c5a235**, the PR #5
merge, after fetch/checkout/pull --ff-only; final fetch confirmed main unchanged.
Work is on feature/persistent-resource-cache. No merge/rebase/squash/force push.

### Actual final commands and results

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

**BUILD SUCCESSFUL in 1m 44s**, 144 actionable tasks: 134 executed, 10 up-to-date.
Desktop/shared/Android compiled; actual debug APK produced. No new dependencies,
version changes, repository substitutions, baseline/suppressions or Maven 429.
Used the existing JDK 21.0.12.1+1, SDK/cache/proxy/trust configuration outside Git
documented in the previous Android section. No compiler/deprecation/packaging
warnings; lint **0 errors, 0 warnings, 0 issues**.

| Suite | Tests | Failures / errors / skipped |
| --- | --- | --- |
| core:jvmTest | 23 | 0 / 0 / 0 |
| app:desktopTest | 180 | 0 / 0 / 0 |
| core:testAndroidHostTest | 23 | 0 / 0 / 0 |
| app:testAndroidHostTest | 160 | 0 / 0 / 0 |
| Total executions | **386** | **0 / 0 / 0** |

**219 unique cases**, including **46 new cases**: disk cache 38, actual Archive
adapter/cache/TEXT boundary 2, application-owned loader/lifecycle 1, Desktop paths
4, Android private-path selection 1. Shared new cases run on both host targets.
androidApp:testDebugUnitTest remains NO-SOURCE; no instrumented suite/device tests
were skipped or claimed. Normal tests are entirely offline.

Cache tests use real temporary files, fake streams, controlled clocks/dispatchers
and restart simulations. They verify every identity field, ambiguous delimiters,
null revision bypass, streaming miss/hit, normal-close publication, truncation/
overflow/changed sizes, cancellation and prompt handoff races, early close,
corruption/header disagreement, recency/quota including container/temp bytes,
active pinned cursors, concurrent fills/owners, file locks, malformed files,
no-follow symlinks, best-effort unavailable storage and ownership/close behavior.

Archive MockEngine tests pass the real source through the cache loader and complete
the existing TextDocument flow twice: **4 metadata requests + 2 acquisitions**, no
cache entry. A second test acquires once, then changes the item to restricted;
fresh metadata blocks reacquisition rather than returning old bytes. No live
Internet calls in either test. All previous Archive redirect/legal/acquisition,
Gutenberg, UTF-8/BOM/512 KiB/cancellation/session tests remain green. Source policies,
core source/contracts, manifests and dependency declarations are unchanged.

Intermediate failures were test-fixture issues: a mutation wrote an already-zero
header byte, a metadata fixture had multiple formats, a negative size was rejected
by readBytes before the cache's read validation, and virtual time could expire a
Ktor transport lease while a real dispatcher was pending. Corrected the fixtures/
expectations and used the existing source-test transport-dispatch pattern; no
source timeout/security policy was weakened. Targeted offline Archive boundary
tests passed before rerunning the full clean command above.

### APK and environment inspection

Task: `./gradlew :androidApp:assembleDebug`.
Path: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Size: **11,372,251 bytes** (about 10.85 MiB).
SHA-256: **`288235eff09e66465feab7d313c639527a3c2f5ad65fa6729d24644933ee65fa`**.

```sh
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump permissions \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb devices -l
```

Inspected package org.infinilect.app, label INFINILECT, compile/target 37, min 26,
debuggable MainActivity, standard debug v2 signature verified. Permissions remain
INTERNET + AndroidX internal app-scoped signature permission; no storage permission.
Merged manifest still disables cleartext/backup. No APK/cache/build files or secrets
are committed. Markdown links/SPDX/dependency inventory/core purity/diff checks pass.

**No source live checks run (0 live-source requests/bytes).** No display/device/
emulator is available; adb returned an empty device list. UI compiled but graphical
smoke test not performed. Actual Android cacheDir/filesystem/OS eviction and physical
device lifecycle need manual validation. Android host tests execute on the host JVM,
not an Android OS. macOS/Windows path selection is tested as pure policy on Linux;
their filesystem rename/ACL behavior was not exercised on those operating systems.

Stable-revision restart hits are proven by offline fixtures. Current IA's null
revision means no persistent-hit/offline-reading claim. This is automatic evictable
infrastructure, not Downloads, metadata truth or reading progress; L1 RAM is deferred.
Ownership/paths/failure limits are in [CACHE.md](CACHE.md) and
[ADR 0014](adr/0014-persistent-resource-cache.md).

## First Android application target — 2026-10-03

Fetched origin, checked out main and pulled `--ff-only` before creating
`feature/android-app`. Main was exactly PR #4 merge
**c582654013d224939603c8aaf569e0a37bfe58b4**; a final fetch confirmed main unchanged.
No main edits, rebase, squash, force push, history rewrite or merge.

### Actual final build and offline tests

The command checks every library/Desktop build and Android debug tests/lint/APK.
Use explicit platform build tasks rather than root `build` to avoid generating
Android release artifacts outside this slice:

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 1m 39s**, 144 actionable tasks: 136 executed, 8 up-to-date.
Android bytecode/DEX/resources/manifest/signing/APK actually produced; Desktop
launcher/JAR compiled and shared libraries built. No compiler/deprecation/packaging
warnings. Lint XML/text report: **0 errors, 0 warnings, 0 issues**. No baseline,
blanket lint disabling or error suppression.

| Suite | Tests | Failures / errors / skipped |
| --- | --- | --- |
| core:jvmTest | 23 | 0 / 0 / 0 |
| app:desktopTest | 135 | 0 / 0 / 0 |
| core:testAndroidHostTest | 23 | 0 / 0 / 0 |
| app:testAndroidHostTest | 118 | 0 / 0 / 0 |
| Total executions | **299** | **0 / 0 / 0** |

These represent **173 unique cases**: 23 core + 135 Desktop app + 15 Android-only.
There are **50 shared common cases** (SearchController 5, ReadingSession 10,
OpenPublicationController 30, ApplicationSources 4, ResultKey 1), plus 53 engine-independent
Archive/neutral-acquisition cases run on both Desktop and Android host.
**21 new unique cases**: BOM-only 1, lifecycle/Back ownership 4, Android engine/
factory 2, Android XML-token adapter 5, real pull-tokenization fixtures 7,
source-scoped result-key identity 1 and Android key serialization 1.
`androidApp:testDebugUnitTest` is NO-SOURCE: meaningful Android unit tests live in
app/androidHostTest and core/androidHostTest. No emulator/instrumentation suite
was executed or silently skipped; Gradle's resource-generation/configuration
SKIPPED/NO-SOURCE tasks are not skipped test cases.

All normal tests are offline. Existing Gutenberg parser/source, Archive metadata/
URLs/acquisition and OAPEN diagnostics regression tests pass. ArchiveUrls and
ArchiveMetadata are byte-identical after the source-set move. Both sources change
only default engine injection; Gutenberg URL policy and project User-Agent are
byte-identical. Core common source/model/contracts are unchanged and its runtime
remains Kotlin stdlib + annotations only on JVM/Android. No security broadening:
CC0/public-only, fresh acquisition metadata, item-scoped exact locations, HTTPS,
redirect/loop/size bounds, revision=null, no lending/login/DRM.

Android parser tests exercise its configuration/token seam and real XML with
MIT test-only upstream kXML. The host shim permits only kXML's verified disabled
DTD default because upstream kXML lacks Android's optional process-docdecl feature;
production Android explicitly disables it and fails closed if unsupported.
Tests include real metadata/multiple authors/languages/rights/resources, optional
fields/empty feed/pagination, escaped/numeric UTF-8/CDATA, malformed XML, namespace
confusion and internal/external DTD/entities. This does not run the actual Android
OS decoder. Test kXML/JUnit, Desktop Java engine/Skiko and AGP are absent from APK
runtime graphs; [full inventory/licensing](ANDROID_DEPENDENCIES.md).

### APK actually generated and inspected

Standard task: `./gradlew :androidApp:assembleDebug`.
File: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Size: **11,355,808 bytes** (about 10.83 MiB).
SHA-256: **`1df99389318539bf6e3f15abed277ccc6dcb7767fd92bf1baafc239c7abc4f89`**.

Official SDK inspection commands:

```sh
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump permissions \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb devices -l
```

Inspected compiled APK and merged manifest: package **org.infinilect.app**, label
**INFINILECT**, versionCode 1/versionName 0.0.1-SNAPSHOT, compileSdk **37**,
targetSdk **37**, minSdk **26**; MainActivity is the exported launcher; debug variant.
INTERNET is the only requested Android capability. AndroidX Core additionally
merges **org.infinilect.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION**, a
**signature** permission scoped to this app, protecting non-exported receivers.
This is expected upstream security behavior, not access to storage/location/
contacts/microphone/identifiers. [Official Core manifest](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core/src/main/AndroidManifest.xml).
No unexpected device permissions. Cleartext **false**, backup **false**, legacy
fullBackupContent **false** plus explicit cloud/device-transfer exclusions.
EmojiCompat downloadable-font initializer absent; retained process lifecycle and
profile installer support are AndroidX runtime, no analytics/network tracking.

APK signature verifies (standard **debug v2 signing**, one signer). No release
signing key/configuration, release APK/AAB or distribution. ABIs: arm64-v8a,
armeabi-v7a, x86, x86_64 from the upstream AndroidX graphics-path native artifact.
Original provisional icon includes a monochrome layer. APK/build outputs are
ignored and never committed.

### Environment and intermediate findings

SDK provisioned outside Git at `/tmp/infinilect-android-sdk`; official command-line
tools 23.0 archive verified against the Google repository SHA-1, then stable
platform 37.0 r2/build-tools 36.0.0/platform-tools installed. JDK 21.0.12.1+1,
Gradle cache and proxy/TLS trust are environment-only, matching earlier verification.
No Maven 429 occurred, no repo mirror/workaround/permanent repository change.

Initial SDK 36 build failed AAR minCompileSdk checks for Compose 1.12.1; official
API 37 fixed it without changing Kotlin/Compose/Gradle. Initial launcher missed its
own Compose Foundation classpath, corrected with the same existing Compose version.
Gradle delegated `by creating` syntax warned; use `create(name)` without suppression.
Initial lint found manifest/classpath, backup, icon and target issues; fixed actual
configuration, retained stable AndroidX Startup 1.2.0 at compile/runtime and target
37. Initial debug native strip diagnostic removed by explicitly retaining the
upstream prebuilt path library's symbols, without adding an NDK.
Final compatibility review caught PublicationId as a LazyColumn key: Android
requires Bundle-supported keys. Replaced only the UI projection with the
serializable Pair<String, String>, keeping core and source/local identity intact.
Offline tests check collision resistance and Java serialization round-trip.
Initial upstream kXML tests rejected its unsupported Android-specific optional
feature; test-only shim checks its disabled default, leaving production strict.
The first component-inventory artifact query hit AGP secondary-artifact ambiguity;
use resolutionResult component graphs instead, without changing dependency resolution.

Official SDK 23's sdkmanager wrapper emitted a **tool deprecation** notice and
bootstrapped Android CLI; subsequent provisioning used its `--no-metrics` option.
This is an environment-tool diagnostic, not an app/compiler warning or app telemetry.
No Android CLI init/skills, external device service or APK upload performed.

### Checks and physical smoke limitation

```sh
git diff --check
git diff origin/main...HEAD --check
git status --short --branch
git rev-list --left-right --count origin/main...HEAD
```

Whitespace, local Markdown targets/anchors, SPDX GPL-3.0-or-later, dependency/
license inventory, core purity and regression checks pass. No credentials, APK,
actual publication body or build output is committed. GPL/CONTRIBUTING copyright
and dependency licenses are preserved. Final branch/tree status is reported with
commits/PR after publication; no merge.

No source live check was rerun: shared policies are unchanged and prior successful
Archive/Gutenberg evidence below remains historical. No source requests/retries
from this task's live checks. `adb devices -l` returned an **empty device list**;
its local daemon needs execution outside the restrictive socket sandbox.
DISPLAY/WAYLAND_DISPLAY absent. **APK built successfully; physical-device smoke
test pending. UI compiled but graphical smoke test not performed.** No emulator,
remote device reservation, strange graphical workaround or third-party upload.

Manual acceptance on a real API 26+ device:

1. `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk` (or local sideload).
2. Open INFINILECT and check layout/system/keyboard insets.
3. Select Gutenberg, explicitly search, then switch to Internet Archive.
4. Search `identifier:gmb-2015-93040`, Open text, check title/text and scroll.
5. Android Back: source/query/results preserved; open again and use Back to results.
6. Cancel Loading with Back; close/reopen the app. Check rotation/destruction safely
   resets the session (no retained query/results/document/scroll position promised).

No reader features/cache/persistence/new formats/distribution added. Review the
actual OS XML/network/lifecycle and device UI before claiming physical validation.


## First end-to-end bounded TEXT reading slice — 2026-10-03

Fetched origin, checked out main and pulled with `--ff-only`. Main matched the
expected PR #3 merge **238a7099428b373053352b20ef2a4a7e8f37d80f**; created only
`feature/text-reader` from that commit. No main edits, rebase, squash, history
rewrite, force push or merge performed in this slice.

### Implemented path

Desktop source choice → SearchController → PublicationSource → real search
results → explicit Open text → OpenPublicationController → details/TEXT selection
→ DirectResourceLoader → ResourceContent → complete bounded strict UTF-8
TextDocument → Compose TextReader → Back with current source/query/results.

Gutenberg remains the default search-only choice. Internet Archive is the only
reading choice, unchanged public-CC0 adapter, fresh permissions and fail-closed
item-scoped storage validation. Both source implementations, Archive metadata/
URLs, core and existing SearchController are identical to main. Existing neutral
resource selection was renamed/reused by the reader and prefix checks rather
than duplicated. No source registry, cross-source matching or simultaneous search.

Reader cap **524,288 bytes (512 KiB)** is separate from the source's **64 MiB**.
Unknown/zero/negative/changed/oversized size, short/long streams, invalid UTF-8
and invalid read counts give controlled errors. Reads allocate one size+1 payload,
probe at most one extra byte, and close before decoding. Decode explicitly as
UTF-8, strip one leading BOM, preserve interior BOMs, reject blank/NUL-bearing
documents. No full text logged; no cache/persistence/settings/EPUB/PDF reader.
Open is bounded to 60s, owns one session job and always closes its acquired handle.
Duplicate busy actions are ignored. Back cancels/invalidate generations; late
completion cannot replace a new document. A source switch cancels/discards the
old session and resets Compose collectors, without an automatic request.
See [ADR 0012](adr/0012-bounded-text-reading.md).

### Final offline verification

```sh
./gradlew clean :core:jvmTest :app:desktopTest build \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

**BUILD SUCCESSFUL in 31s**, 28 actionable tasks, all executed. XML reports:
**core 23 + app 129 = 152 tests**, **0 failures / 0 errors / 0 skipped**.
**39 new deterministic tests:** opener **29**, one-source session/navigation **10**.
All normal tests remain offline; no live task in test/check/build.

New tests cover complete TEXT and correct resource/once-only loader, details/ID
ownership, missing details/TEXT/PDF-only cases, size known/unknown/negative/zero/
changing/oversized/exact cap, actual overrun/early EOF/zero read, invalid UTF-8
including after the old prefix boundary, multibyte characters split across reads,
BOM, whitespace/NUL, read/acquisition/cleanup errors, retry, double Open, timeout,
close/cancellation/read and handle-handoff races, stale noncooperative completion,
application scope cancellation, Search/Loading/Reader/Back preservation,
source-switch isolation, explicit pagination and captured query/no automatic I/O.

Retained suites: Gutenberg parser 11/source 8; SearchController 5; Archive metadata
12/URLs 11/source 26; prior neutral prefix demo 4; OAPEN REST 7/alternate 6; all pass.
No compiler/deprecation warnings in the final clean run. The initial incremental
opener run passed but reported test-scheduler opt-in warnings; explicit test-only
ExperimentalCoroutinesApi opt-in removed them. Later incremental UI/tests passed.
The final cancellation-at-handoff guard/test was added after the live check;
it only affects cancelled operations and passed offline, so no live rerun.

Local Markdown paths/headings, SPDX GPL-3.0-or-later, both diff checks and core
purity passed. No publication bodies, credentials/build artifacts committed.
LICENSE/CONTRIBUTING/third-party declarations unchanged. No dependency or version
changes. Resolved inventory exactly matches the saved baseline: core runtime
**2**/tests **6**, app runtime **64**/tests **70**, build plugins **22**. Inspection
used a temporary environment-only init task:

```sh
./gradlew -I /tmp/infinilect-search-inventory.init.gradle searchDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

The mandatory clean build used **no init script**. Existing environment-local
JDK 21/proxy/CA configuration, TLS verification enabled, permanent repositories
unchanged. No Maven 429/mirror workaround. Kotlin 2.4.20, Compose Multiplatform
1.12.1, Gradle 9.7.1, Temurin JDK 21.0.12.1+1 and all dependency licenses unchanged.

### Exactly one new live full-document check

```sh
./gradlew :app:internetArchiveTextReadingCheck \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**2026-10-03T08:06:48.622357109Z**, task/build success in **11s**. Same
ReadingSession/SearchController/OpenPublicationController/document loader as UI,
with a byte-counting ResourceLoader wrapper only for diagnostic measurements:

| Request | HTTP | Application-consumed bytes | Outcome |
| --- | --- | --- | --- |
| Advanced search for identifier:gmb-2015-93040 | 200 | 594 | One explicit result page |
| metadata/gmb-2015-93040 | 200 | 5,359 | Publication/TEXT details |
| Same metadata immediately before acquisition | 200 | 5,359 | Fresh CC0/access/ownership/locations |
| download/gmb-2015-93040/gmb-2015-93040_djvu.txt | 302 | 0 | One validated redirect to dn760105.eu.archive.org |
| dn760105.eu.archive.org/0/items/gmb-2015-93040/gmb-2015-93040_djvu.txt | 200 | 2,566 | Full small TEXT, exact EOF/size, strict UTF-8 |

**5 requests, one redirect, 13,878 total application-consumed bytes**. Declared
and consumed document size **2,566 bytes**; **2,562 decoded UTF-16 characters**.
Resource closed before Ready. Back retained exact result state and query. Item
is the previously verified public CC0 government document; source still performs
fresh access/license checks. No 403/429, retry, content logging or persistent
download. Transport buffering can exceed measured application consumption.
No existing prefix, Gutenberg or OAPEN live check repeated.

Existing SLF4J no-provider/NOP warnings appeared only in the live process; no
logging backend/dependency added.

### Graphical limitation and review before merge

**UI compiled but graphical smoke test not performed.** Neither DISPLAY nor
WAYLAND_DISPLAY is available; no virtual-display workaround was attempted.
Offline session tests and live production-controller execution do not prove
Compose visual rendering/scroll behavior.

On a graphical desktop, run `./gradlew :app:run`, select Internet Archive, search
`identifier:gmb-2015-93040`, Open text, inspect actual text, scroll, Back and
confirm source/query/results remain. Also check Gutenberg search, switching source
during a search and Back during opening. Test layout responsiveness on documents
near the 512 KiB cap before broader usability claims. Strict UTF-8 and known-size
requirements intentionally exclude some items. No full-file hash/revision
verification, reader settings/position persistence or v0.0.1 completion claim.

## Final PR #3 delivery/lifecycle review — 2026-10-03

Started at `3d8cc453a3a53812850ccc10485e7229592c9a26` on the existing
`feature/oapen-acquisition` branch. `git fetch origin`, status, branch/log,
merge-base and left/right counts confirmed integrated main
`86d70c1bc4f281e7f866207ff8bfcfed647e058d` unchanged, an ancestor, **0 behind /
4 ahead** before the additive review commit. All four previous commits preserved;
no new branch, merge, rebase, squash, force push or history rewrite.

GitHub reported PR #3 **open, ready, unmerged, mergeable=true / clean** at review
start. Main was unprotected, repository rulesets empty, and the head had no
check runs/statuses or required checks. There was no local conflict or main
advance to explain the earlier `mergeable:false`. Its historical cause could
not be established; a temporary computation remains a possibility, not a
confirmed diagnosis. No main merge/rebase was attempted.

### Corrections and official evidence

The [current item-scoped boundary](INTERNET_ARCHIVE.md) replaces a production
single-node constant with fresh documented `server`/`workable_servers`/`dir`
coordinates and observed official-API `alternate_locations.workable` pairs.
Always start at the official download permalink; only exact announced
host/directory/item/file redirects pass additional HTTPS, structured DNS-zone
and canonical-path checks. No blanket subdomain authorization. Official pages
do not provide an exhaustive host family/list or redirect-count guarantee;
unsupported locations/hops fail closed. [ADR 0011 follow-up](adr/0011-verified-source-acquisition.md)
preserves the initial decision and explains the change.

Also corrected: missing `nodownload`/`is_collection` gates and ambiguous access
flags; permission refresh occurring before waiting for the acquisition mutex;
non-atomic close races; abandoned handle lifetime retaining the mutex;
overlong reads consuming more than one known-size overflow byte; and the demo
accepting truncated UTF-8 at actual EOF. Preserve `rights`, `licenseurl` and
`possible-copyright-status` separately, keep **revision=null / CC0-only**.
The existing two-redirect bound was correct; new tests verify both its accepted
boundary and rejection of a third hop. All sources/probes now share the normal
project User-Agent, without development-tool/model identification.

### Final clean build and offline tests

```sh
./gradlew clean :core:jvmTest :app:desktopTest build \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

**BUILD SUCCESSFUL in 28s**, 28 actionable tasks, all executed. XML reports:
**core 23 + app 90 = 113 tests**, **0 failures / 0 errors / 0 skipped**.
An earlier incremental `:app:desktopTest` also passed in 29s. No compiler or
deprecation warnings; expected Gradle generated-resource/configuration tasks
marked SKIPPED/NO-SOURCE are not skipped test cases.

App counts: Archive metadata **12**, URLs **11**, source/lifecycle **26**, neutral
demo **4**, OAPEN alternate **6**, OAPEN REST **7**, Gutenberg parser **11**,
Gutenberg source **8**, search controller **5**. Seventeen tests added in this
review; existing tests extended for the new trust boundary and standard UA.
Normal tasks remain completely offline/deterministic with MockEngine/fixtures.
Coverage adds multiple item-scoped nodes, unannounced and confusing domains,
Unicode/punycode, userinfo/ports/query/fragment, wrong identifiers/files,
traversal/double encodings, canonical URL loops, exactly two/excessive redirects,
fresh serialized permission checks, declared-size overflow, concurrent close,
virtual-time handle expiry, ambiguous access flags and truncated UTF-8 EOF.

Both diff checks and local Markdown file targets passed. Core is identical to
main and remains dependency-free in production (Kotlin only). Its source has
no HTTP/Compose/JSON/source/platform imports. Gutenberg's only implementation
change is referencing the centralized **identical** User-Agent value; its policy,
unsupported acquisition, tests, UI/controller and launcher remain unchanged.
No versions/dependencies/license changes or tracked publication bodies,
credentials/build artifacts. Runtime dependency licenses retain their terms.

Existing JDK 21/proxy/CA environment configuration was used with TLS verification
enabled and unchanged repositories. No Maven HTTP429 or mirror workaround.
Kotlin 2.4.20, Compose Multiplatform 1.12.1, Gradle 9.7.1, Temurin JDK
21.0.12.1+1, Ktor 3.6.0 and coroutines 1.11.0 unchanged. No visual UI/native
installer/reader verification. Existing SLF4J no-provider/NOP messages appeared
only in the live diagnostic; no logging dependency was added.

### Exactly one new live check

Only the Internet Archive check was rerun, because delivery policy changed.
No OAPEN or Gutenberg live request; no live retry.

```sh
./gradlew :app:internetArchiveAcquisitionCheck \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**2026-10-03T07:20:59.244405773Z**, task/build success in 10s:

| Request | HTTP | Application-consumed bytes | Outcome |
| --- | --- | --- | --- |
| Advanced search for identifier:gmb-2015-93040 | 200 | 594 | One explicit page |
| metadata/gmb-2015-93040 | 200 | 5,359 | Item/public CC0 resources |
| Same metadata, immediately before opening | 200 | 5,359 | Fresh access/location gate |
| download/gmb-2015-93040/gmb-2015-93040_djvu.txt | 302 | 0 | Validated redirect to ia803102.us.archive.org |
| ia803102.us.archive.org/35/items/gmb-2015-93040/gmb-2015-93040_djvu.txt | 200 | 512 | UTF-8 prefix through ResourceContent, handle closed |

**5 requests, 1 redirect, 11,824 consumed bytes total, 512 resource bytes**;
known file size **2,566**. No 403/429 or retry. This different primary node was
announced by fresh metadata and accepted without a hardcoded node constant.
Transport can buffer more than the application consumes. No whole-file
checksum validation or persistent download, and no text was logged.

### Remaining review limits

The source remains a narrow public-CC0 TEXT/PDF adapter with only TEXT-prefix
integration evidence. Unknown location shapes/hosts, more than two redirects,
unsupported license/access states and ambiguous data fail closed. Review the
observed alternate-location schema and 60s handle ownership deadline before
broader item support or a reader. Rights metadata is supplied by IA, not a new
legal adjudication. No cache, reader, lending/login/DRM or mobile features added.

## Alternate access and first acquisition — 2026-10-02

Stayed on `feature/oapen-acquisition`, preserving `8334415` and `bb464b9`.
Remote refs refreshed; integrated main `86d70c1bc4f281e7f866207ff8bfcfed647e058d`
has not advanced and is an ancestor. No new branch/rebase/squash/history rewrite.
The original REST finding below is historical: OAPEN's official alternate
metadata access works, but file transfer remains blocked/unverified.
[OAPEN](OAPEN.md), [Archive](INTERNET_ARCHIVE.md), [comparison](ACQUISITION_COMPARISON.md).

### Final checks

```sh
./gradlew clean :core:jvmTest :app:desktopTest build \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main...HEAD --check
```

- **BUILD SUCCESSFUL in 29s**, 28 actionable tasks, all executed.
- Both final diff checks/local Markdown links passed; working tree clean after commits.

Tests: core 23, app 73, **96 total**, zero failures/errors/skipped. App retains
Gutenberg parser 11/source 8 and search controller 5, original OAPEN probe 7;
adds Archive metadata 9/URL 5/source 19, neutral consumer 3 and OAPEN alternate 6
(**42 new tests**). Offline MockEngine/curated metadata fixtures; no normal
build/test/check task requests catalog/publication APIs. Coverage includes
mapping/optional metadata/IDs/languages/rights and file-license overrides,
unknown/private/restricted/lending cases, explicit opaque pagination/query encoding,
invalid tokens, ownership, schemes/hosts/ports/userinfo/traversal/redirect loops,
HTTP errors/type/encoding/length, bounded malformed/deep/oversized JSON/XML,
actual overlong/truncated streams, partial reads/EOF, cancellation during
open/read/parse, source shutdown, early close, fresh handles and UTF-8 prefix
boundaries. PDF streaming/content and live server outage tests are not claimed.

An initial clean run found an assertion expecting MockEngine's upstream channel
cancellation to be immediately visible. It was corrected to wait for the next
serialized request (producer cleanup), without sleeps; close remains non-blocking.
A first incremental compilation also found a missing timeout extension import
and an unnecessary `!!`; both were corrected before final checks.

Core, Gutenberg implementation/tests, SearchController/SearchScreen and desktop
launcher are identical to main. No Compose/Ktor/JSON/source/platform import or
new dependency in core. LICENSE/contribution terms remain GPL-3.0-or-later;
third-party licenses are unchanged. All new original Kotlin uses that SPDX header.
Both diff checks and local documentation links passed. No publication body,
credentials, build artifact or downloaded dump is tracked.

Resolved inventory (temporary inspection task, no repository override): core
runtime 2/test 6; app runtime **64**/test **70**; build plugins 22. Only new runtime
artifact is desktop serialization-json 1.11.0 (Apache-2.0, exact upstream tag),
sharing existing serialization-core 1.11.0; no compiler plugin/engine/version
change. See [DEPENDENCIES.md](DEPENDENCIES.md). Inventory used
`./gradlew -I /tmp/infinilect-search-inventory.init.gradle ... searchDependencyInventory`;
that first clean invocation's test timing failure is described above; the final
mandatory command used **no init script**.

Existing environment-local JDK 21/proxy/CA configuration was used, TLS verification
on, original repositories. No Maven 429 or mirror workaround. Final build has
no compiler/deprecation warning. Live tasks emit existing SLF4J no-provider/NOP
warnings; no logging backend added. No graphical UI/native installer was run.
Kotlin/Compose compiler 2.4.20, Compose Multiplatform 1.12.1, Gradle 9.7.1,
Temurin JDK 21.0.12.1+1, Ktor 3.6.0 and coroutines 1.11.0 unchanged.

### New live checks — exactly one invocation each

First command requested both tasks; Gradle stopped after OAPEN's timeout, so
Archive did not run in that invocation. Archive was then invoked separately
for its **first and only** run. Neither existing REST nor Gutenberg check rerun.

```sh
./gradlew :app:oapenAlternateAccessCheck :app:internetArchiveAcquisitionCheck \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew :app:internetArchiveAcquisitionCheck \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

| Check/time UTC | Request | HTTP / application-consumed bytes | Outcome |
| --- | --- | --- | --- |
| OAPEN 23:11:53 | One documented OAI GetRecord GET attempt | No status; 0 bytes | Request timeout after 15s; no HEAD/redirect/retry; task exit 1 |
| Archive 23:13:46 | advancedsearch.php for identifier:gmb-2015-93040 | 200 / 594 bytes | One result page, no next page |
| Archive | metadata/gmb-2015-93040 | 200 / 5,359 bytes | Publication and file list |
| Archive | same metadata (permission refresh) | 200 / 5,359 bytes | Explicit acquisition rights/ownership gate |
| Archive | download/gmb-2015-93040/gmb-2015-93040_djvu.txt | 302 / 0 bytes | One validated redirect to dn760105.eu.archive.org |
| Archive | /0/items/gmb-2015-93040/gmb-2015-93040_djvu.txt | 200 / 512 bytes | UTF-8 prefix through ResourceContent; early close; task success |

Archive: **5 requests, 11,824 bytes total**; size known **2,566 bytes**. No IA403/429,
no retry. One OAPEN GET attempt with no response plus five successful-response IA
requests in these live checks. Transport may buffer more bytes than consumed by
application; no complete book was explicitly consumed/saved/logged. Demo uses
DirectResourceLoader and neutral format selection; no advanced stream is passed
to a later reader. The final added file-specific-rights gate and diagnostic failure
counters were covered offline; no extra live invocation was made.

### Earlier development observations (separate from task runs)

OAPEN: three minimal requests — documented OAI GetRecord 200/10,525 bytes, MARC
books dump HEAD200/0 bytes (no size supplied), announced PDF HEAD403/0 bytes
(Anubis headers, no redirect). No dump or PDF downloaded; no challenge bypass.
These curl observations remain valid alongside the later Ktor timeout; they do
not imply reliable acquisition. Historical REST403 was not rechecked.

Archive: seven API/file development requests. One compound search returned HTTP
200 with a backend-error JSON object; a separate simpler documented query found
CC0 candidates. One candidate's metadata showed restrictions/private files and
was rejected without acquisition. A narrower public-text query found the chosen
Dutch government notice; metadata confirmed CC0/no restrictions/public files.
File HEAD302 established the observed storage host; one Range0–31 GET returned
206/32 bytes, Content-Range bytes0-31/2566, text/plain;charset=utf-8. No IA403/429
and no complete file consumed. No HTML catalog scraping or unofficial library.

### Review before expansion

Review exact delivery-host allowlist (unknown hosts fail closed), the narrow
CC0/access/file-rights policy, DOCUMENT semantic fallback and rights/license
field fallback before supporting more items or general UI acquisition. Only
TEXT prefix consumption verified live; PDF content/transfer, EPUB, full-file
hash revisions, complete charset validation and reading are deferred. OAPEN
needs permitted transfer/client discovery clarification. No cache/download
store/progress/reader/login/lending/DRM/mobile work; v0.0.1 remains incomplete.

## Initial OAPEN REST investigation — 2026-10-02 (historical)

Started from integrated main `86d70c1bc4f281e7f866207ff8bfcfed647e058d` on
`feature/oapen-acquisition`. Existing contracts, core, UI, Gutenberg sources/tests,
dependency versions and historical ADRs are unchanged. See [OAPEN.md](OAPEN.md)
for current official documentation, the two development-request failures and the
unverified acquisition mechanism. This is not a completed acquisition slice.

The independent Ktor/Java `:app:oapenApiAccessCheck --args=water` was run manually
**once**. It requested one bounded page from the documented REST search endpoint,
returned HTTP 403, consumed zero metadata bytes, and failed the opt-in task with
exit code 1 in 9 seconds. No publication metadata was mapped, pagination followed,
resource requested or graphical UI executed. The endpoint's development response
was `You address is not allowed to access this API.` No workaround was attempted.

Normal tests use MockEngine only. Seven new diagnostic tests cover query encoding,
fixed host/path/parameters and identifying headers, invalid queries without I/O,
HTTP access errors/redirects without retry, media/encoding and declared-size
validation, actual body bounds, cancellation/close and transport timeout. They
do not stand in for unimplemented OAPEN parsing, publication mapping, source
ownership, PDF streaming, resource selection or acquisition UI tests. Malformed
XML is not parsed by the transport diagnostic; HTTP success proves only access.

Final checks used the existing environment-local JDK/proxy/CA settings, with TLS
verification enabled and the original project repositories:

```sh
./gradlew clean :core:jvmTest :app:desktopTest build \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
git diff --check
git diff origin/main --check
```

- BUILD SUCCESSFUL in 25 seconds; 28 actionable tasks, all executed.
- Core: 23 tests. App: 31 tests (the existing 24 plus 7 diagnostic tests).
  Total: 54 tests; zero failures, errors or skipped tests. Gutenberg's 19
  parser/source tests and the existing 5 controller tests still pass.
- Both diff checks and local Markdown links passed; core and Gutenberg code/tests
  remain identical to main. No runtime/test dependency or toolchain change.
- No compiler/deprecation warnings or Maven HTTP 429 occurred. No repository
  mirror override or workaround was used. The live diagnostic emitted SLF4J's
  existing no-provider/NOP warning; no logging backend was added to silence it.
- The live diagnostic does not run during build; its HTTP 403/exit 1 is separate
  from the successful deterministic suite and build.
- No graphical UI, native installer, OAPEN catalog mapping or book acquisition
  was verified. Review the gates in OAPEN.md before implementing those features.

## Gutenberg search slice — 2026-10-02

Started from integrated main `28619ed`, on `feature/gutenberg-search`. Core models,
contracts, build configuration and tests are unchanged. Kotlin/Compose compiler
2.4.20, Compose Multiplatform 1.12.1, Gradle 9.7.1 and Eclipse Temurin JDK
21.0.12.1+1 remain selected; additions are Ktor 3.6.0 and coroutines 1.11.0.

### Actual final checks

| Check | Result |
| --- | --- |
| `clean :core:jvmTest :app:desktopTest build` | BUILD SUCCESSFUL in 23s; 28 tasks executed, desktop recompiled/JAR built |
| Core JVM tests | 23 tests; 0 failures/errors/skipped |
| App desktop tests | 24 tests: parser 11, source 8, search controller 5; 0 failures/errors/skipped |
| `git diff --check` / staged diff | Passed |
| Core purity | No core diff; Kotlin-only imports; resolved runtime remains kotlin-stdlib 2.4.20 and annotations 13.0 |
| Resolved dependency/license inventory | Core runtime 2, app runtime 63, core tests 6, app tests 69, build plugins 22; all license evidence recorded in DEPENDENCIES.md |
| Local documentation links and bilingual README coherence | Passed |
| Optional live source check | Success in 19s including compilation/inventory; one real search page for `shakespeare`, 25 books, next token present |

Tests are deterministic and require no Internet for Gutenberg. Authored fixtures
cover actual search navigation shape, structured multiple authors, languages,
optional absence, resources/type mapping, revision absence, empty results and
pagination. Malformed/unsupported XML, DTD/external entities, namespace lookalikes,
size/depth/entry/namespace bounds, encoded URL preservation, forged/mismatched
continuations and wrong-source IDs are tested. MockEngine covers headers, one-page
requests, explicit next, no redirect/retry, HTTP errors, wrong media/compression,
missing/misleading lengths, actual body limits, cancellation, close and serialization
of concurrent callers. Controller tests cover all five states, ignored busy actions,
manual pagination, previous-page preservation/retry, cancellation and generic errors.
Normal tests do not exercise real server outages, timeouts or resource acquisition.

### Real Gutenberg evidence

Before implementation, read official Offline Catalogs and Feeds, Terms of Use and
Robot Access documentation. With an identifiable/contact User-Agent, fetched the
OPDS entry page and its linked OpenSearch description, then one `query=shakespeare`
page to verify the query parameter, navigation entries and next relation. No human
publication HTML page was fetched, scraped or used as a catalog. See SOURCES.md for
exact endpoints and rules, including planned XML OPDS retirement in 2027.

The separate live task then used the production GutenbergSource/Ktor Java engine,
not fixtures or curl, against the same official HTTPS OPDS search endpoint. It
returned 25 mapped book entries after filtering author/subject navigation. Example
IDs/titles: 1513 — Romeo and Juliet; 100 — The Complete Works of William Shakespeare;
49008 — The Works of William Shakespeare [Cambridge Edition] [Vol. 8 of 9]. The
real feed's next URL was validated/encoded but never requested. Real search entry
languages/resources/rights were absent; mapping those fields is verified with
fixtures, not claimed as an acquisition integration test. Details/resources were
not requested by the live task. A later URL-escape/namespace hardening adjustment
was covered by the final clean tests/build; no extra live request was made.

### Commands and environment

Successful final commands used the original repository configuration, with the
same environment-local JDK/proxy/certificate settings as the foundation. No HTTP
429 occurred during this slice's Gradle runs. No Maven mirror override or permanent
repository change was needed. The earlier foundation's 429 is historical below.
TLS verification stayed enabled. With JDK 21 configured:

```sh
./gradlew clean :core:jvmTest :app:desktopTest build \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew -I /tmp/infinilect-search-inventory.init.gradle \
  :app:gutenbergSearchCheck --args=shakespeare searchDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

The temporary inventory init script only registers an inspection task; it does not
replace repositories. Contributors use `:app:gutenbergSearchCheck` without it.
The live task is not a dependency of test/check/build. No compiler/deprecation
warnings remained in the final clean build. SLF4J reported that no provider exists
and selected its NOP logger during the live check: intentionally no logging backend,
telemetry or extra dependency was added.

### Limits and review before the reader slice

- No graphical desktop/Xvfb: SearchScreen was compiled, not run visually. No UI
  tests, desktop interaction/accessibility or native installer verification is claimed.
- Source/transport/parser are currently desktop-only; shared UI/state and core remain
  KMP-compatible. Android/iOS adapters are deferred, not assumed to work with JDK StAX.
- `getPublication` and `loadResource` throw explicit UnsupportedOperationException
  after source ownership checks. Before offering Open, verify detail/acquisition
  endpoints and implement ResourceContent, charset, cancellation/close and revisions.
- Gutenberg search often supplies author display summaries instead of structured
  authors, and omits languages/resources. No enrichment requests are made. Only the
  observed book/search subset is implemented; not a complete OPDS validator/engine.
- Pagination is tested with fixtures/MockEngine and its real next link is validated;
  the actual second page was not fetched. Redirects/compressed feeds are rejected.
- There is no cache, download, persisted progress or reader. v0.0.1 is not finished.
- Recheck Gutenberg's XML retirement/OPDS2 access plan and source rights before
  extending the adapter. Verify graphical execution and IDE import manually.

## Foundation review (historical)

Second technical pass performed on 2026-10-02 in Linux x86-64. Final versions:
Kotlin/Compose compiler 2.4.20, Compose Multiplatform 1.12.1, Gradle 9.7.1,
Eclipse Temurin JDK 21.0.12.1+1.

### Actual checks and results

| Check | Result |
| --- | --- |
| Official Compose release/tag/API | 1.12.1 exists, published 2026-09-22, not draft/prerelease; retained |
| Wrapper update and regeneration under 9.7.1 | Success; distribution and JAR SHA-256 verified, see TOOLCHAIN.md |
| `clean :core:jvmTest` | Success in 20s, 22 tests before adding the multi-buffer regression case |
| `build foundationDependencyInventory` | Success in 16s; core and app built, desktop Kotlin compiled and JAR generated after clean |
| Final `:core:jvmTest build foundationDependencyInventory` | Success in 15s; 23 tests, 0 failures/errors/skipped |
| Build using original repositories (without mirror override) | Failed in 11s during plugin resolution: Maven Central HEAD returned HTTP 429 for Kotlin's BOM |
| Dependency comparison with original foundation | No changes in desktop runtime (46 artifacts, including local core), test (6) or build-plugin (22) graphs; no new dependencies |
| Core runtime graph | Only kotlin-stdlib 2.4.20 and JetBrains annotations 13.0; no Compose/Ktor/Readium/platform API imports |
| Local Markdown links and `git diff --check` | Passed |

Final tests: PublicationTest 7, ResourceContentTest 13, ResourceCacheKeyTest 3.
They cover source-aware identity, type/format independence, metadata absence and
preservation, ownership/uniqueness, representation/revision separation, unknown
revision keys, short reads across multiple buffers, known/unknown size limits,
EOF/zero limits, overflow-safe arithmetic, cleanup and error/cancellation propagation.
Fixtures are in-memory and use Kotlin's standard coroutine primitives without a
new dependency. Cancellation tests inject a cancellation signal; they do not claim
real HTTP scheduling/cancellation, adapter close behavior or cache persistence.
App tests remain `NO-SOURCE`; no UI test pass is claimed. No compiler/deprecation
warnings were reported by the final successful build with `--warning-mode=all`.

### Environment and commands

The initial environment provided only a JRE. The full Temurin JDK was downloaded
from its official GitHub release, checksum-verified and installed under `/tmp`.
It uses the environment's Java certificate store; TLS verification remains enabled.

The session still encounters a Maven Central IP rate limit. A direct GET returned
200, but Gradle's HEAD through the plugin portal returned 429, so that single GET
was not evidence of restored full resolution. Successful checks used a temporary
init script replacing repositories with JetBrains' official cache redirector for
the same Central/plugin portal/Google Maven coordinates. Project repository
configuration is unchanged. With the verified JDK and proxy/certificate settings:

```sh
./gradlew -I /tmp/infinilect-repositories.init.gradle clean :core:jvmTest \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew -I /tmp/infinilect-repositories.init.gradle build foundationDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew -I /tmp/infinilect-repositories.init.gradle :core:jvmTest build foundationDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

The init script and inventory task are inspection tools outside the repository.
They inspect `desktopRuntimeClasspath`, `jvmTestRuntimeClasspath`, `jvmRuntimeClasspath`
and the root build-plugin classpath. POM license declarations are recorded in
[DEPENDENCIES.md](DEPENDENCIES.md), with upstream notices in THIRD_PARTY_NOTICES.md.
The Gradle patch license remains Apache-2.0. No runtime or test library was added.
Normal contributor verification remains `./gradlew :core:jvmTest` and `./gradlew build`
with JDK 21; retry normal repository resolution outside this rate-limited session.

### Limits and manual checks before merge

- No graphical desktop/Xvfb was available: `:app:run` and interactive/visual behavior
  were not tested. Manually open the existing welcome window in a graphical session.
- JetBrains' Kotlin 2.4.20 compatibility table still ends at Gradle 9.7.0. The patch
  9.7.1 is Gradle's recommended update and passes this project's tests/build, not an
  asserted update to JetBrains' certification table. Check IDE import with JDK 21.
- Review the resource ownership/close and unknown-revision revalidation contracts
  before implementing a source. Transport cancellation, revision mapping, byte limits
  and charset handling need actual adapter integration tests later.
- Native installers, full native redistribution notices, Windows/macOS runtime,
  Android/iOS targets and platform entrypoint migration are not verified here.
- Gutenberg/OPDS, cache, downloads and persisted progress remain unimplemented;
  no end-to-end v0.0.1 reading test is claimed.

The earlier foundation passed 4 tests with Gradle 9.7.0; that historical result is
superseded by this pass's final 23-test/9.7.1 verification.
