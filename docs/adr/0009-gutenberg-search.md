# ADR 0009: Explicit Gutenberg OPDS search on desktop

Date: 2026-10-02 · Status: Accepted for the search slice

## Context

The next useful increment is real catalog search, not a full reader. Gutenberg
permits OPDS applications with an identifying/contact User-Agent and browser-like
traffic. Its actual search feed includes book and navigation entries, optional
metadata and continuation links. XML and URLs are untrusted input.

## Decision

Keep two modules and the existing core contracts/models unchanged. Implement the
trusted GutenbergSource in app/desktopMain, injected into a shared Compose-free
SearchController through PublicationSource. SearchScreen observes simple StateFlow
states. Request only one page per explicit search/Next page action, ignoring busy
actions and serializing source HTTP operations. Replace pages instead of building
an unbounded result history; retain a failed continuation for manual retry.

Use official HTTPS Gutenberg XML OPDS, not HTML scraping or an aggregator. Validate
source-owned opaque tokens against the original query, host, search path and an
advancing index. Preserve optional metadata and represent only supported advertised
acquisitions. Never use feed updated as a content revision.

Choose stable Ktor 3.6.0 (Apache-2.0) and its Java engine for the existing JDK 21
desktop target. It uses JDK HTTP support without adding another networking backend.
Ktor is built with Kotlin 2.3.21, readable by the current Kotlin 2.4.20 compiler;
coroutines 1.11.0 matches its declared dependencies. Verify actual tests/build,
not just version numbers. Core does not inherit these dependencies.

Use JDK StAX, whose license is GPLv2 with Classpath Exception, as a desktop parser
without a new parser Maven dependency. Select the built-in provider, disable DTD
and external entity access, reject unsupported xml:base, bound response/parser work
and propagate cancellation. A shared parser abstraction or new source module is
not justified by one desktop adapter; revisit portability when adding a real target.

Leave getPublication/loadResource explicitly unsupported after source ownership
validation. Returning null/empty bytes would misrepresent support or absence; adding
detail/acquisition requires separate endpoint, resource and charset verification.
No new core capability hierarchy is needed without a reader consumer. Document
the exception and remove it through working implementations in the next slice.

## Consequences

Gutenberg is the first functional search source. Normal tests use authored fixtures
and MockEngine; an opt-in one-page live task checks the real implementation without
becoming a build dependency. The UI knows neither HTTP nor OPDS. No reader, cache,
downloads, progress persistence, new platforms or plugin engine are implemented.

Search navigation content is an author display summary, retained as one string when
formal authors are absent; missing languages/resources remain unknown. See
[SOURCES.md](../SOURCES.md) for exact endpoints, relations, security/traffic limits
and planned Gutenberg XML retirement in 2027. OPDS2 will need renewed official
verification/contact; do not infer a replacement endpoint.
