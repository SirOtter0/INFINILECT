# ADR 0018: Project Gutenberg catalog and acquisition separation

- Status: Accepted for implementation; human PR/device review pending
- Date: 2026-10-04
- Extends: [ADR 0009](0009-gutenberg-search.md); its historical search-only scope is preserved

## Context

Gutenberg correspondence permits explicit application opens. Current official
interfaces expose OPDS search and per-ebook RDF with current file/charset/size/rights.
XML OPDS is planned for retirement in 2027. Durable file URLs, guessed `/files`
paths, scraping and unbounded/bulk acquisition are inappropriate. The existing
indexed TEXT reader, source resolution and independent stores already suffice.

## Decision

Keep core and all reader/persistence contracts unchanged. Isolate catalog XML in
GutenbergCatalog; use a separate bounded RDF parser/resolver behind GutenbergSource.
Publication identity is Gutenberg's canonical numeric ID; `/ebooks/<id>` is only
an informational stable reference. TEXT has logical key `text-utf8`, not a URL.
Fresh RDF must match the ebook and format ownership. Refresh again immediately
before acquisition; Library/catalog metadata never authorizes bytes.

Prefer explicit UTF-8 direct files over generated variant locations. Require known
extent, HTTPS exact www.gutenberg.org, item-scoped validated paths, no query/userinfo/
fragment/ports/escapes and tightly scoped redirects. Do not accept mirrors or repair
malformed redirects. Reject over 16 MiB before downloading. Preserve strict full
UTF-8/EOF and existing indexed reader/progress semantics. No charset inference.

Revision remains null: modified timestamps/extent are not a verified byte revision.
Reusable resource caching is bypassed; reopening acquires again. Keep original book
rights separate from metadata CC0 and make no worldwide public-domain claim.
One explicit search page and user-triggered opens only; identifiable project UA,
finite deadlines, serialized requests, cancellation and close on all terminal paths.

## Consequences

Both Android/Desktop use the same reading stack and existing local metadata/progress
stores. Verified OPDS2 can replace catalog discovery without changing readers or
acquisition contracts. No new dependencies/schema. Some legacy/ASCII-only/mirror-only
or malformed-redirect books are deliberately unavailable; stale/missing size fails
closed. High-volume/mirror optimization is deferred. A host live check is evidence
of server behavior, not device UI verification. See [policy/evidence](../GUTENBERG.md).
