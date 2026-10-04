# ADR 0019: Bounded structural EPUB preparation without a renderer

Status: Proposed — Draft PR #13
Date: 2026-10-04

## Context

The real TEXT reader has separate acquisition, preparation and presentation
boundaries. EPUB needs seekable container access and package/spine ownership
before any platform renderer can safely consume it. Adding a rendering dependency
to core or treating a structural parser as a reader would break those boundaries.

## Decision

Add pure Kotlin EPUB metadata/manifest/spine/internal-path/document contracts in
core. Add an independent app preparation seam alongside TEXT, owned and closed by
ApplicationSources. Do not change the TEXT opener or mark EPUB readable in UI.

Stream ResourceContent to an app-private UUID session ZIP, then strictly inspect
ZIP32 local/central records, bounded sizes/expansion/CRC, OCF container and EPUB3
package/XHTML ownership before returning a document. Use existing standard
ZipFile/SAX APIs shared by Android/Desktop, with required DTD/entity protections.
No extraction, new dependencies, source-policy changes or database migrations.

Prepared documents expose only manifest-owned local sequential ResourceContent
handles. A future platform renderer must impose its own script/network/content
sandbox and explicit Ready handoff. Readium objects cannot enter core; any future
Readium Kotlin adapter is Android-specific, not an assumed Desktop solution.

Prepared ZIPs are rebuildable session/cache-directory infrastructure, independent
of revision-aware ResourceCache, persistent user state and future Downloads.
Application close cancels preparation, closes all handles and drains off-main
cleanup. No EPUB progress is persisted; a future locator must be EPUB-specific.

## Consequences

32 MiB archive / 64 MiB expanded / 8 MiB per-entry / 512-entry / 100:1 bounds,
1 MiB XML and finite parser/manifest/spine limits make preparation cost explicit.
The current subset rejects legitimate features including encoded/non-ASCII paths,
multiple renditions, DTDs, protected fonts, media overlays and fallback chains.
CSS/SVG are inert, not sanitized for rendering. CRC is corruption detection, not
source authorization or a fabricated revision. Full validation scans the archive
before document publication and costs private disk space; two live documents per
owner are supported. Physical Android provider/engine verification is still needed.

This adds a new boundary without rewriting ADR 0017 or enabling Gutenberg
acquisition. [Exact policy, limits and evidence](../EPUB.md).
