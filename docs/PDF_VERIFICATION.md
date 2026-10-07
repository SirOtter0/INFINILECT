# PR #20 PDF verification

Base: `2eb55715131b0628be526c5e4644bd6a9f68a417`, verified current main/PR #19 merge.
Branch: `feature/bounded-local-pdf`. [PR #20](https://github.com/SirOtter0/INFINILECT/pull/20)
remains Draft; no merge is authorized or performed.

## Environment and initial checks

This fresh workspace had no Gradle distribution/cache, Android SDK, full JDK or
compatible external signing keystore. Local wrapper execution with a writable
`GRADLE_USER_HOME` failed downloading Gradle with `java.net.SocketException:
Operation not permitted`. No test ran in that local attempt. The exact base tree
and commit were recovered through the authenticated GitHub connector and verified
against their Git object hashes. No access prompt is being left pending.

Verification therefore runs on GitHub Actions, using pinned current upstream
actions, JDK 21 and the project's documented `platforms;android-37.0` / build-tools
36.0.0. Initial setup-only attempts failed on an obsolete `tools` package and then
the old `android-37` package spelling; both failed before any tests ran.

[Initial code verification](https://github.com/SirOtter0/INFINILECT/actions/runs/37529829169)
passed focused PDF contracts/adapters, import/reader/session and existing import
tests. Its clean matrix found one existing mixed-format TEXT timeout regression
on Desktop and Android host. The new PDF decision incorrectly bypassed the deadline
when any PDF representation existed. The correction exempts only the selected,
enabled PDF route and keeps non-PDF acquisition/preparation inside the original
deadline. Corrected focused and frozen-code results are recorded below;
initial failures are not claimed as passes.

[Corrected focused run](https://github.com/SirOtter0/INFINILECT/actions/runs/37531087082)
on `63e9e2d9a84ac8330522f1c55f8133f17596a7fc` passed all three Gradle invocations:
core PdfDocument tests; Desktop engine/preparer tests; and import/reader/session,
existing local-import and OpenPublicationController tests. The clean matrix was
deliberately gated out until this focused verification passed.

## Review before the frozen-code matrix

The resource review checked descriptor ownership on Android constructor failure
and renderer retirement, one-page/finally closure, PDDocument/input ownership,
intermediate bitmap/image cleanup, private spools and close/return-boundary races.
Additional host cases cover undelivered prepared documents and serialized renders.
Fatal VM errors are not mislabeled as ordinary PDF failures; process exhaustion
remains possible. Missing optional codec checks include resource construction,
forms/patterns, inline images and masks, and fail conservatively.

Bounds use validated geometry and Long products before owned output allocation;
PDFBox allocation arithmetic is checked before renderImage. The review traced
cancelled/stale results through controller and adapter ownership, inspected import
publication after full EOF/digest verification and engine inspection, and confirmed
original paths/URIs remain absent from persisted records. Static boundary checks
found no engine/Bitmap/BufferedImage/descriptor/Java2D/Skia imports in PDF core,
common reader/controller or the owned preparation interface. Android rendering uses
only the API-21 PdfRenderer surface available on API 26. Existing mixed-format TEXT
deadline behavior is now covered by the original passing regression case.

A subsequent presentation review captured the request ticket as an immutable value
before suspending image conversion. Reading the delegated current state afterward
could otherwise stamp an obsolete bitmap with a newer request's ticket. The final
matrix is repeated on this correction; the earlier frozen artifact is superseded.

Modified-document local Markdown links: 140 checked, zero missing at this review.
`git diff --check` passed. Android physical and Desktop graphical checks remain pending.

## Verification scope

Original generated PDF fixtures have real page trees, streams, fonts and byte-offset
xref/trailers. Host tests exercise PDFBox rendering, malformed/truncated/fake input,
zero/pathological geometry/page count, password and empty-password encryption,
missing optional image codecs, invalid indices, output limits, repeated close/open,
EOF integrity, serialized operations, cancellation at IO return and blocking render,
obsolete reader delivery, durable import/deduplication/source deletion/cache deletion,
new source/progress owners, Library-before-open/History-after-render, progress restore
and a recreation index overriding an older asynchronous progress commit.

Android host adapter tests use the infrastructure seam with an owned fake engine;
they **do not execute Android framework PdfRenderer on a physical device**.
PDFBox/Java2D host rendering does not establish graphical Windows/Linux/niri
acceptance. [Pending physical/graphical checklists](PDF_READER.md).

## Signing and final matrix

No compatible key is available here. CI disables debug signing with an external
temporary init script; it never generates a replacement key and never changes
production signing configuration. Any produced APK is unsigned, unsuitable for
installation/update acceptance. The expected existing certificate remains
`547ad50541c240ad2327e8018619145a6b9d2d8a3954833ca81d46a19f9c8193`.

The frozen-code matrix is gated by a `[verify matrix]` commit after focused tests
and the ownership/bounds/boundary review stabilize. It includes the existing
core/app Desktop and Android host checks, build, Android unit/lint/unsigned APK,
and Desktop distributable. Exact final results and artifact identity follow.

Exact frozen-code command (the external init script only disables CI signing):

```sh
./gradlew -I /tmp/pdf20-unsigned.gradle clean \
  :core:jvmTest :app:desktopTest :core:build :app:build :desktopApp:build \
  :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  :desktopApp:createDistributable \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

## Original vertical-slice automated result and artifact

[Final frozen-code run](https://github.com/SirOtter0/INFINILECT/actions/runs/37532146147)
completed successfully on `dfc4d34feb29cf2c356763a07d41552756f6fd6f`.
All three focused Gradle invocations passed, followed by the clean matrix:
`BUILD SUCCESSFUL in 4m 12s`; 149 actionable tasks, 145 executed and 4 up-to-date.
Evidence commit `59db9c1647dc264032e8149f3314e3ad79a4c8a2` changed only this
verification document. The later focused progress review is recorded separately
below; these full-matrix counts describe the original vertical slice.

| XML suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| core / jvmTest | 58 | 0 | 0 | 0 |
| core / testAndroidHostTest | 58 | 0 | 0 | 0 |
| app / desktopTest | 815 | 0 | 0 | 0 |
| app / testAndroidHostTest | 782 | 0 | 0 | 0 |
| Total executions | 1,713 | 0 | 0 | 0 |

These include 25 unique new PDF cases / 42 platform test executions. XML counts
were independently checked from the downloaded artifact. Existing TXT/EPUB/CBZ
and mixed-format timeout suites passed. Android lint, core/app/Desktop builds,
Android debug assembly and Linux Desktop distributable completed successfully.
`androidApp:testDebugUnitTest` and `desktopApp:test` are NO-SOURCE, not additional
executed tests. There are no device/instrumentation or graphical acceptance results.
Non-fatal warnings remain: two unnecessary non-null assertions in FilePdfPreparer
(reported on both compilation targets) and SDK Manager CLI deprecation.

Artifact `pdf-verification`, ID `11445132774`, contains exact XML results,
`pdf-verification.json` and the unsigned debug APK (seven-day retention):

- APK: `androidApp/build/outputs/apk/debug/androidApp-debug-unsigned.apk`.
- Workspace copy: `/workspace/INFINILECT/androidApp/build/outputs/apk/debug/androidApp-debug-unsigned.apk`.
- Size: **12,040,538 bytes** (11.48 MiB).
- SHA-256: `915a90e68513e881469e069058d8e7e3221a3332571b240759b5dcc6525f4d95`.
- Signing certificate SHA-256: **unavailable — APK is unsigned**. Downloaded APK
  has no v1 signature entries or APK signing block. The compatible private key is
  unavailable; no replacement identity was generated. Rebuild with the established
  external key before physical installation/update acceptance.
- Artifact ZIP SHA-256: `e29b810633d299d413f4af35e9c306864f45966302597b841e9a70e38a4b1638`.

Artifact ZIP digest, APK byte count and APK digest were verified after download.
Android physical acceptance and Windows/Linux/Wayland/niri graphical acceptance
remain pending against the [documented checklists](PDF_READER.md).


## Focused review follow-up: successful-page progress and workflow removal

[Focused verification](https://github.com/SirOtter0/INFINILECT/actions/runs/37536916524)
passed on production/test HEAD `1d5d0c0c444fd1f3604ab0262424058899a6fd62`.
The final follow-up commit removes the temporary workflow and updates documentation;
production code and tests remain exactly those verified in this run.

Requested/loading index is separate from the last successfully rendered index.
Only a current, non-cancelled render publishes Ready and submits its semantic
position. Failed/obsolete/cancelled targets and close/flush during an in-flight
render cannot advance progress. Initial normal opening does not write a reset.
Compose recreation captures the last successful index, retaining restoration over
an older asynchronous writer commit. No engine timeout, boundary, dependency,
resource limit or non-PDF production behavior changed.

Focused regression coverage includes successful navigation after a gated render,
CODEC/RENDER failure preserving progress and reopening the previous successful page,
late obsolete delivery, closing during a noncooperative render, failed initial
recreation, and an older write still in flight during recreation. ApplicationSession
coverage recreates from a loading target and reopens after a later failed render.
The real-file PDF import/restart test now waits for Ready before closing.

| Focused task | Executions | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| app / desktopTest | 77 | 0 | 0 | 0 |
| app / testAndroidHostTest | 75 | 0 | 0 | 0 |
| Total | 152 | 0 | 0 | 0 |

Desktop suites: PdfReaderController 7, PdfSession 3, ApplicationSession 14,
PageApplicationSession 5, OpenPublicationController 30, ProgressLifecycle 16,
DesktopPdfImport 2. Android host runs the same suites except DesktopPdfImport.
The ApplicationSession wildcard also selects PageApplicationSession; those existing
page-reader cases passed unchanged. Counts were independently checked from XML.
Gradle invocations: Desktop `BUILD SUCCESSFUL in 2m 35s`; Android host
`BUILD SUCCESSFUL in 1m 27s`; updated unsigned APK assembly `BUILD SUCCESSFUL in
1m 21s`. No expensive full clean matrix was rerun: engines, dependencies, limits
and non-PDF production code are unchanged, and the affected behavior is covered
by these focused tests plus Android compilation/assembly. Physical/graphical
acceptance remains pending.

Exact focused commands (external init script disables CI signing only):

```sh
./gradlew -I /tmp/pdf20-unsigned.gradle :app:desktopTest --tests '*PdfReaderControllerTest' --tests '*PdfSessionTest' --tests '*ApplicationSessionTest' --tests '*OpenPublicationControllerTest' --tests '*ProgressLifecycleTest' --tests '*DesktopPdfImportTest' --no-daemon --console=plain --max-workers=2
./gradlew -I /tmp/pdf20-unsigned.gradle :app:testAndroidHostTest --tests '*PdfReaderControllerTest' --tests '*PdfSessionTest' --tests '*ApplicationSessionTest' --tests '*OpenPublicationControllerTest' --tests '*ProgressLifecycleTest' --no-daemon --console=plain --max-workers=2
```

The requested updated test APK was rebuilt after both focused suites passed:

```sh
./gradlew -I /tmp/pdf20-unsigned.gradle :androidApp:assembleDebug --no-daemon --console=plain --max-workers=2
```

The branch-only `.github/workflows/pdf-verification.yml` was used one final time
for this evidence and is **deleted from the final PR tree**. There is no replacement
per-feature workflow or repository-wide CI redesign. Permanent evidence is preserved
here and in PR #20; linked run logs remain available. Artifact `pdf20-review-evidence`
(ID `11446812978`, seven-day retention) contains XML, counts, the updated unsigned
APK and the public apksigner JAR from Android SDK build-tools 36.0.0. No signing
inputs were uploaded to GitHub/CI or copied into the repository.

Artifact ZIP SHA-256: `8e0f35b8c218f1c932a4b6d945145f58b46d8c0bfdff1fa2daa660dc2f48346d`.
Updated unsigned APK SHA-256: `58714ceccf05c79f99363b1d1e3a8b2bc8cdbf8c86be9f07ef8142e0dcf81103`.
Both digests and extracted file sizes were verified after download.

### Updated development-signed test APK

The separately supplied development inputs were consumed locally, outside the
repository. Alias certificate SHA-256 was verified before signing and again from
the signed APK; both match the user-required fingerprint. No replacement key or
release identity was generated. This identity is for development/testing only.

- APK: `androidApp/build/outputs/apk/debug/androidApp-debug-development.apk`.
- Size: **12,062,727 bytes**.
- SHA-256: `b48b7b543446fee93d739deed2f2f8034fcb650e284913fbd5ad658ae97daba1`.
- Signing certificate SHA-256: `95C708E6CFEE94BC13ECF34D2A38CF4F1DC5EAB185133F8E139629DD0BE470FE`.
- Official SDK apksigner verification: **PASS for minSdk 26**, v2 and v3 signatures.
  v1/v3.1/v4 are absent; v2 covers supported API 26 devices.
- This APK contains the reviewed progress correction, unlike the original unsigned
  vertical-slice artifact recorded above. Device installation/update compatibility
  still depends on the installed app's certificate; no physical acceptance is claimed.

Final tracked-file audit checks for keystores/password files, private-key headers,
raw/Base64 attached key/password material and attachment/secret paths; no matches.
The key, password and local signing scripts/tools are not tracked. TXT/EPUB/CBZ
production behavior is intentionally unchanged. PR #20 remains Draft and unmerged.


## Search layout follow-up and user-reported Android acceptance (2026-10-07)

The user reported physical Android acceptance of local PDF import, rendering,
multi-page navigation, landscape page rendering and progress/reopen behavior.
These are user-reported device results, not additional host tests or a claim that
all device/security checklist cases or API 26 devices have been exercised.
Previous/Next-only PDF navigation and automatic Library insertion after import
remain intentional and unchanged.

A separate Search regression was reported on smaller Android viewports: fixed
controls/source rows/messages/footer content could exhaust the available height,
leaving the weighted result list with no usable viewport. Idle/loading/error
content outside that list could also extend below the non-scrollable screen.

The surgical fix gives SearchScreen an explicit weight in App's existing Column.
Inside its bounded area, controls form a separately scrolling header capped at
half the available content height; the existing LazyColumn receives the remaining
space. Status, empty/error/loading messages, Library feedback, pagination and the
notice footer are lazy items in that same viewport, so none is stranded below
fixed content. Result keys, callbacks, page-generation viewport ownership and
source/session navigation are retained. The header and list are scroll siblings,
not nested scroll containers. No whole-screen verticalScroll was added.

Android MainActivity already handles safe drawing/IME padding and adjustResize;
that code is unchanged and no duplicate IME padding was added. A shorter available
height simply remeasures the bounded header/result allocation. Controls can be
reached by scrolling the header, while the result viewport remains independently
scrollable. No PDF reader, import/acquisition, engine, limits or dependency changed.
The entire App reader/navigation branch is byte-identical to the pre-follow-up
version; Open, Library and Next page callbacks are unchanged.

### Focused verification

[Successful focused run](https://github.com/SirOtter0/INFINILECT/actions/runs/37576657696)
verified production/test HEAD `83d3f4dc74898b826063f2cef5660d77b883ce5d`.
The final evidence commit only removes the temporary workflow and updates this
verification document; production and tests remain exactly those verified.
An [initial attempt](https://github.com/SirOtter0/INFINILECT/actions/runs/37576381829)
failed compilation before tests because the Column DSL hid BoxWithConstraints'
implicit maxHeight receiver. Capturing that value in its constraint scope fixed
compilation; no initial failure is claimed as a pass.

| Focused task | Executions | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| app / desktopTest | 48 | 0 | 0 | 0 |
| app / testAndroidHostTest | 44 | 0 | 0 | 0 |
| Total | 92 | 0 | 0 | 0 |

Four new SearchLayout tests render and measure the actual Compose UI through the
existing Desktop ImageComposeScene/Skiko runtime; no UI-test dependency was added.
They exercise long source headers at mobile heights 420/280 and enlarged font
scale 1.6, a reduced-height keyboard scenario, a wide Desktop viewport, first/last
publication reachability, scroll-owner independence, header controls reachability,
Idle/Loading/Empty/Error feedback, pagination, viewport retention/reset and the
actual App import controls. Assertions use positive/bounded visibility, logical
indices and semantics, not fixed expected pixel coordinates. These are headless
layout tests, not physical Android touch/IME or native Desktop-window acceptance.

Existing SearchController (8), SearchResultsViewport (7), ResultKey (1),
ApplicationSession (14), PageApplicationSession (5), LocalImportSession (6) and
PdfSession (3) cases passed on both targets; Desktop additionally ran the four
layout cases. This includes automatic Library-before-open import semantics,
reader Back/search viewport preservation and PDF opening/progress behavior.
XML totals and artifact digest were independently verified after download.

Exact commands:

```sh
./gradlew :app:desktopTest --tests '*SearchLayoutTest' --tests '*SearchControllerTest' --tests '*SearchResultsViewportTest' --tests '*ResultKeyTest' --tests '*ApplicationSessionTest' --tests '*LocalImportSessionTest' --tests '*PdfSessionTest' --no-daemon --console=plain --max-workers=2
./gradlew :app:testAndroidHostTest --tests '*SearchControllerTest' --tests '*SearchResultsViewportTest' --tests '*ResultKeyTest' --tests '*ApplicationSessionTest' --tests '*LocalImportSessionTest' --tests '*PdfSessionTest' --no-daemon --console=plain --max-workers=2
./gradlew :androidApp:compileDebugKotlin :desktopApp:compileKotlin --no-daemon --console=plain --max-workers=2
```

- Desktop focused tests: `BUILD SUCCESSFUL in 2m 32s`; 15 tasks executed.
- Android host focused tests: `BUILD SUCCESSFUL in 1m 21s`; 14 executed, 1 up-to-date.
- Android `compileDebugKotlin` and Desktop `compileKotlin`: `BUILD SUCCESSFUL in 26s`;
  12 executed, 11 up-to-date. Common/platform compilation was also covered by tests.
- No full clean matrix, APK build, signing operation or signing-input access was
  performed for this follow-up. Existing development signing work is untouched.
- Temporary verification automation is deleted from the final tree. No permanent
  per-feature workflow, CI redesign or dependency change remains.
- Artifact `pr20-search-layout-verification`, ID `11463202697`, contains XML and exact
  JSON counts (seven-day retention). ZIP SHA-256:
  `941867035cafadde93ed7d438e8b43c21160527b4ccf101c95ad87cff22a7dc1`.
- Final diff/status, Markdown links and tracked-secret checks pass. No signing
  input, private key or attachment/secret path is introduced. PR #20 stays Draft.

### Remaining physical/graphical checks

Install a build of the corrected HEAD on the small Android viewport that exposed
the bug. Check portrait/landscape and enlarged fonts; scroll the source/query
header, then the result list; show/hide the keyboard while the query is focused;
search/next page/open/Back; exercise empty/loading/error states; confirm import and
navigation controls remain accessible and imports still enter Library. The user's
reported PDF acceptance is preserved, but Search touch/IME behavior with this fix
still needs that physical check. A native Desktop-window smoke test should confirm
mouse-wheel/header/result behavior and resizing; headless layout/compilation does
not establish Windows/Linux/Wayland/niri graphical acceptance.

### Updated development APK for Search physical acceptance

An updated unsigned debug APK was built from production HEAD
`347e2eeb5ccdfab55ec3e646bc329be857d45034` using temporary automation at
`0c470033fc6e399ad93c4b19c8d8de2e12dae4a0`; application code is identical.
[Build evidence](https://github.com/SirOtter0/INFINILECT/actions/runs/37577728203)
records `:androidApp:assembleDebug` with an external initialization script that
disables debug signing. `BUILD SUCCESSFUL in 2m 57s`; no tests or full matrix
were repeated. The temporary workflow is removed again from the final tree.

The unsigned artifact ZIP (ID `11463496663`) was verified against SHA-256
`079a97d0ad19000daf5839935a74a1763a4c0d25ec008e1828fa61378987b63d`.
Signing occurred locally with the user-supplied development identity; no signing
input was copied into the repository or uploaded to CI. The signed APK is
12,079,111 bytes, SHA-256
`f8d574976206b2dd3db1d9a1d53a7e3edf28663d69076fc3fb885748d4d0b699`.
Certificate SHA-256:
`95:C7:08:E6:CF:EE:94:BC:13:EC:F3:4D:2A:38:CF:4F:1D:C5:EA:B1:85:13:3F:8E:13:96:29:DD:0B:E4:70:FE`.
APK Signature Schemes v2/v3 were verified with minimum SDK 26. This identity is
for development/testing only. The remaining Search physical checks above still
apply; building/signing does not constitute device acceptance.
