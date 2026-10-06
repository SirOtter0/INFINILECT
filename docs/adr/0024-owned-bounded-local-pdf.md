# ADR 0024: Owned bounded local PDF reading

Status: proposed in Draft PR #20. Base: merged PR #19
`2eb55715131b0628be526c5e4644bd6a9f68a417`.

## Context

Local acquisition already owns immutable, SHA-256-identified imports independently
of cache, Library, History and original picker locations. PDF parsing is hostile
input. Domain/application readers must depend on owned contracts and must not
acquire platform descriptors or expose engine documents/images.

## Decision

Add the small PdfPreparer/PdfDocument/PdfRaster boundary, separate from CBZ's
page-resource preparation. Android uses framework PdfRenderer's API-26-compatible
surface; Desktop uses file-backed Apache PDFBox 3.0.8. Only Desktop adds a PDF
dependency. Source bytes must pass complete loader verification before parsing;
bounded document inspection is mandatory before an import is published.

Own normalized geometry, page count, bounded dimensions and typed failures in core.
Serialize engine operations and retire cancellation results safely. Retain one
rendered page and persist its logical index through the existing Page locator and
progress schema, with PDF format identity. Passive rendering only.

This pair uses the Android platform's existing engine and a maintained pure-Java
Desktop dependency, avoiding a shared native distribution and additional licensing
or ABI management. Adapters remain independently replaceable. It does not guarantee
identical appearance or encryption classification across engines.

## Consequences

Explicit source/page/geometry/raster limits bound owned inputs and outputs, not
all parser working memory or CPU. Synchronous operations have no promised hard
deadline. Close can await abandoned native/parser work asynchronously. Future
process isolation is required for enforceable kill/time/memory budgets, and can
replace adapters without changing PdfReader. It is deliberately deferred here.

No password UI, search/OCR/reflow, thumbnails/tiling, forms/actions/scripts,
embedded-file extraction, external acquisition, annotations/signatures or export.
Host verification cannot establish Android physical or Desktop graphical acceptance.
[Detailed policy, budgets, references and acceptance](../PDF_READER.md).
