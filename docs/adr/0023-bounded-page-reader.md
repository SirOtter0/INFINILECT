# ADR 0023: Bounded source-independent page reader

- Status: Proposed in Draft PR #17
- Date: 2026-10-05
- Follows: [0002](0002-publication-model.md), [0015](0015-persistent-reading-progress.md), [0022](0022-bounded-epub-media-and-presentation.md)

## Decision

Keep publication meaning (COMIC) separate from transport (PAGES). Pure PageDocument
describes an ordered source-owned page sequence and opens ResourceContent, not URLs
or platform images. ResourcePageDocument is the current individual-resource adapter;
future CBZ/web/download adapters can provide the same document. PDF stays a separate
semantic reader. Require bounded source-declared geometry for stable vertical slots,
but validate actual image headers/provider dimensions before allocation.

Extract PR #16 hardened passive raster decoding into a neutral owned RasterDecoder
boundary. EPUB retains separate document/controller semantics. PageReaderController
owns one bounded decode worker, serialized across replacement page readers, and at
most three decoded pages, using visible pages or ±1 prefetch. Compose fits/zooms pages and uses stable lazy page keys;
loading/failed/ready states have the same height. No browser, URI loader or new dependency.

Persist Page(pageKey, ordered-index fallback, intra-page fraction) as schema3/tag3 in
the existing progress store; preserve TEXT v1/EPUB v2 bytes. Persist global reading
mode through separate PageReaderSettingsStore and a bounded, coalesced application
writer; never in progress/cache or Library/History SQL. Use private persistent data
and the existing non-destructive atomic filesystem strategy. Back/navigation remain
ApplicationSession semantics, with Android only binding its system action reactively.

## Consequences

This is a first bounded page-reader foundation: PNG/JPEG only, 512 pages, 2 MiB
encoded/2048 dimensions/1M pixels per page, three retained rasters, cooperative 10s
decode deadline. Strict formats and bounded view windows intentionally exclude some
legitimate scans. No production comic source or CBZ/PDF/download support. Original
debug/opt-in artwork makes real end-to-end review possible without broadened rights.
Detailed budgets, lifecycle, approximation and physical gates: [PAGE_READER](../PAGE_READER.md).
