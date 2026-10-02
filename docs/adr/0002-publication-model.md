# ADR 0002: Source-aware identity and independent type/format

Date: 2026-10-02 · Status: Accepted

## Context

Catalog-local identifiers collide, and a book can be EPUB, PDF or plain text.

## Decision

PublicationId is the structured pair (SourceId, localId). Keep semantic
PublicationType separate from PublicationFormat. Publications list resource
references with explicit ownership and unique keys; the resource locator remains
opaque to readers. Source adapters provide search/details/resources; readers use
ResourceLoader rather than calling adapters.

## Consequences

Storage and routing must preserve both ID components. Formats can expand without
reclassifying publications. Reader choice and resource loading do not depend on
catalog protocols. The initial foundation deferred streaming and richer metadata;
[ADR 0006](0006-bounded-resource-access.md) and [ADR 0007](0007-metadata-and-resource-identity.md)
now refine those boundaries before any real adapter. Rich transport error types
remain deferred.
