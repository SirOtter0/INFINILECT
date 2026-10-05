# ADR 0021: Passive shared EPUB presentation and semantic progress

- Status: Accepted for the deliberately narrow Draft PR #15 implementation
- Date: 2026-10-04
- Follows: [0019](0019-bounded-epub-foundation.md), [0015](0015-persistent-reading-progress.md)

## Context

Structural EPUB validation neither renders chapters nor makes HTML browser-safe.
Android and Desktop need the same source-independent reader semantics and durable
positions. TEXT's code-point locator cannot name a spine item. Readium Kotlin is
maintained Android software, not a Desktop engine. WebView/JavaFX/JCEF would add
separate execution/network/file/navigation policies and native/UI lifecycles.
The existing bounded XML/Compose/file progress infrastructure suffices for a
smaller shared reader. [Upstream research and exact subset](../EPUB_READER.md).

## Decision

Use manifest-owned EpubDocument resource handles, bounded namespace-aware SAX and
ordered mixed content to create semantic chapter blocks/runs/owned internal links.
Render those through passive shared Compose, with **no browser or execution path**.
Ignore CSS; reject active/foreign content and external/undeclared targets. Images
are bounded alt-text placeholders, never decoded. Retain two bounded chapter models;
serialize chapter parsing and reject stale generations. TOC/Previous/Next/internal
anchors navigate only declared spine items. No new third-party dependency.

The neutral opener retains TEXT preference and adds an enabled EPUB preparation/
reader handoff. First chapter/TOC must parse before successful-open History. It
closes every unhanded document; the reader owns its document after handoff. Back,
replacement and application close cancel reader work, flush progress and close
prepared resources. Library/History keep metadata only and re-resolve owning sources.

Pure core adds ReadingLocator.Epub(canonical spinePath, element-child ordinal path,
block code-point offset, chapter progression). Layout coordinates remain transient.
Text records keep schema v1 byte encoding; EPUB uses v2/tag2. File names, quotas,
checksums, atomic replacement and platform directories remain unchanged. No SQL
schema change, migration of TEXT or byte revision fabrication. Two-second fixed
throttle and immutable progress identity preserve persistence ownership/privacy.

Production EPUB acquisition is **disabled pending verification**: Archive's current
adapter/legal boundary is unchanged and Gutenberg remains catalog-only. An original
three-chapter development source exercises the actual pipeline/persistent state in
Android debug builds and explicitly opted-in Desktop. This is not advertised as a
production catalog. Runtime ZIP construction remains bounded, with no copyrighted
fixture or imported arbitrary files.

## Consequences

Both platforms share semantics and UI without an Android-only engine in core.
The subset sacrifices publisher CSS/layout/images and broad EPUB compatibility.
Element structure changes fall back approximately within a surviving spine path;
removing that path starts at the first chapter. Whole-publication percent is chapter
weighted, not a page/word guarantee. Schema-v2 EPUB records are ignored by older
builds, while historical TEXT remains compatible. Device UI/performance/restoration
and production source verification remain review gates. No EPUB release/completed
v0.0.1 claim. Future richer presentation must preserve this explicit ownership and
security boundary, rather than treating prepared HTML as trusted app content.
