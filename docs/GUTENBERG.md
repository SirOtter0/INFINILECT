# Project Gutenberg TEXT integration

## Official evidence and narrow scope (2026-10-04 UTC)

- [Offline Catalogs and Feeds](https://www.gutenberg.org/ebooks/offline_catalogs.html)
  documents application OPDS discovery, the search entry point, and the **same
  per-ebook RDF metadata** under cache/epub as the downloadable metadata catalog.
  No full catalog was downloaded. XML OPDS retirement is planned for 2027; OPDS2
  is available for testing by contacting Gutenberg, not by guessing an endpoint.
- [Robot access](https://www.gutenberg.org/policy/robot_access.html) documents
  permitted automated acquisition, `/robot/harvest` format/language variants and
  mirrors. One bounded harvest page was inspected as documentation, never crawled.
  Harvest is a bulk discovery interface, not this app's individual-open resolver.
  The page also explains that files change and generated formats are rebuilt.
- [Terms of Use](https://www.gutenberg.org/policy/terms_of_use.html) requires an
  identifiable OPDS client/contact, browser-like volume, and stable `/ebooks/<id>`
  references instead of durable direct-file links. High-volume clients should
  use mirrors. No numeric rate-limit contract is inferred or implemented.
- User-provided Gutenberg correspondence permits OPDS applications and explicit
  individual acquisition. The implementation combines that guidance with current
  official machine-readable RDF evidence; it does not scrape landing pages.

`/cache/epub/84/pg84.rdf`, `/cache/epub/1342/pg1342.rdf` and other bounded known-item
probes returned RDF identifying current files, `dcterms:isFormatOf`, explicit
`text/plain; charset=utf-8`, byte extent and original book rights. Metadata-level
CC0 in `cc:Work` is **not** substituted for book `dcterms:rights`. USA public-domain
status establishes no territorial guarantee outside the USA.

## Identity and separation

`PublicationId(SourceId("gutenberg"), decimalEbookId)` is stable. IDs are canonical
positive decimal integers up to Int.MAX_VALUE (no leading zeros). The informational
`sourceUrl` is `https://www.gutenberg.org/ebooks/<id>`, never acquisition authority.
The TEXT reference is `key="text-utf8"`, format TEXT, mediaType
`text/plain; charset=utf-8`, revision **null**. Neither a file path nor mirror host
becomes application identity. No previous Gutenberg reading-progress records need
migration because acquisition did not exist before this PR.

`GutenbergCatalog` isolates XML OPDS search/pagination from `GutenbergRdfParser`
and `GutenbergAcquisition`. Replacing the catalog adapter with verified OPDS2 later
requires no core/reader/Library/History/progress contract change. The existing
Desktop StAX / Android XmlPull token seam and parser security limits are preserved
and reused for RDF. This is a bounded Gutenberg RDF subset, not a general RDF engine.

Search fetches one page only. No detail enrichment, acquisition or prefetch occurs
on search or metadata-only Add to Library. Opening re-resolves IDs via
`getPublication`; `loadResource` **refreshes RDF again under the serialized source
lock immediately before acquisition**. Stored metadata, rights, URLs and catalog
resource references cannot authorize the transfer.

## TEXT selection and network authority

Only explicit UTF-8 `text/plain` entries with one charset parameter, positive known
extent and matching `isFormatOf` are candidates. No ASCII/Latin-1 inference,
charset guessing, ZIP extraction, HTML conversion or EPUB acquisition is added.
Selection is deterministic: direct fresh RDF `/files/<id>/<safe ASCII name>.txt`
first, fresh generated `/cache/epub/<id>/pg<id>.txt` second, then the fresh RDF
`/ebooks/<id>.txt.utf-8` variant. Equal-rank candidates use lexical URL order.
Names are discovered, not synthesized from the ebook ID. The chosen logical
resource retains its identity if the current file path changes.

Only exact `https://www.gutenberg.org` with no explicit port is accepted. Raw ASCII
URLs must have no userinfo, query, fragment, percent escapes, backslashes, traversal,
other ebook ID or unexpected path. Unicode/confusable/suffix hosts fail closed.
A redirect may name only the same fresh file, or the selected UTF-8 variant's
item-specific generated delivery route. Relative root paths are validated before
resolution. Maximum two redirect hops, with loops rejected; the narrow authorized
set normally permits at most one useful hop. **No mirror host is accepted**, even
an official one. Higher-volume/mirror support requires a separately justified
item-scoped authority mechanism; there is no wildcard allowlist.

A HEAD probe of ebook 11's official UTF-8 variant returned a malformed HTTP
Location containing `/https,%20http://...`. It was **not followed or repaired**.
That is observed service/proxy-path evidence, not a guarantee about all Gutenberg
clients or a license to normalize malformed URLs. The RDF direct UTF-8 files for
84/1342 avoid that route. Books with only an unusable variant fail closed; there
is no silent fallback after network/policy failure. An explicit HTTP downgrade
also fails; HTTPS is never replaced by HTTP.

## Transport, reader and lifecycle

User-Agent remains:
`INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)`.
Ktor/platform engines are unchanged; no personal information, telemetry or retries.
Connect timeout 5s; metadata request timeout 15s; configured socket inactivity
15s (engine capabilities differ; Java does not guarantee a separate socket timeout).
TEXT request and idle handle ownership are bounded to 60s, as is the existing open
controller deadline. Source requests are serialized, including an active stream,
until close/EOF consumer cleanup or the ownership deadline. Back/app close cancels
pending work and synchronously closes active handles; each producer also closes in
`finally`. Duplicate close is safe.

Metadata: 1 MiB actual byte limit, exact Content-Length when present, Atom/RDF MIME
validation, identity encoding, no redirects. XML: depth 32, 100,000 events, 16
attributes/namespaces per element, 16,384 captured characters/attribute value;
RDF allows one matching ebook and at most 256 files, rejects DTD/entities and
foreign/nested xml:base. Parsing is off the UI dispatcher.

TEXT: positive size up to **16 MiB**, no larger than PR #10's reader maximum.
Acquisition requires identity encoding, explicit UTF-8 MIME, Content-Length equal
to fresh RDF extent, actual exact EOF and a one-byte overflow probe. Missing size,
stale extent, MIME disagreement, truncation or overflow fail safely. Files larger
than 16 MiB are rejected from metadata before requesting their bytes. The Archive
64 MiB source ceiling is unchanged. No whole-book ByteArray/String is created by
acquisition; 8 KiB metadata buffers and the existing reader's bounded buffers are used.

The existing `ResourceLoader → FileTextPreparer → TextDocument → TextReader` path
performs full strict UTF-8 validation, leading BOM handling, bounded indexing and
lazy local windows. Reader/core contain no Gutenberg branches. Existing Library,
History and code-point progress are unchanged. Successful Ready records History;
failed/cancelled opens do not. Saved Library IDs re-resolve through the source.

Gutenberg RDF modified/extent and HTTP dates are not proven byte revisions;
`revision=null` deliberately bypasses reusable ResourceCache lookup/fill. Every
reopen refreshes/acquires. Prepared reader backing files remain disposable session
storage, separate from persistent Library/History/progress. No Downloads/offline
management is implemented.

## Verification and physical Android A–T plan

Offline source/policy tests run on Desktop and Android host, including real file/
SQL store recreation with **no recent-map fallback**, large TEXT, ownership,
malformed metadata, redirects, MIME/size/EOF, cancellation and source close. Android
host uses a real kXML tokenizer behind the same seam, not a physical Android OS.
The opt-in `:app:gutenbergTextReadingCheck --args="84:Frankenstein 1342:Pride"` exercises shared session
search/open/full preparation/Back without logging text; never runs in test/build.
The successful corrected live run used title queries Frankenstein/Pride, 8 HTTP
200 requests, 1,326,203 consumed bytes, no redirects; complete texts were 421,633
and 738,046 bytes. An initial `id:84` diagnostic produced no usable results (HTTP
200, 3,466 bytes) and stopped before details/acquisition. This adapter does not
promise an ebook-ID query syntax; use title/author searches. No automatic retry
or rate-limit failure occurred. See [actual results](VERIFICATION.md). Live success does not establish device UI
behavior. The reviewer separately reports PR #10 physical Android large-TEXT
scrolling and progress persistence success; that evidence is not a Codex smoke test.

A. Install PR #11 APK over merged PR #10 without clearing app data.
B. Existing Internet Archive Library/History/progress remain.
C. Select Gutenberg; search `Frankenstein` (Frankenstein).
D. Add from results without opening.
E. Terminate/relaunch; Library entry persists.
F. Open from Library (fresh metadata/acquisition).
G. Confirm real readable TEXT.
H. Scroll substantially.
I. Wait at least 3 seconds, Back, reopen; logical progress restores.
J. Terminate/relaunch, reopen; durable progress restores.
K. Successful open appears in History.
L. Remove from Library; History/progress remain.
M. Search/open again; old progress still restores.
N. Search/open `Pride and Prejudice` (Pride and Prejudice), above the old 512 KiB ceiling.
O. Scroll forward/backward rapidly; bounded lazy windows resolve promptly.
P. Back/cancel while opening; no crash or successful History from cancelled work.
Q. Internet Archive still opens normally afterward.
R. Clear Android CACHE ONLY; Library/History/progress remain.
S. Reopen Gutenberg; disposable text data rebuilds with fresh acquisition.
T. No normal-operation storage/progress/library/history/network-policy errors.

No Codex physical-device or graphical Desktop test is claimed. No completed release,
EPUB/PDF reader, new database schema, source script or bulk downloader is introduced.
