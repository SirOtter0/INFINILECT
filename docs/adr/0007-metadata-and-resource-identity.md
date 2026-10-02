# ADR 0007: Minimal source metadata and revision-aware resource identity

Date: 2026-10-02 · Status: Accepted · Refines ADRs 0002 and 0004

## Context

The first real result/reader must preserve source provenance and legal context.
A stable publication/resource key alone cannot distinguish updated bytes.

## Decision

Add only languages (empty means unknown), nullable sourceUrl (publication detail/
canonical URL, not an acquisition) and nullable rights (verbatim source statement).
They support language display, opening the original source, and avoiding loss of
rights information. Core rejects blanks; trusted adapters normalize language tags
and validate URLs. Null rights is never a public-domain or permission conclusion.
Cover and summary are deferred: the first list can use title/authors, and there is
no concrete requirement yet for image acquisition or rich descriptions.

PublicationResource gains a nullable opaque revision. When known and nonblank,
cacheKey produces ResourceCacheKey containing the full PublicationId, resource key,
format, media type and revision. This is byte identity, not just catalog identity;
trusted engines must update it when content changes. A requested revision must
match acquired bytes or the adapter must refresh/fail.

Unknown revision produces no key for unconditional cache reuse. Source revalidation
is required before reusing any retained unversioned bytes. A post-download digest
identifies acquired bytes but cannot prove later source freshness. See CACHE.md.
Persist structured fields, or versioned length-prefixed components before hashing;
no ambiguous delimiter concatenation, toString serialization or runtime hashCode.

## Consequences

Updated revisions/representations cannot collide with earlier keys. The unknown
case is explicit instead of silently accepting indefinitely stale resources.
Correctness depends on the trusted engine's revision/validator mapping; adapter
fixtures must verify it. Downloads and progress retain their independent lifetimes.
No source integration, cache store, URL library, metadata hierarchy or serializer
is added. Tests verify metadata absence/preservation and identity separation.
