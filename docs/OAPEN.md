# OAPEN investigation and acquisition gate

Verified on 2026-10-02, starting from integrated main
`86d70c1bc4f281e7f866207ff8bfcfed647e058d` on `feature/oapen-acquisition`.

**Blocked investigation, not a functional second source or acquisition slice.**
OAPEN is intended to be the first real acquisition experiment. No OapenSource,
OAPEN search UI, resource acquisition, PDF consumer, cache or download store is
implemented. Gutenberg remains unchanged and search-only while acquisition
guidance is pending. See [ADR 0010](adr/0010-oapen-verification-gate.md).

## Official evidence

The following current official pages and the guide they link were consulted:

- [OAPEN Library metadata](https://www.oapen.org/librarians/oapen-library-metadata)
  and [Search OAPEN using a REST API](https://www.oapen.org/article/8185269-search-using-a-rest-api)
  document `https://library.oapen.org/rest/search`, the `query` parameter,
  `expand=metadata`, `expand=bitstreams` and `expand=metadata,bitstreams`.
  `Accept: application/xml` or `application/json` chooses the representation.
- The linked [REST /search guide](https://oapen.o172i.upcloudobjects.com/151b0a45669f429381cb46fe441f23b6.pdf)
  states that search results are lists of items, with `expand`, `limit` and
  `offset` parameters. It documents query/filter/sort syntax, including
  `dc.title_sort|asc`, but is not a complete item/bitstream response schema or a
  file-transfer specification. No API version is inferred from DSpace history.
- [Researchers](https://www.oapen.org/researchers) explicitly states that readers
  can access and download books without fees, restrictions or registration.
  This establishes general reader access, not a verified REST transfer route.
- [POSI self-audit](https://www.oapen.org/oapen/posi-self-audit) distinguishes
  openly available CC0 metadata from publications distributed with publisher
  licences. The metadata page also states that its feeds are CC0 1.0.

The site's general CC BY 4.0 footer is not applied to every book. A future mapper
must preserve publication-level rights/licence text or identifiers supplied by
OAPEN. Open access is not an assertion of public-domain status. Metadata openness
does not change the individual publication licence or permit arbitrary reuse.
No downloaded documentation or publication files are bundled in this repository.

## Observed access blocker

Two development requests to the documented endpoint returned HTTP 403:

```text
https://library.oapen.org/rest/search?query=handle:%2220.500.12657/25287%22&expand=metadata,bitstreams
https://library.oapen.org/rest/search?query=water&expand=metadata,bitstreams&limit=10&offset=0

You address is not allowed to access this API.
```

Both used `Accept: application/xml` and an identifying/contact User-Agent.
The first query is copied from OAPEN's own example. The second is one bounded
page. This result does not establish a universal API-access policy or an
authentication requirement: access from this environment was rejected.
No alternate API, proxy, host, HTML scraper, aggregator or credentials were used
to work around it. We did not obtain a successful item/bitstream response.

## Explicit, bounded access diagnostic

```sh
./gradlew :app:oapenApiAccessCheck --args=water
```

This opt-in desktop task is independent of `test`, `check` and `build`. It uses
the existing Ktor Java engine and makes exactly one request to the documented
search endpoint with `expand=metadata,bitstreams`, `limit=10`, `offset=0`.
There is no pagination, retry, discovery request or publication acquisition.

- Only the fixed HTTPS host `library.oapen.org` and path `/rest/search` are used.
  Metadata cannot supply a URL; **no acquisition hosts are enabled**. The official
  object-storage host above is a documentation link, not an allowed book host.
- Queries are trimmed and limited to 1–256 characters, encoded as one parameter.
- User-Agent: `INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)`.
- Connect timeout: 5 seconds; request timeout: 15 seconds. As with the Java engine
  used by Gutenberg, no separate socket-timeout guarantee is claimed.
- Redirects are disabled, including same-host redirects; every non-200 response
  reports only its status and is not consumed or retried.
- HTTP 200 requires `application/xml` and uncompressed/identity encoding. If
  present, Content-Length must be a valid nonnegative length at most 2 MiB.
- Actual consumption is bounded to 2 MiB plus a single overflow probe, using an
  8 KiB buffer. Bytes are discarded, never materialized or logged. Cancellation
  propagates; scoped response cleanup cancels I/O, and close releases the client
  and its owned engine. One instance serializes requests.

This checks transport access only. HTTP 200 would **not** establish valid XML,
publication mapping, resource ownership, permitted acquisition or a usable PDF.
No JSON/XML parsing or source contract is fabricated. Deterministic tests cover
the diagnostic's transport limits and cancellation with MockEngine; they do not
claim to test the unimplemented OAPEN source/acquisition contracts.

## Required evidence before continuing

1. Obtain OAPEN guidance about the observed address restriction and permitted
   application/API access. No contact message has been sent on the user's behalf.
2. Obtain a successful current REST response and verify item identifiers, canonical
   URLs, titles, authors, languages, publication types, rights and bitstream fields.
   Do not select a parser or invent fields from an older DSpace example.
3. Obtain official documentation/guidance identifying the file-transfer endpoint
   or confirming use of a returned bitstream link for an individual explicit
   reader download, including allowed hosts, redirects and access requirements.
   The consulted pages enumerate bitstreams but do not specify that mechanism.
4. Only then implement `OapenSource : PublicationSource`, source-owned opaque
   pagination and metadata/resource mapping with `SourceId("oapen")`. Preserve
   OAPEN's own identifier and reject foreign IDs. Map only supplied, known MIME
   types; absent stable byte revisions remain null.
5. Route acquisition through `ResourceLoader` to the owning source and return a
   fresh bounded sequential `ResourceContent`. Readers/selection remain neutral
   to source identity. Verify PDF bytes under explicit user action, close the
   demonstration handle and open a fresh handle for any later consumer; never
   hand an already-advanced demonstration stream to a reader.
6. Define and test a book-size limit, streaming cleanup and a host/redirect policy
   against the verified mechanism before adding acquisition UI. These limits are
   not established by the diagnostic's 2 MiB metadata bound. No PDF engine or
   complete cache is needed to prove this flow.

Acquisition, source parsing/mapping/pagination tests, ownership/resource/redirect
tests and the Idle/Loading/Ready/Error demo are intentionally deferred rather than
replaced with fake success. Actual checks are in [VERIFICATION.md](VERIFICATION.md).
