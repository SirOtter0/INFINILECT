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
