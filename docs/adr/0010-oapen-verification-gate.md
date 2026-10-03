# ADR 0010: Verify OAPEN access and acquisition before implementing a source

Date: 2026-10-02 · Status: Accepted verification gate; integration blocked

## Context

OAPEN was selected as the intended first acquisition experiment while Gutenberg
remains search-only pending acquisition guidance. Existing PublicationSource,
ResourceLoader and ResourceContent already separate acquisition from readers;
no new core abstraction or module is needed to investigate a second source.

Current OAPEN documentation defines REST search and expanded bitstream objects,
and explicitly permits free reader downloads. However, the documented endpoint
returns HTTP 403 from this environment. The consulted pages/linked guide neither
provide a complete current item/bitstream response schema nor specify the file
transfer mechanism and its hosts. General reader access is not a substitute for
verifying a concrete machine acquisition route.

## Decision

Stop at documented evidence. Add an opt-in, bounded Ktor access diagnostic and
record exact endpoints, policy statements, observed failures and remaining gates
in [OAPEN.md](../OAPEN.md). Do not implement a speculative OapenSource, a fabricated
resource URL, an alternative API, or a fake acquisition/demo success.

Once those gates are resolved, implement source-specific acquisition behind the
existing PublicationSource/ResourceLoader contracts. Resource selection and
consumers must not know OAPEN or Gutenberg. Return fresh sequential ResourceContent
handles with bounded reads, cancellation and close; keep revision null unless
stable byte identity is documented. No cache, persistent downloads or PDF reader
is necessary for that proof. Define actual acquisition hosts/limits only after
verifying the transfer route.

## Consequences

The requested acquisition slice is not complete. OAPEN is not yet a functional
source, and only Gutenberg remains selectable in the app. The diagnostic can
reproduce access evidence without Internet-dependent normal tests, dependencies,
core changes or Gutenberg policy changes. Resolving API access and the transfer
contract requires official OAPEN guidance and a successful response, not a guessed
DSpace version. Historical ADRs are preserved.
