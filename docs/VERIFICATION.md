# PR #14 TEXT viewport hardening verification — 2026-10-04 UTC

Fetched PR #13 and verified it was merged before starting. Exact starting/current
main: `2093f754e9e22587abfbd7b44a76753f38339259` (PR #13 merge).
Branch: `feature/text-reader-hardening`. **Draft; no merge.**
[Diagnosis/invariants](TEXT_READER.md), [ADR 0020](adr/0020-stable-text-viewport.md).

## Scope and actual diagnosis

The reviewer reported much faster upward scrolling on physical Android. The old
full lazy list already had stable global indices; no prepend/append or custom
velocity caused the defect. Per-item asynchronous loading temporarily replaced a
visited multi-line window with a single text line, including warm file-cache hits.
Compose 1.12.1's official reverse-measurement loop uses that collapsed measured
extent. The regression independently simulates this loop: a 200px reverse delta
across known 1,000px slots lands at 9/800px, while old 20px placeholders traverse
from slot 10 to slot 0. These are synthetic host metrics, not device measurements.

The fix retains visited heights for the current layout, keys windows by global
code-point start within a document generation, uses one conflated loading worker,
symmetrically prefetches two neighbors per side, retains composed ready content
through LRU eviction and rejects stale/cancelled results. Actual final layout is
required for EOF progress. Initial restoration is the only programmatic scroll;
scrolling becomes enabled after it. The short FileWindows close/cache guard no
longer spans IO; its cancellable read Mutex/post-read checks prevent late publication
and close clears cached references. Physical gesture confirmation remains pending.

Source/acquisition/cache policy, progress persistence format/throttle, Library/
History SQL, strict UTF-8/BOM, 16 MiB TEXT ceiling, EPUB foundation, toolchain,
dependencies/licenses, launchers and manifests are unchanged. The separate Desktop
niri/Wayland black-area observation remains untouched pending Windows comparison.

## Commands and results actually executed

Final focused command (after the last production/test changes):

```sh
./gradlew :app:desktopTest --tests '*TextViewportTest' --tests '*FileTextDocumentTest' \
  :app:testAndroidHostTest --tests '*TextViewportTest' --tests '*FileTextDocumentTest' \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

Passed **61 tests per target** (22 viewport/worker, 39 indexed-document cases).
BUILD SUCCESSFUL in 1m 11s; 27 actionable tasks: 13 executed, 14 up-to-date.

Final clean verification:

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  :desktopApp:createDistributable \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 2m 26s; 150 actionable tasks: 142 executed, 8 up-to-date.**

| Task | Executions | Failures / errors / skipped |
| --- | ---: | --- |
| `:core:jvmTest` | 42 | 0 / 0 / 0 |
| `:app:desktopTest` | 499 | 0 / 0 / 0 |
| `:core:testAndroidHostTest` | 42 | 0 / 0 / 0 |
| `:app:testAndroidHostTest` | 485 | 0 / 0 / 0 |
| Total | **1,068** | **0 / 0 / 0** |

**555 unique class/method cases; 27 new unique cases**, executed on both app host
targets. Existing source/authorization/redirect/cache/progress/collections/SQLite
PRAGMA/EPUB tests are preserved. New coverage includes collapsed-slot reproduction,
forward/backward arithmetic with variable heights, 500 direction reversals, eviction/
reload and stable keys, 1,000 coalesced focus changes, one read in flight, distant
navigation without a full-document scan, cache bounds, retained composed windows,
late non-cooperative completion/failure, cancellation/idempotent close, false EOF,
Unicode/combining/CRLF/supplementary cuts, exact window edges and full persistent
owner/store restart after backward navigation. Host simulation is not a Compose
rendering/gesture test or an Android OS-filesystem test.

Android lint: **0 issues**. No compiler/Gradle warnings or new warnings. Existing
SLF4J no-provider/NOP messages remain in two JDBC host-test suites; no dependency
was added to suppress them. Launcher unit tasks remain `NO-SOURCE`, not claimed
as executed tests. The existing JDK 21/SDK 37/toolchain and repositories were used;
no Maven 429/repository workaround was needed. No live Gutenberg/IA/OAPEN checks
or publication acquisition requests were run. The official Compose source JAR was
consulted to verify measurement behavior; it is not a project dependency.

## Artifacts actually built and inspected

APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`

- Exact bytes: **11684009** (+16,384 bytes compared with the recorded PR #13 build).
- SHA-256: `eeb406e897769ea0acfef3f9129696f60ace1becd2b61989ccce5cd881a693a5`.
- aapt2: `org.infinilect.app`, versionCode 1, versionName 0.0.1-SNAPSHOT,
  label INFINILECT; minSdk26/target37/compile37.
- Permissions remain INTERNET plus the existing internal dynamic-receiver
  permission; no storage permissions or manifest/cleartext changes.
- apksigner verification passed; certificate SHA-256 is
  `547ad50541c240ad2327e8018619145a6b9d2d8a3954833ca81d46a19f9c8193`,
  matching the recorded previous debug APK. Package/signature are update-compatible;
  installing over that build was not physically tested.

Desktop image: `desktopApp/build/compose/binaries/main/app/desktopApp`.
`desktopApp:build` and `createDistributable` passed. Gradle-run and the image retain
one unchanged Desktop entry point consuming the same hardened shared reader.
Neither graphical app was launched. `adb devices -l` returned no attached devices;
DISPLAY and WAYLAND_DISPLAY are unset. No device, emulator or graphical Desktop
smoke test was performed. **Symmetric physical swipes still need reviewer testing.**

## Repository checks

`git diff --check` and `git diff origin/main...HEAD --check` pass. Local Markdown
links resolve. Kotlin source SPDX checks pass; original GPL-3.0-or-later/upstream
license declarations and dependency inventory are unchanged. Pure-core imports/
dependencies remain free of Compose/Ktor/platform APIs. File/credential-pattern
inspection found no generated APK/database/cache/progress/build artifacts or secrets
in versioned changes. Complete diff reviewed against the recorded current main;
no acquisition, schema, dependency or out-of-scope feature change.

## Reviewer physical/graphical plan

1. Install over the current debug build without clearing data. Library/History/
   ReadingProgress must survive.
2. Open the same large compatible IA public CC0 TEXT used to report the defect
   (or `identifier:stcrt-2015-37219` if current metadata still permits it). Scroll
   downward across more than eight windows, then upward with comparable swipes.
   Check that revisited windows no longer compress/jump; do not treat host tests
   as proof of gesture timing.
3. Alternate forward/backward repeatedly, including fast flings and distant
   regions, then reach beginning and end. No accumulated anchor drift/false 100%
   from loading. Extreme cold-region loading can still appear.
4. Wait >=3s, Back, reopen; then fully terminate/relaunch and reopen. Restore to
   approximately the same logical region. IA must still fresh-acquire because
   revision=null; no source authorization/cache bypass.
5. Open a small TEXT and test Android Back/button Back, retained Search query/
   results, Library and successful-open History.
6. Back/close while preparation or window loading is active; switch publication/
   source and verify no late text appears in another reader.
7. Clear Android **cache only**. User stores survive; freshly reopening rebuilds
   disposable preparation and restores progress.
8. In a graphical Desktop environment test the same bidirectional reader via
   Gradle and the packaged image. Record niri/Wayland sizing separately; this PR
   does not diagnose/fix that observation.

Unseen slot geometry is an explicit viewport-sized estimate; typography changes
reset measured geometry and restoration remains line-approximate. Window cuts
preserve code points, not grapheme/paragraph layout continuity. No exact physical
scroll timing or completed v0.0.1 release is claimed.

---

# PR #13 bounded EPUB foundation verification — 2026-10-04 UTC

Verified starting/current main: `b0f73b76b4df662a49fbee54684e3f9281d9a68a`
(merged PR #12). New branch: `feature/epub-foundation`. **Draft; no merge.**
[Policy/API evidence](EPUB.md), [ADR 0019](adr/0019-bounded-epub-foundation.md).

## Production scope and regression boundary

Pure EPUB document contracts plus application-owned Desktop/Android preparation
are implemented. EPUB remains **unavailable in the reading UI**. No renderer,
XHTML dump, new source/legal scope, Gutenberg acquisition, EPUB progress,
dependency/version, schema, manifest/permission or Desktop window/layout change.
Existing TEXT/source/cache/progress/collections code is unchanged apart from
application factory ownership of the lazy EPUB preparer.

## Commands actually executed

Focused core/EPUB tests were run first, then the corrected parser/lifecycle suite:

```sh
./gradlew :core:jvmTest --tests '*EpubDocumentTest*' \
  :app:desktopTest --tests '*FileEpubPreparerTest*' --tests '*ApplicationSourcesTest*' \
  :app:testAndroidHostTest --tests '*FileEpubPreparerTest*' --tests '*ApplicationSourcesTest*' \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

That focused run passed 4 core tests and 51 EPUB + 6 owner tests on each app host
target. The final clean suite also includes the later close-after-validation
regression, taking EPUB preparation to **52 cases on each host target**.
Synthetic ZIP/XML fixtures are generated from original tiny strings, not books.
Tests cover valid nested/Unicode/multi-spine packages, local ownership/handles,
ZIP header/CRC/path/alias/symlink/size/ratio attacks, XML/XXE limits, unsupported
protection/active/remote content, exact size/unknown bounded size, cancellation,
60s virtual deadline, late handoff, failure/close cleanup, stale owner isolation
and platform paths. Host Android tests use the host JDK, not a device provider.

Final full clean command, after all production changes:

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  :desktopApp:createDistributable \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 2m 28s; 150 actionable tasks: 142 executed, 8 up-to-date.**

| Task | Test executions | Failures / errors / skipped |
| --- | ---: | --- |
| `:core:jvmTest` | 42 | 0 / 0 / 0 |
| `:app:desktopTest` | 472 | 0 / 0 / 0 |
| `:core:testAndroidHostTest` | 42 | 0 / 0 / 0 |
| `:app:testAndroidHostTest` | 458 | 0 / 0 / 0 |
| Total | **1,014** | **0 / 0 / 0** |

**528 unique class/method cases; 57 new unique cases** (4 core, 52 preparation,
1 application-owner case). `androidApp:testDebugUnitTest` and Desktop launcher
unit tasks remain `NO-SOURCE`, not claimed as executed tests. Lint report: **0
issues**. No final Kotlin/compiler/Gradle warnings. Two existing JDBC-test suites
still contain the prior SLF4J no-provider/NOP messages; no logging dependency is
added. Initial development test/setup failures and one Android nullability warning
were corrected before this final clean run; no existing test was weakened/deleted.

The configured JDK 21/SDK 37 and existing toolchain/repositories were used. No
Maven 429/repository workaround was needed by these final commands. Existing
version/dependency/license declarations and upstream license copies are unchanged.

## Artifacts actually produced/inspected

APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`

- Exact bytes: **11667625**.
- SHA-256: `2900f83d5e8ac7aa05f639382f7b6abe1c283e8c3641a9d56fabba9ecf79a6cd`.
- aapt2: package `org.infinilect.app`, versionCode 1, 0.0.1-SNAPSHOT,
  minSdk26/target37/compile37, label INFINILECT.
- INTERNET and the existing internal dynamic-receiver permission only; no new
  storage permission or cleartext policy. Source manifests are unchanged.
- apksigner verification passed. Debug certificate SHA-256 remains
  `547ad50541c240ad2327e8018619145a6b9d2d8a3954833ca81d46a19f9c8193`,
  matching the prior build's package/signature for an update on the same device.
  It is not a release-signed APK.

Desktop image: `desktopApp/build/compose/binaries/main/app/desktopApp`.
`createDistributable` passed. A module-restricted JDK probe using the actual image
JARs and its `MODULES` list loaded the new core/preparer classes and configured/
parsed local SAX with required external-entity flags/lexical handler successfully.
The image retains java.xml/java.net.http/java.sql. This is a headless API/runtime
probe, **not** launching or visually testing the packaged application.

## Audits and remaining manual verification

Both `git diff --check` and `git diff origin/main...HEAD --check`, complete diff
inspection, local Markdown links, source SPDX and generated/secret/dependency
checks pass. Core remains pure Kotlin; source/reader/cache/progress/collections/
SQL/manifest policies are unchanged. No APK, ZIP fixture binary, build output,
prepared data or credential is committed. Existing historical verification text
mentions an obsolete license identifier only to say no such declaration exists;
project code remains GPL-3.0-or-later.

**Zero live source requests:** no IA/Gutenberg/OAPEN live check, acquisition, extra
page or book download. Official standards/API documentation, GitHub and build
traffic are separate. **No Codex physical Android/emulator or graphical Desktop
verification.** Android host tests cannot prove real SAX/NIO/provider/ownership
behavior. This Draft remains a deliberately narrow foundation: representative
permitted EPUB compatibility and actual Android provider tests are still needed
before enabling any EPUB engine. CSS/SVG assets are inert, not sanitized browser
content; no full EPUB conformance/rendering/security claim is made.

Human checks for this APK should preserve existing small/large TEXT, Back/source
switch, Library/History/progress restart and cache-only deletion behavior. There
is no user-facing EPUB action to smoke-test. The previously reported Desktop
window/niri observation is untouched and no new conclusion/test is asserted here.

---

# PR #12 Desktop first-slice verification — 2026-10-04 UTC

Starting/fetched main is exactly `dde04ffcced213d020197e8dfc686136118885c0`,
the PR #11 merge. New branch: `feature/desktop-first-slice-verification`.
PR #12 must remain **Draft pending human graphical Desktop verification**.
No merge, history rewrite or force push. v0.0.1 is not declared complete.

## Separate evidence and remaining gap

**Human Android evidence supplied by the reviewer:** merged PR #11 passed the
existing persistent-state, IA TEXT/indexed reading/forward-backward scroll/progress/
Back/reopen/process-restart, Library/History/catalog actions/cache-only deletion,
experimental Gutenberg OPDS2 search/explicit Next/metadata-only Library/unsupported
opening tests. The final pagination correction starts Next at the top/first result.
This is not a Codex device test. It supersedes the pending-device statements in
older sections below; those historical results are retained.

**This environment:** DISPLAY and WAYLAND_DISPLAY are unset; no X11 socket or
usable graphical session/Xvfb is available. A JDK probe returned
`GraphicsEnvironment.isHeadless=true` without forcing that property. No app window,
visual text/scroll/Next, native window-close/relaunch or real-source Desktop UI test
was performed. ADB's local socket required the existing sandbox escalation;
`adb devices -l` then returned an empty device list. No Android device/emulator
was used. No GUI infrastructure was installed.

**Zero live source requests:** no IA/Gutenberg/OAPEN opt-in check was run and no
publication was acquired from a real server. Tests use offline MockEngine/fixtures.
Gradle dependency/build traffic and GitHub operations are separate from source
requests. PR #11's successful live Gutenberg evidence was not repeated.

## Demonstrated Desktop runtime defect and narrow fix

The existing `:desktopApp:createDistributable` task succeeded, but its default
jlink runtime included only java.base/java.datatransfer/java.xml/java.prefs/
java.desktop/java.logging/jdk.crypto.ec. It omitted both required modules:

- Production `createApplicationSources()` failed with
  `NoClassDefFoundError: java/net/http/HttpClient$Version` in Ktor's Java engine.
- Loading the existing SQLite JDBC driver failed with
  `NoClassDefFoundError: java/sql/Driver`.

These failures were reproduced without a graphical window or network request:
temporary Java probes outside the repository used the image's actual packaged
JARs and full JDK `java --limit-modules` with exactly the module list read from
`lib/runtime/release`. This demonstrates a runtime packaging defect; it does not
claim execution of the bundled launcher or GUI. Full-JDK Gradle-run/host tests
already have these modules, which concealed the defect.

**Only production change:** desktopApp's existing nativeDistributions configuration
adds `modules("java.net.http", "java.sql")`. No include-all-modules workaround,
new dependency, installer task, native target, shared/source/reader code or Android
change. The rebuilt runtime includes both and the transitive java.transaction.xa.
Both module-restricted probes now exit0: actual source owners construct/close
without requests; SQLite JDBC connects to an in-memory database and executes a
query. The final post-clean image was checked again. OpenJDK module notices remain
in the image's legal directory; runtime/license declarations are corrected.

## Desktop lifecycle/filesystem review and offline integration

Reviewed Desktop Main/ApplicationSources/ApplicationSession/ReadingSession/opening,
progress writer/history queue/SQL driver, cache handles and prepared TEXT cleanup.
Normal window close cancels the session first, queues latest logical progress,
closes producers/transports and waits up to **3 seconds** for independent progress,
history/driver and preparation cleanup. Disposal/repeated close is idempotent.
Abrupt termination or unusually slow storage can lose pending writes after that
bounded wait; this PR does not promise crash-proof final lifecycle callbacks.

Desktop namespaces remain separate under platform per-user locations:

| Platform | Persistent base | Cache/preparation base |
| --- | --- | --- |
| Linux | absolute XDG_DATA_HOME or ~/.local/share | absolute XDG_CACHE_HOME or ~/.cache |
| macOS | ~/Library/Application Support | ~/Library/Caches |
| Windows | absolute LOCALAPPDATA or ~/AppData/Local | absolute LOCALAPPDATA or ~/AppData/Local |

Each uses org.infinilect.app. Persistent siblings are
infinilect-collections.sqlite and reading-progress-v1; cache namespaces are
resource-cache-v1 and reader-text-v1. Relative/invalid environment paths fall back
safely or become unavailable; no cwd/repository fallback. Existing identity digests,
private POSIX permissions where supported, inherited Windows user ACL, no-follow
owned-file checks and generated prepared filenames remain intact. OS path policy
is tested on this Linux host; native macOS/Windows filesystem execution is untested.

One new Desktop-only integration case,
`DesktopReadingLifecycleTest.desktopOwnerCloseRestartAndCacheDeletionPreserveIndependentUserStores`,
uses production IA mapping/acquisition/redirect policy with offline MockEngine,
real Desktop JDBC/SQLDelight, FileReadingProgressStore, DiskResourceCache,
FileTextPreparer and application/session ownership. It checks:

- explicit search and metadata-only catalog Library save; no successful-open History yet;
- actual TEXT preparation/window read and pending logical position;
- direct idempotent owner close while Ready, finite drain, closed document/source,
  one driver release, a committed progress file and no prepared payload;
- cache-only deletion in the test's own temporary tree, never real user state;
- entirely new writers/stores/clients/sessions, durable Library/History/progress
  restored without old RAM, saved-item source re-resolution and progress restoration;
- two fresh metadata requests and one transfer per open, revision=null, no reusable
  resource-cache entry, and Back returning to Library.

Real-time IO is used instead of a virtual test clock for this combined boundary.
Existing coverage still handles source switching/stale results, retained Search/
Reader Back, catalog mutation without scroll reset, explicit-only pagination and
successful-page viewport replacement. No additional lifecycle defect was demonstrated.

## Exact final commands and results

Focused offline test:

```sh
./gradlew :app:desktopTest --tests '*DesktopReadingLifecycleTest*' \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**1 case, 0 failures/errors/skips; BUILD SUCCESSFUL in 19s, 15 actionable tasks:
6 executed / 9 up-to-date.**

Full clean verification:

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 2m 12s; 146 actionable tasks: 138 executed / 8 up-to-date.**

| Task | Executions | Failures/errors/skips |
| --- | ---: | --- |
| core:jvmTest | 38 | 0/0/0 |
| app:desktopTest | 419 | 0/0/0 |
| core:testAndroidHostTest | 38 | 0/0/0 |
| app:testAndroidHostTest | 405 | 0/0/0 |
| Total | **900** | **0/0/0** |

**471 unique cases**, one new Desktop case. The focused run is separate from these
full-suite totals. Android app and Desktop launcher unit tasks are NO-SOURCE;
Android host tests are JVM tests, not device tests. Android lint **0 issues**.
No compiler/Gradle/dependency warnings. Existing SLF4J no-provider/NOP messages occur
in the temporary runtime probes/test stderr; no logging backend was introduced.
Configuration-cache advice is not a verification warning. Existing JDK21/proxy/CA,
SDK and Gradle cache setup were used; no repository changes or new Maven429 workaround.

After the clean build:

```sh
./gradlew :desktopApp:createDistributable \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 15s; 18 actionable tasks: 7 executed / 11 up-to-date.**

## Artifacts and checks

- Desktop launcher JAR: desktopApp/build/libs/desktopApp-0.0.1-SNAPSHOT.jar,
  **10,751 bytes**, SHA256
  `209f3eb0d0be0be766850f4264fdd2c42c3712bb5c22257d04f7fcec79c7c716`.
  This is not a standalone dependency/runtime bundle.
- Linux x64 app image: desktopApp/build/compose/binaries/main/app/desktopApp,
  **182 regular/readable files, 161,550,308 logical bytes** (sum of file contents,
  including followed internal legal symlinks). No installer/release was created.
  The sorted relative-path/SHA256 file manifest is outside the repository at
  `/workspace/artifacts/INFINILECT-PR12-desktop-app-image.sha256`; its own SHA256 is
  `82b4a950fdf80a82484bf85ba24cf59d246d12c9e014bf24f8170546084b8a0a`.
  This manifest digest is not a hash of a directory/archive. JDK legal notices
  remain present; no JUnit/Hamcrest/MockEngine/kotlin-test/coroutines-test JARs are bundled.
- Standard debug APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk,
  **11,618,414 bytes**, SHA256
  `d1aed899195c49a6039f0c07859b8f4a11e809bf2e8da52402eaa0d0c650566f`.
  It is byte-identical to the retained final PR #11 pagination-fix APK.
  AAPT2: org.infinilect.app, versionCode1/0.0.1-SNAPSHOT, min26/target37/compile37,
  INTERNET and existing internal signature receiver permission only. Apksigner
  verifies the same debug certificate SHA256
  `547ad50541c240ad2327e8018619145a6b9d2d8a3954833ca81d46a19f9c8193`.
  No new Android update test is claimed or needed to establish byte identity.

Both diff checks, full base diff inspection, local Markdown file links, Kotlin SPDX,
license/dependency inventory and tracked artifact/secret checks pass. Core, shared
application/source/security, cache/progress/collections algorithms, SQL schema,
Android manifest/permissions and dependency versions are unchanged. No binaries,
probes, runtime/cache/progress/database files or source contents are committed.

## Human Desktop smoke plan — pending

1. On a graphical desktop, run `./gradlew :desktopApp:run` (or the Linux image's
   `bin/desktopApp`). Search IA `identifier:stcrt-2015-37219`; open a compatible
   public CC0 TEXT, scroll forward/backward, wait at least 3s, Back/reopen and
   verify approximate progress plus retained Search/results/scroll.
2. Add/remove from catalog and reader; confirm Library mutation alone creates no
   History. Successful reading creates History. Save Library/progress, close the
   app completely, relaunch and reopen: all three stores remain and progress restores
   after normal fresh IA acquisition.
3. With the app closed, remove **only** this app's resource-cache-v1 and reader-text-v1
   in the platform cache base above, preserving the persistent directory. Relaunch:
   Library/History/progress remain; TEXT reacquires/prepares. Never delete app data.
4. Select experimental Gutenberg, search Shakespeare, scroll down and press Next
   explicitly: first new result appears at the top. Save metadata-only Library;
   close/relaunch; opening remains safely unsupported, with no new History/progress/
   cached bytes. Remove it without affecting unrelated state.
5. Switch sources during search; old results must not overwrite the active source.
   Catalog Add/Remove must not reset result scrolling. Reader/Library/History Back
   and window close/relaunch must work without storage errors. Report OS, JDK/image,
   approximate restored location and any visual/lifecycle failure. Stop after this
   slice; no EPUB/Downloads/settings or additional live checks are requested.

---

The following sections preserve earlier verification history; their pending
PR #11 device gate is superseded by the reviewer's Android evidence above.

# PR #11 link-length correction — 2026-10-04 UTC

Previous HEAD: `097140d5ff28a4d85544d04e7f5a590bf1166a3b`.
Fetched main remains `a5caaadfb859160f742740d23d1869bd78db0f38`; same
`feature/gutenberg-full-integration`, existing PR11 open Draft. This section
supersedes the **current-blocker** claim in the historical 08:01 root503 report
below, preserving that observation. No branch/history rewrite, force push or merge.

## Actual cause and standards

Read-only availability check **2026-10-04T08:31:21.415060219Z**:
root200 / 173,269 bytes; search200 / 12,391 bytes; **2 requests/185,660 bytes**. Root discovery
succeeded, search parsing failed at `link.number("length")`; exit1. No redirect,
retry, 429/503, detail or acquisition. Thus service503 was no longer the blocker.

Exactly one bounded diagnostic search at **2026-10-04T08:37:02.379563Z**:
`https://opds-test.pglaf.org/opds/search?query=Frankenstein`, HTTP200,
application/json, Content-Length 12,391, consumed 12,391; **8 publications**.
Seven EPUB links have integer `length`; ebook10414 instead has open-access
relation, text/html, href `https://www.gutenberg.org/ebooks/10414`, **length absent**.
It is not null or a string. No details/pages/acquisition/retry were requested.
The exact metadata subset is an authored regression fixture; the full response
remains only in /tmp. No book content was requested/logged.

Official sources inspected on the same date:
[OPDS2 §1.1 and §5](https://specs.opds.io/opds-2.0#5-publications),
[OPDS publication schema](https://specs.opds.io/schema/publication.schema.json),
[Readium §2.4 Link Object](https://readium.org/webpub-manifest/#24-the-link-object),
[official Link schema](https://readium.org/webpub-manifest/schema/link.schema.json).
These define optional **size**, integer original bytes, strictly positive in the
schema; they do **not** define length. There is no normative length type/unit/
zero/null rule to impose. Unknown extension keys are permitted, so Gutenberg's
numeric extension and the observed absence are not demonstrated non-conformance.
**INFINILECT's required positive length assumption was incorrect.**
[Exact distinctions and values](GUTENBERG.md).

Narrow catalog policy: all Gutenberg `Publication.resources` are empty. No delivery
URL, size or length is exposed as byte metadata, revision, permission or resource.
Required ID/self/title/author/language and acquisition relation/type/href string
structure stay validated. Optional extensions remain globally bounded strict JSON,
not coerced into numbers. The previous descriptive EPUB mapping is removed because
acquisition is unsupported. Library snapshots already omit resources: no migration.
Changed/hostile delivery locations stay inert; catalog network routes remain exact.
`loadResource` is unchanged: ownership validation + fixed unsupported error,
**zero requests**, including forged/stored EPUB/TEXT refs.

## Frozen corrected live check — one execution

```sh
./gradlew :app:gutenbergSearchCheck --args=Frankenstein \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

UTC **2026-10-04T08:47:29.718739554Z**:

| Request | HTTP | Application-consumed bytes |
| --- | --- | ---: |
| https://opds-test.pglaf.org/opds/ | 200 | 173,269 |
| https://opds-test.pglaf.org/opds/search?query=Frankenstein | 200 | 12,391 |

**Root discovery and parser succeeded; 8 publications, no next token, resources 0.**
First IDs/titles:84—Frankenstein: or, the modern prometheus;
41445/42324—Frankenstein: Or, The Modern Prometheus.
The diagnostic identified one missing HTML length and seven numeric extensions;
all delivery metadata is excluded by the corrected parser, with no resource
exposed. The opt-in check now uses only root+search; no extra detail is necessary.
**2 requests / 185,660 bytes, 0 redirects/retries/429/503/acquisition/downloads/pages.**
BUILD SUCCESSFUL in 12s; 15 actionable tasks: 5 executed / 10 up-to-date. Exit0.
Correction's diagnostic+final source traffic: **3 requests / 198,051 bytes**, all200.
The earlier read-only availability check is separately recorded, not rerun.
No IA/OAPEN/old-OPDS/RDF/mirror request. This is one development-catalog validation,
not a claim of production stability, acquisition or EPUB/TEXT reading support.

## Offline verification after the live check

Targeted command first passed both targets in 1m 3s, 27 actionable tasks
(13 executed / 14 up-to-date), **57 Gutenberg cases per target**:

```sh
./gradlew :app:desktopTest --tests 'org.infinilect.app.gutenberg.*' \
  :app:testAndroidHostTest --tests 'org.infinilect.app.gutenberg.*' \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

Final required full clean command:

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 2m 14s; 146 actionable tasks: 138 executed / 8 up-to-date.**

| Task | Executions | Failures/errors/skips |
| --- | ---: | --- |
| core:jvmTest | 38 | 0/0/0 |
| app:desktopTest | 407 | 0/0/0 |
| core:testAndroidHostTest | 38 | 0/0/0 |
| app:testAndroidHostTest | 394 | 0/0/0 |
| Total | **877** | **0/0/0** |

**459 unique cases** after removing Gradle platform suffixes; **20 new unique**
length/delivery boundary cases in this correction, each run on both app targets.
Android app unit task NO-SOURCE; host tests are JVM simulations, not device tests.
Android lint **0 issues/errors/warnings**. No compiler/Gradle/dependency warnings;
existing SLF4J no-provider/NOP warning only in the opt-in Java check. Original
repositories/proxy/CA/JDK configuration retained; no new Maven 429 workaround.

Coverage includes actual absent HTML length, numeric/absent/null/negative/zero/
fraction/overflow/string/boolean/object/array extensions, optional normative size,
global bounds, malformed required fields, safe identity and inert delivery URLs.
The real SQL Library reopen test now saves metadata from a null-length publication
and discards RAM before reopening; unavailable opens preserve Library/progress,
produce no History/cache, and make no acquisition requests. Existing IA, cache,
large TEXT/UTF-8/BOM/progress, SQLite PRAGMA/diagnostics, source switch/Back tests
remain green. No unrelated implementation or security policy changed.

## APK inspection and human gate

`androidApp/build/outputs/apk/debug/androidApp-debug.apk`, produced by standard
`:androidApp:assembleDebug`; delivery copy
`/workspace/artifacts/INFINILECT-PR11-opds2-length-fix-debug.apk`.
**11,618,414 bytes**, SHA-256:
`cc157b9c1402e2f5ebfe774ec8c6219d272b3c17fb5bfe627b89a8f57b3369d1`.
AAPT2: org.infinilect.app/versionCode1/0.0.1-SNAPSHOT, visible INFINILECT,
compileSdk 37 / targetSdk 37 / minSdk 26. INTERNET + pre-existing app-internal signature
receiver permission only; no storage permission/manifest change. Apksigner verifies
v2; certificate SHA256
`547ad50541c240ad2327e8018619145a6b9d2d8a3954833ca81d46a19f9c8193`,
compared directly with the retained PR10 APK: **same certificate/package**.
No claim of actual install/update or graphical execution. No physical Android or
Desktop UI testing by Codex; [human catalog A–N plan](GUTENBERG.md#human-android-catalog-smoke-plan-not-performed-by-codex).
Keep PR11 Draft pending human Android review. No merge.

## Final adversarial checks

1. Standards mismatch corrected: no required length exists in the cited Link model;
   tests cover all JSON kinds rather than accepting only the live fixture.
2. Malformed length cannot authorize acquisition: all resources/delivery metadata
   are excluded; no numeric coercion or inferred revision.
3. Gutenberg loadResource still makes **zero requests**, proven with MockEngine.
4. Hostile delivery strings remain inert; unsafe identity/self/network routes and
   malformed required structure/global bounds still fail closed.
5. Optional delivery defects do not invalidate otherwise safe catalog metadata.
6. No executable `/ebooks/search.opds/`, RDF/files/harvest/mirror fallback remains.
7. Delivery URL changes alone leave catalog usable; future acquisition needs a
   separately verified fresh authorization/transport contract.

Both diff checks, complete current-main diff inspection, local Markdown links,
Kotlin-source SPDX/license/secret/artifact checks pass. The lone `GPL-3.0-only`
text match is an existing historical statement saying no such declaration exists;
it does not license project code as only. Build-script header policy predates this
correction; original GPL and upstream license texts are unchanged. Core/IA/cache/progress/reader/collections/
SQL/manifests unchanged; zero new dependency/version/schema/permission changes.
No full response, publication content, APK or runtime data committed.

---

The following earlier correction sections preserve historical results; their
08:01 service503 is superseded as the current blocker by the verified fix above.

# PR #11 correction — experimental OPDS2 catalog (2026-10-04 UTC)

Previous reviewed HEAD: `2f98e9a5a528e51196fcc0e8332e613c5f81add5`.
Fetch confirmed base/current main `a5caaadfb859160f742740d23d1869bd78db0f38`;
branch `feature/gutenberg-full-integration`, unchanged history, open PR11.

The exact user-supplied email recommends the OPDS development endpoint and
explicitly discourages unmaintained OPDS0.9; **it did not answer acquisition**.
Corrected scope: experimental OPDS2 root/search/details + metadata-only Library;
no Gutenberg TEXT acquisition. IA is default and retains the existing reader.
Legacy XML/RDF/direct-file code is removed, no fallback. Core, IA/cache/progress/
collections/reader implementations, SQL schema, manifests/permissions unchanged.
No new dependency/version: existing shared serialization-json reused; obsolete
host-only kXML declaration removed. [Categorized evidence](GUTENBERG.md),
[corrected unmerged ADR0018](adr/0018-gutenberg-catalog-acquisition.md).

## Bounded investigation: eight requests, no book download

| UTC timestamp 2026-10-04 | Official URL | HTTP | Consumed bytes |
| --- | --- | --- | ---: |
| 07:39:42.033986 | https://opds-test.pglaf.org/opds/ | 200 | 131,073 |
| 07:40:33.163034 | https://www.gutenberg.org/ebooks/offline_catalogs.html | 200 | 17,895 |
| 07:40:33.935697 | https://www.gutenberg.org/policy/robot_access.html | 200 | 9,253 |
| 07:40:34.591606 | https://www.gutenberg.org/policy/terms_of_use.html | 200 | 12,086 |
| 07:41:12.006298 | https://opds-test.pglaf.org/opds/search?query=Frankenstein | 200 | 12,391 |
| 07:41:12.242820 | https://opds-test.pglaf.org/opds/publications?id=84 | 200 | 5,823 |
| 07:41:12.559336 | https://www.gutenberg.org/MIRRORS.ALL | 200 | 2,922 |
| 07:41:50.213206 | https://opds-test.pglaf.org/opds/search?query=shakespeare | 200 | 32,951 |

**8 requests /224,394 consumed bytes**, no redirects/retries/429/503, book
content, next page, mirror request, harvest, catalog dump or endpoint enumeration.
The initial root probe deliberately stopped at 128KiB+1; declared172,863 bytes,
truncated JSON was not claimed parsed. Its complete initial links showed the exact
search template and item self link. Final frozen-code diagnostic below uses the
1MiB policy but received HTTP503 before root-body consumption/parsing. Research bodies remain untracked
in /tmp, never fixture dumps committed to Git.

Search returns metadata.currentPage/itemsPerPage25/numberOfItems; Shakespeare
advertises next page2/last23. Item84 current details: open-access EPUB3/length474,733,
no TEXT link, no distinct byte revision/license; USA rights are prose in description.
No production-preview endpoint was linked in inspected material. Development
service evidence is **not production validation**. Delivery redirects untested
because no acquisition was performed. UA public issues-page contact unchanged.

## Corrective implementation checks

Initial default-sandbox Gradle startup failed before compilation because its local
lock service could not determine a usable wildcard IP. Execution with sandbox
approval enabled that normal local service; no project/repository workaround.
A first targeted compile found a mistaken test-only close() on the stateless progress
store; removed. The next targeted run caught a fixture query-encoding error for
special characters; fixture now uses Ktor URLBuilder. These were corrected before
final verification; no production policy relaxed. Existing non-Gutenberg tests
are retained; obsolete XML/RDF-specific tests replaced by current OPDS2 coverage.

## Final frozen-code clean verification

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 2m10s**, 146 actionable tasks:138executed/8up-to-date.

| Task | Executions | Failures/errors/skips |
| --- | ---: | --- |
| core:jvmTest | 38 | 0/0/0 |
| app:desktopTest | 387 | 0/0/0 |
| core:testAndroidHostTest | 38 | 0/0/0 |
| app:testAndroidHostTest | 374 | 0/0/0 |
| Total | **837** | **0/0/0** |

**439 unique cases;37 new unique preview policy/source/integration cases**.
The obsolete Gutenberg XML/RDF-acquisition tests are replaced rather than forcing
preview JSON into an old contract. All non-Gutenberg tests remain: IA security/
fresh metadata/null revisions, cache, TEXT large/UTF-8/BOM/EOF/cancellation,
progress/durable Android-compatible save, SQLDelight/PRAGMA correction, catalog
Library, History, retained search, Android Back/lifecycle. Android host is a
host JVM simulation of shared code, not an Android OS/device execution.
androidApp:testDebugUnitTest is NO-SOURCE, not skipped device coverage.
Android lint: **0 issues/errors/warnings**. No Kotlin/Gradle/compiler/dependency
warnings in clean build; existing SLF4J no-provider/NOP warning in opt-in Java check.

New coverage includes schema root/search/details, encoded queries, next-page
bounds, numeric identity/canonical URLs, unknown MIME/TEXT non-permission,
acquisition rel validation, hostile/cross-item URLs, duplicate/escaped JSON keys,
UTF-8/depth/node/string/array/response bounds, wrong MIME/encoding/length,
HTTP503/429/redirect fail-closed, cancellation, source-close active work,
serialized calls, real SQL database recreate/metadata Library durability,
unsupported saved opens preserving entries/history/progress/cache, no forged
TEXT fallback, and search results retained when unavailable opening is ignored.

`git diff --check`, full current-main diff/protected paths, Markdown local links,
SPDX and artifact/secret checks pass. No new versions/dependencies, database
schema, permission/manifest, publication content, source-controlled runtime files
or whole-book buffers are introduced. Removed only host-test kXML declaration;
third-party license text remains untouched. No wildcard or hidden legacy fallback.

## One final development-service check: externally blocked

```sh
./gradlew :app:gutenbergSearchCheck --args=Frankenstein \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

At **2026-10-04T08:01:10.497855143Z**, root
`https://opds-test.pglaf.org/opds/` returned **HTTP503**.
**One request, zero application-consumed body bytes**, no redirect, retry,
search/detail, acquisition or code-point/window output. Diagnostic task exited1;
this opt-in failure is not a normal-test/build failure. **Stopped on503**, no
service retry/downgrade/bypass/fallback. Corrected live catalog chain is not proven.
Earlier bounded research observed200/schema/search/details, not production uptime.
No genuine development-service book download was performed by this correction.
Total correction research+final check: **9requests /224,394 consumed bytes**.

## Corrected APK and environment limits

Standard `:androidApp:assembleDebug` output:
`androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Delivery copy: `/workspace/artifacts/INFINILECT-PR11-opds2-catalog-debug.apk`.
**11,618,414 bytes**; SHA-256:
`c3b81fbe54fb05ec9824f8375097f37a026bd3cc8993cf757192b892d4e0f906`.
Package org.infinilect.app, visible INFINILECT, versionCode1/0.0.1-SNAPSHOT;
compile/target37, min26. INTERNET and existing app-local signature receiver
permission only; no storage permission. Apksigner verifies; certificate SHA-256
`547ad50541c240ad2327e8018619145a6b9d2d8a3954833ca81d46a19f9c8193`,
matching merged PR10 debug signature. Package/signature support update in place;
actual install/update retention remains human testing.
JDK21.0.12.1+1/Kotlin2.4.20/Compose1.12.1/Gradle9.7.1/AGP9.3.1 unchanged.

No authorized adb device/emulator and no DISPLAY/WAYLAND. **No physical Android
or graphical Desktop smoke test**. Human PR10 large-TEXT evidence remains attributed
as human-reported only. New **A–O** plan in [GUTENBERG.md](GUTENBERG.md) checks
catalog-only Gutenberg and IA reading/storage regressions, not nonexistent Gutenberg
reading. PR remains open as draft for human review/preview-availability evidence;
no merge, rebase, squash or force push.


## Historical previous-head evidence (superseded implementation)

The following earlier PR11 logs/results are retained as historical observations
only. Its RDF transfers returning200 did **not** establish current supported
acquisition policy. Its old APK/test totals/interfaces/A–T plan are superseded by
the corrected section above and the current A–O catalog/IA-regression plan.
No earlier build/live/device claim should be attributed to corrected code.

# PR #11 — Project Gutenberg TEXT (2026-10-04 UTC)

Base verified after fetch/pull: `a5caaadfb859160f742740d23d1869bd78db0f38`, merged
PR #10. Fresh branch `feature/gutenberg-full-integration`; no prior branch/history
rewrite. Refetch before delivery found main unchanged.

## Final clean verification

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 2m 23s**, 146 actionable tasks: 138 executed, 8 up-to-date.
JDK 21.0.12.1+1; Kotlin/compiler 2.4.20, Compose 1.12.1, Gradle 9.7.1,
AGP 9.3.1; SDK 37/min 26. No dependency/version/schema/manifest changes.

| Task | Executions | Failures/errors/skips |
| --- | ---: | --- |
| core:jvmTest | 38 | 0 / 0 / 0 |
| app:desktopTest | 411 | 0 / 0 / 0 |
| core:testAndroidHostTest | 38 | 0 / 0 / 0 |
| app:testAndroidHostTest | 391 | 0 / 0 / 0 |
| Total | **878** | **0 / 0 / 0** |

**475 unique cases; 42 new unique** policy/source/session cases shared on both
app targets. Android host uses real kXML tokenization behind the Android adapter;
it is not an Android-device execution. androidApp:testDebugUnitTest is NO-SOURCE,
not a skipped device test. Lint XML: **0 issues/errors/warnings**. No Kotlin/Gradle
compiler/dependency warnings in the final clean build. The opt-in live Java task
emits the existing SLF4J no-provider/NOP diagnostic; no logging dependency added.

Focused commands during implementation:

```sh
./gradlew :app:desktopTest --tests 'org.infinilect.app.gutenberg.*' \
  :app:testAndroidHostTest --tests 'org.infinilect.app.gutenberg.*' \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew :app:desktopTest --tests '*GutenbergReadingIntegrationTest*' \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

Intermediate runs caught/fixed compile ambiguity/fixture quoting, redundant !!
warnings, a close race where source-owned buffered content needed synchronous
invalidation, and an old Android factory assertion expecting Gutenberg to remain
search-only. A host test initially waited only for Results and could hang on other
terminal states; it now observes Error/Empty as well. No regression case was removed.
Final full suite, including Archive/source security, cache, UTF-8/BOM/limits,
SQLDelight/Android PRAGMA correction and progress tests, passes.

New tests exercise real SQLDelight/file store recreation with **all recent RAM
state discarded**, Library saved from search, fresh RDF before saved opens and
before each acquisition, null-revision cache bypass, >512 KiB indexed reading,
History success/failure/cancellation, old progress retention, Back/results and
post-preparation cancellation cleanup. IDs/URLs/MIME/size/XML adversarial cases
include raw escapes/traversal, confusable hosts, userinfo/ports, loops, malformed
or contradictory metadata, short/extra bytes, timeout and close/cancellation.

## Live verification (not part of build/test/check)

Initial diagnostic at **2026-10-04T00:21:19.384139225Z**:

```sh
./gradlew :app:gutenbergTextReadingCheck --args='84 1342' \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

It queried `id:84`: **one HTTP 200**, 3,466 bytes, no usable book results, and
stopped without metadata/acquisition. This was a diagnostic query assumption,
not a 429/503 or acquisition failure; it is not claimed successful. The check was
corrected to explicit ebook-ID/title-query pairs, without changing product search
or adding automatic retry/fallback. This adapter does not promise ID-query syntax.

One corrected execution at **2026-10-04T00:23:25.048871872Z**:

```sh
./gradlew :app:gutenbergTextReadingCheck --args='84:Frankenstein 1342:Pride' \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**Successful shared ReadingSession search → getPublication → fresh RDF resolution
→ ResourceLoader → ResourceContent → complete strict indexed UTF-8 → Ready → Back**.
No text logged. Both resources TEXT, `text/plain; charset=utf-8`, revision=null.

| Request (in order) | Status | Consumed bytes |
| --- | ---: | ---: |
| `/ebooks/search.opds/?query=Frankenstein` | 200 | 29,425 |
| `/cache/epub/84/pg84.rdf` (details) | 200 | 19,046 |
| same RDF (fresh acquisition) | 200 | 19,046 |
| fresh RDF `/files/84/84-0.txt` | 200 | **421,633** |
| `/ebooks/search.opds/?query=Pride` | 200 | 62,567 |
| `/cache/epub/1342/pg1342.rdf` (details) | 200 | 18,220 |
| same RDF (fresh acquisition) | 200 | 18,220 |
| fresh RDF `/files/1342/1342-0.txt` | 200 | **738,046** |

Corrected run: **8 requests, 1,326,203 bytes, 0 redirects/retries/403/429/503**.
Ebook 84: 419,434 code points/397 windows; ebook 1342: 728,846/690 windows.
Content-Length matched fresh RDF; full EOF/UTF-8 passed, handles closed; Back
retained query/results. Total live check attempts: **9 requests, 1,329,669 bytes**.
The large book exceeds the former 512 KiB ceiling without whole-book RAM storage.

Separate official research: 10 requests (9 GET 200 + one HEAD 302), 135,656 consumed
metadata/documentation bytes; no book body. Robot/terms/offline documentation,
one bounded harvest page (not crawled), RDF IDs 11/1342/1661/84 and ebook 11 OPDS
were inspected. The ebook-11 variant HEAD returned a malformed HTTP Location and
was **not followed or repaired**. This is recorded service/proxy-path observation,
not a universal storage-host guarantee. Research + live total: **19 requests,
1,465,325 bytes**; only 84/1342 book bodies were downloaded, once each.

## APK and inspection

Task: `:androidApp:assembleDebug` (standard debug variant).
Path: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Delivery copy outside Git: `/workspace/artifacts/INFINILECT-PR11-gutenberg-text-debug.apk`.
Size **11,634,798 bytes**; SHA-256:

```text
c0e7126653148829aea6168c78db7f4ad093269c0d8a24f3ea2313d5629e7fca
```

Aapt2: `org.infinilect.app`, INFINILECT, versionCode 1/0.0.1-SNAPSHOT;
compile/target 37, min 26. Only INTERNET and the pre-existing app-local
signature-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; no storage permissions.
Apksigner verifies the debug APK and certificate matches PR #10:
`547ad50541c240ad2327e8018619145a6b9d2d8a3954833ca81d46a19f9c8193`.
Package/signature/version therefore support in-place debug update; installation
and preservation on a real device still need the human smoke test.

No authorized adb device/emulator, DISPLAY or WAYLAND_DISPLAY. **No Codex physical
Android or visual Desktop test performed**. User separately reports PR #10 large
TEXT Android opens, bidirectional scrolling, Back/reopen/process-restart progress,
and brief extreme-scroll loading that promptly resolves. This is human-reported
physical evidence, not Codex execution and not Gutenberg device verification.
[Exact pending A–T plan](GUTENBERG.md#verification-and-physical-android-at-plan).

Diff whitespace, local Markdown links, SPDX, dependency inventory, runtime-artifact/
secret inspection and protected-path diff review passed. Core, reader/progress,
collections/cache/Archive implementations, SQL schema, dependencies and manifest
remain unchanged. No APK/RDF dump/book/progress/database/cache file is committed.
Limits: explicit UTF-8, exact known size ≤16 MiB, fresh extent consistency, exact
www.gutenberg.org/item-scoped destinations only; no mirrors, HTTP downgrade,
legacy charset guessing or malformed redirect repair. v0.0.1 is not declared complete.

---

# Verification


## PR #10 — bounded large TEXT reading — 2026-10-04

Fetched current origin/main and verified merged PR #9 before creating
`feature/streaming-large-text-reader`. Exact base:
**6adf9cf85495da88bfab630bf5009c285eace029**. Refetched before delivery;
main remained at that SHA. No previous branch/history was reused or rewritten.

Final clean command (existing environment JDK/SDK/Gradle home, no repository changes):

```sh
./gradlew clean :core:jvmTest :app:desktopTest :core:build :app:build \
  :desktopApp:build :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

**BUILD SUCCESSFUL in 2m 7s; 146 actionable tasks: 138 executed, 8 up-to-date.**
Final code was frozen before this run. Targeted Desktop/Android regressions and
intermediate clean builds also ran during implementation; after the final cleanup
review the full clean command above was rerun. No Maven 429, permanent repository
workaround, dependency/version update or schema migration was needed.

| Task | Test executions | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| :core:jvmTest | 38 | 0 | 0 | 0 |
| :app:desktopTest | 369 | 0 | 0 | 0 |
| :core:testAndroidHostTest | 38 | 0 | 0 | 0 |
| :app:testAndroidHostTest | 349 | 0 | 0 | 0 |
| Total | **794** | **0** | **0** | **0** |

**433 unique class/method cases**, deduplicating target suffixes; **37 new cases**
(34 FileTextDocumentTest + 3 LargeTextSessionTest), executed on both app targets.
`:androidApp:testDebugUnitTest` is **NO-SOURCE**, not a device test/skipped test.
Android host tests run a host JVM, not Android filesystem/Compose instrumentation.
Lint report: **zero issues (zero errors/warnings)**. No Kotlin/Gradle/dependency
warnings in the final clean build. Resource/no-source task SKIPPED markers are
Gradle task statuses, not skipped test cases. The opt-in live task separately
emitted existing SLF4J no-provider/NOP warnings; no logging dependency was added.

Coverage includes full 16 MiB generated streaming fixture (no giant fixture String),
max read buffer/request and primitive-index bounds, eight-window cache bounds,
small/BOM-only/interior BOM, every UTF-8 chunk boundary, supplementary characters,
malformed encodings, short/extra/changing sizes, one-byte excess probe, unknown/
over-limit rejection before reading, zero/NUL/whitespace, cancellation during
acquisition/ongoing indexing/progress lookup, close on terminal paths, beginning/
middle/end/backward indexed navigation, old locator compatibility, changed-length
approximation, durable .progress record + fresh persistence/store owner restore,
normal temporary cleanup, owner close, stale/active owner distinction, unrelated
files, namespace/OS-parent symlinks, backing truncation, cache-only eviction and
same-owner re-preparation, private Android/per-user Desktop paths, saved-Library
source re-resolution, successful History, retained Search/Back and fresh null-revision
reacquisition. Existing source/cache/SQLDelight/Android-PRAGMA/progress/catalog tests
remain present and green. Test API assertions now use bounded windows/global code
points; previous regression cases were retained, including full-limit/overflow.

An early new durable-reopen test used the store's real IO dispatcher beneath a
virtual timeout: corrected the test to inject its scheduler, assert the committed
.progress file and discard all RAM state before restoration. A diagnostic-message
assertion also accidentally matched the fixed phrase 'private temporary storage';
it now checks the injected private detail. No persistence implementation was changed.

### One real large-TEXT check

Metadata-only discovery made **six official requests**, all HTTP 200, consuming
**25,486 bytes**. It selected Dutch government item `stcrt-2015-37219`, whose
metadata explicitly declares `http://creativecommons.org/publicdomain/zero/1.0/`,
public/non-lending text, with `stcrt-2015-37219_djvu.txt`, **589,899 bytes**. No large
PDF/dump was acquired. This exceeds the old 524,288-byte TEXT limit.

Exactly once, at **2026-10-03T23:03:09.378684620Z** (2026-10-04 in Europe/Madrid):

```sh
./gradlew :app:internetArchiveTextReadingCheck --args=stcrt-2015-37219 \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

| Request | Status | Application-consumed bytes |
|---|---:|---:|
| Official Advanced Search | 200 | 635 |
| Item metadata / getPublication | 200 | 5,654 |
| Fresh acquisition metadata | 200 | 5,654 |
| Official /download item/file permalink | 302 | 0 |
| Fresh-metadata-authorized dn760106.eu.archive.org/0/items/item/file | 200 | 589,899 |

**Five acquisition-check requests, one validated redirect, 601,842 bytes total**;
full resource consumed/closed, **589,204 Unicode code points, 551 indexed windows**,
strict UTF-8 Ready, Back retained query/results. No content printed, no retries,
403 or 429. Discovery plus check: **11 requests, 627,328 consumed bytes**. The
check used the real shared session/controller/preparation path, not a UI fixture.
Cleanup/private-path review refinements afterward were tested offline; the live
check was not repeated. Live source evidence does not imply graphical scrolling
or physical-device validation.

### APK and environmental limits

Task: `:androidApp:assembleDebug`.
Path: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Size: **11,618,414 bytes**.
SHA-256: **a895459bd1933e336788c8ea26e2e0fdd2ae9e1dc278a54849d12da95476099d**.

APK inspected with SDK aapt2/apksigner: org.infinilect.app, visible name INFINILECT,
compile/target SDK37, min SDK26, HTTPS/cleartext policy unchanged. Permissions
match PR #9: INTERNET and existing app-local signature-scoped AndroidX receiver
permission; no storage permission. Debug signature is verified and its SHA-256
certificate matches the PR #9 APK, supporting an in-place debug update. No release
signing/distribution, manifest change, permission change or generated APK commit.

No DISPLAY/WAYLAND_DISPLAY; authorized `adb devices -l` returned an empty list.
**UI compiled, but graphical Desktop/physical Android smoke tests were not performed.**
The user's prior PR #9 device results are distinct from this new slice. Execute
[the exact physical A–J plan](TEXT_READER.md#verification-and-physical-plan),
including cache-only deletion and complete process restart. New private-file
provider behavior, lazy layout/restoration and device scrolling need that review.

`git diff --check`, full `git diff origin/main...HEAD --check`, local Markdown link
check and complete diff inspection passed before delivery. Core/source/security/
resource-cache/progress-store/schema/dependency/manifest diffs are empty. New code
has GPL-3.0-or-later SPDX headers. No secrets or generated database/progress/cache/
reader backing/APK/build artifacts are versioned. This is not v0.0.1 completion;
known costs/limits are in [TEXT_READER.md](TEXT_READER.md) and
[ADR 0017](adr/0017-indexed-text-document.md). PR is left open, without merge.

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
