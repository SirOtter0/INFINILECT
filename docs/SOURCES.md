# Sources

A source obtains publications and resources; it does not choose how they are read.
The app routes by stable SourceId. Catalog-local IDs are only unique inside that
source. Resource keys are opaque and must be resolved by their owning source.

## First source: Project Gutenberg / OPDS

Gutenberg is the first functional source, currently **search only** on desktop.
`GutenbergSource : PublicationSource` lives in `app/desktopMain`; shared UI receives
only core publications/search pages. No Gutendex, mirror catalog or other aggregator
is used. No human-facing HTML catalog page is fetched or scraped.

### Official interface verified on 2026-10-02

- [Offline Catalogs and Feeds](https://www.gutenberg.org/ebooks/offline_catalogs.html)
  explicitly permits application/machine OPDS discovery and identifies
  `https://www.gutenberg.org/ebooks/search.opds/` as the entry point.
- [Terms of Use — OPDS Feed](https://www.gutenberg.org/policy/terms_of_use.html)
  requires an identifiable User-Agent with a contact web page or email and
  browser-like request volume, including only one result page per search.
- [Robot access](https://www.gutenberg.org/policy/robot_access.html) prohibits
  ordinary website crawling; permitted machine interfaces must be used instead.
- The feed's [OpenSearch description](https://www.gutenberg.org/catalog/osd-books.xml)
  confirms `query={searchTerms}` for Atom/OPDS. It still advertises legacy HTTP/m.
  URLs; INFINILECT uses the documented current HTTPS/www entry point, verified
  with a real query. It does not follow the legacy host or suggestions endpoint.
- Current XML is Atom (`http://www.w3.org/2005/Atom`) with OPDS 1.x catalog links
  and Dublin Core terms (`http://purl.org/dc/terms/`), not JSON OPDS2. See the
  [OPDS 1.2 specification](https://specs.opds.io/opds-1.2.html) for relation semantics.
  Gutenberg documents an OPDS2 testing feed requiring contact and plans to retire
  XML OPDS in 2027. Recheck with Gutenberg before migration; do not infer an OPDS2 URL.

| Endpoint / relation | This slice |
| --- | --- |
| `/ebooks/search.opds/?query=<encoded terms>` | One GET per explicit submitted search |
| Feed `rel="next"` / Atom media type | Validated continuation, requested only by explicit Next page |
| Feed `rel="search"` / OpenSearch description | Verified during development; no runtime discovery request |
| Entry `/ebooks/<numeric id>.opds`, `rel="subsection"` | Determines book ID; no per-result detail fetch |
| Stable `/ebooks/<id>` landing URL | Derived only from validated Gutenberg ID per the official deep-linking policy; not fetched |
| `http://opds-spec.org/acquisition` and its subrelations | Map advertised TEXT/HTML/EPUB/PDF links when present; no acquisition request |
| `alternate`, `start`, author/subject subsections, images/thumbnails | No requests; navigation/cover entries are not books or resources |

### Request and pagination policy

User-Agent: `INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)`.
The public project issue page is the contact; no private personal data is sent.
Requests accept Atom and ask for identity encoding. There are no automatic searches,
debounce requests, retries, background discovery, per-book enrichment or next-page
prefetches. UI actions while loading are ignored; one source instance serializes
HTTP requests. Closing the application closes/cancels its owned client and engine.

`SearchPage.nextPageToken` is a versioned, source-owned opaque encoding of a verified
continuation URL. Consumers round-trip it with the **same original query**, without
parsing or constructing it. It is not a credential or a signed capability: the
source validates every supplied token before I/O. Only HTTPS/www Gutenberg search
URLs with that query and a strictly advancing `start_index` are accepted; duplicate
parameters, foreign hosts/paths, credentials, fragments and malformed tokens fail.
The displayed page is replaced rather than accumulated indefinitely. Next-page
failure retains the previous results/token for an explicit retry.

### Mapping and deliberately unsupported operations

Book entries use `SourceId("gutenberg")` and their numeric Gutenberg ID as localId.
Author/subject navigation and foreign entry IDs are excluded. EBook search results
are currently classified BOOK independently of any advertised resource format.
Titles and structured Atom authors are preserved. For the verified Gutenberg
navigation search feed, `<content type="text">` carries the author display summary;
it is used only if structured authors are absent and retained as **one display
string**, without guessing how to split names. HTML content is not used as authors.

Entry `dcterms:language` values are retained as normalized lowercase language tags;
feed `xml:lang` is not interpreted as the book language. Missing languages/authors,
rights or acquisitions remain absent. Plain Atom rights are preserved verbatim;
Gutenberg availability never implies global public-domain status. INFINILECT does
not host publications. Covers, descriptions and rich metadata remain deferred.

Acquisition links map only supported media types and validated HTTP(S) URLs on
www.gutenberg.org (legacy HTTP upgraded to HTTPS). Resource keys are source-owned
validated URLs; duplicate URLs are collapsed. Unknown/non-HTTP/foreign URLs and
image/archive representations are omitted. This is mapping, not download support.
All resource revisions remain **null**: observed search `<updated>` changes on
each request and cannot establish byte identity. See [CACHE.md](CACHE.md).

`getPublication` and `loadResource` first reject another SourceId, then throw
`UnsupportedOperationException` for owned identities. They never return fake null
details or empty bytes. Implement and verify actual detail/acquisition, charset,
stream close/cancellation and revision semantics with the reading slice before
offering an Open action. The core contract remains unchanged; see [ADR 0009](adr/0009-gutenberg-search.md).

### Input and transport limits

Ktor Java engine 3.6.0 runs only on desktop/JDK 21. Connect timeout is 5 seconds,
request timeout 15 seconds; Java engine has no separate socket-timeout guarantee.
Redirects are disabled, including same-host redirects (report an error rather than
silently fetching another page). HTTP errors are displayed without an automatic retry.
Queries are 1–256 characters. Feed bodies are bounded to 1 MiB by actual streaming
reads plus one overflow probe, even with missing/misleading Content-Length; compressed
responses and non-Atom media types are rejected. Scoped response consumption releases
I/O on completion, failure or cancellation; XML parsing runs off the UI dispatcher.

The parser is JDK 21's built-in **StAX**, desktop-specific, with DTD/external entities
disabled, external DTD access empty and a resolver that refuses all external access.
DOCTYPE/entity-reference events are rejected. It uses namespace-aware selective
mapping and rejects unsupported xml:base rather than resolving URLs incorrectly.
Limits: 100 entries (including navigation), depth 32, 100,000 events, 16 attributes
or namespace declarations per element, 16 KiB per attribute or captured text field. No XML/HTML rendering or
arbitrary link fetching occurs. This is a Gutenberg subset, not full OPDS validation.
See [JDK XMLInputFactory documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.xml/javax/xml/stream/XMLInputFactory.html)
for the security properties; the built-in provider is selected explicitly.
JDK license/platform scope is recorded in THIRD_PARTY_NOTICES.md. Android/iOS
parsers are not added or assumed compatible.

## OAPEN alternate access and first acquisition source

Historical REST requests returned HTTP403. Official OAI-PMH GetRecord instead
returned metadata with a direct PDF link; its HEAD was blocked, and the opt-in
alternate Ktor diagnostic timed out once. REST blocked does not mean OAPEN
unusable. No OapenSource or challenge bypass. See [OAPEN](OAPEN.md).

InternetArchiveSource is the second concrete PublicationSource and first actual
resource-acquisition experiment. It uses official advanced search/item JSON APIs
and documented individual download permalinks, supporting only public CC0 text
items with validated TEXT/PDF files. The unchanged UI still searches Gutenberg;
Archive's opt-in CLI demo reads at most 512 text bytes through ResourceContent.
No arbitrary metadata URL is acquired. Fresh item-scoped delivery locations, permission
refresh, bounded parsing/streaming and deterministic offline tests enforce limits.
See [Archive endpoints/rights/hosts](INTERNET_ARCHIVE.md),
[comparison](ACQUISITION_COMPARISON.md), and
[ADR 0011](adr/0011-verified-source-acquisition.md).

## Future declarative definitions

An external definition may describe a source ID, catalog URLs, approved engine
kind and engine-supported mappings/options. A versioned schema and validation
must precede importing external definitions. Trusted engines implement protocol
handling and enforce URL, redirect, size and parsing limits. Definitions must not
contain scripts, bytecode, shell commands, dynamic libraries or unrestricted
expression evaluation. Installing a definition cannot install executable code.

The first adapter can be built in. Do not build a plugin marketplace, universal
scraper or broad engine configuration language for v0.0.1. Authentication and
additional sources should be designed only when a concrete legal source needs them.
