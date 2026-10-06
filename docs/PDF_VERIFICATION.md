# PR #20 PDF verification

Base: `2eb55715131b0628be526c5e4644bd6a9f68a417`, verified current main/PR #19 merge.
Branch: `feature/bounded-local-pdf`. Draft PR creation follows final verification;
no merge is authorized or performed.

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
deadline. Final results will be recorded below after the corrected focused run
and frozen-code clean matrix; initial failures are not claimed as passes.

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
and Desktop distributable. Artifact identity and exact XML counts remain pending.

Exact frozen-code command (the external init script only disables CI signing):

```sh
./gradlew -I /tmp/pdf20-unsigned.gradle clean \
  :core:jvmTest :app:desktopTest :core:build :app:build :desktopApp:build \
  :core:testAndroidHostTest :app:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  :desktopApp:createDistributable \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```
