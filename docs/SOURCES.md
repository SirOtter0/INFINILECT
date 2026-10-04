# Sources

A source obtains publications and resources; it does not choose how they are read.
The app routes by stable SourceId. Catalog-local IDs are only unique inside that
source. Resource keys are opaque and must be resolved by their owning source.

## Project Gutenberg — experimental OPDS2 catalog

The exact endpoint supplied in Gutenberg's email is
`https://opds-test.pglaf.org/opds/`. The reply explicitly discourages unmaintained
OPDS0.9; it did not answer individual acquisition. [Categorized official/email/live
basis, limits and manual plan](GUTENBERG.md), [ADR0018](adr/0018-gutenberg-catalog-acquisition.md).

`GutenbergSource` uses shared bounded JSON parsing and Ktor outside core. The first
explicit search verifies the root self/search template, then requests one page;
next requires a user action. No legacy `/ebooks/search.opds/`, RDF, guessed download,
HTML scraping, retries, images or aggregator fallback. No requests at construction.

| Observed development interface | Use |
| --- | --- |
| `/opds/`, OPDS2 self/start/search template | One bounded in-session discovery on first explicit Search |
| `/opds/search{?query,title,author}` | Only encoded query; one result page≤25 |
| `next`, `/opds/search?limit=25&query=...&page=...` | Opaque validated token; explicit next action, page≤1000 |
| `self`, `/opds/publications?id=<ebook number>` | Fresh detail metadata; matching ID/self required |
| `http://opds-spec.org/acquisition/open-access`, delivery MIME/href | Inert bounded metadata; no PublicationResource or byte acquisition |

HTTP root/search currently use application/json while link types advertise
application/opds+json; details use application/opds-publication+json. Strict JSON
and UTF-8, 1MiB actual payload, preflight depth32/duplicate keys, ≤20,000 nodes,
bounded arrays/strings and exact HTTPS development routes. Redirects disabled;
serialization/cancellation off UI. Connect5s/request15s; UA/contact unchanged.

Identity remains `PublicationId(SourceId("gutenberg"), positive canonical ebook
number)`; canonical www/ebooks URL is informational. Title/authors/languages map
safely; absent dedicated rights remain absent. USA-rights prose/metadata CC0 never
becomes a worldwide book license. Gutenberg resources are empty; optional delivery
extensions are never byte authority. Readium defines optional `size`, not required
`length`; the observed HTML link without length is catalog-usable. [Standards/finding](GUTENBERG.md).

Internet Archive is selected initially. **Project Gutenberg (experimental)** is
catalog-only with metadata Library actions. `getPublication` resolves the observed
self-link pattern; `loadResource` is explicitly unsupported and performs no I/O,
even for old/forged TEXT refs. Saved entries remain when opening is unavailable.
No byte cache reuse/History/progress is created by catalog-only actions.

## OAPEN alternate access and first acquisition source

Historical REST requests returned HTTP403. Official OAI-PMH GetRecord instead
returned metadata with a direct PDF link; its HEAD was blocked, and the opt-in
alternate Ktor diagnostic timed out once. REST blocked does not mean OAPEN
unusable. No OapenSource or challenge bypass. See [OAPEN](OAPEN.md).

InternetArchiveSource is the second concrete PublicationSource and first actual
resource-acquisition experiment. It uses official advanced search/item JSON APIs
and documented individual download permalinks, supporting only public CC0 text
items with validated TEXT/PDF files. Desktop/Android default to Internet Archive TEXT reading, with an explicitly
selected experimental Gutenberg catalog as the other source.
The source-neutral TextReader requires full strict UTF-8 with known size ≤16 MiB;
PDF/EPUB are not opened. Archive's existing opt-in prefix demo still reads at most
512 bytes; its new full-text check uses the same session/controller as the UI.
No arbitrary metadata URL is acquired. Fresh item-scoped delivery locations, permission
refresh, bounded parsing/streaming and deterministic offline tests enforce limits.
See [Archive endpoints/rights/hosts](INTERNET_ARCHIVE.md),
[comparison](ACQUISITION_COMPARISON.md), and
[ADR 0011](adr/0011-verified-source-acquisition.md), and
[ADR 0012](adr/0012-bounded-text-reading.md). The later disk-cache loader bypasses
these null-revision resources; fresh Archive acquisition is unchanged. No downloads. Persistent progress remains independent. See [cache policy](CACHE.md).

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
