# Project Gutenberg experimental OPDS2 catalog

PR #11 was corrected after human review recovered the exact Gutenberg email.
The prior HEAD `2f98e9a` implemented legacy XML search + RDF/direct TEXT delivery;
its successful transfers proved technical reachability, **not an endorsed
application-acquisition contract**. That implementation is removed, not a fallback.

## A. Direct Project Gutenberg email guidance

The user supplied the actual reply, which says:

> The development end point is https://opds-test.pglaf.org/opds/
>
> We expect to have a production preview service available soon.
>
> The OPDS 0.9 feed is not being maintained; we do not recommend developing
> against it - if it stops working we will not fix it.

The original question asked how to acquire an individual selected ebook, including
OPDS links, mirrors and robot/harvest. **The reply did not answer that question.**
It supplied a development endpoint and deprecated the legacy feed. It did not
endorse RDF, `/files`, harvest, mirrors, numeric thresholds or permanent-file-link
policy for INFINILECT. No personal email/name is persisted or sent by the app.

## B. Current official public documentation

Consulted once each on 2026-10-04 UTC; timestamps/bytes in [VERIFICATION](VERIFICATION.md).

- [Offline catalogs](https://www.gutenberg.org/ebooks/offline_catalogs.html): OPDS
  is for machine/application discovery; JSON OPDS2 is available for testing by
  contact. Public text still lists `/ebooks/search.opds/` and planned XML retirement
  in 2027. Direct guidance above is more specific: do not develop against OPDS 0.9.
  The page documents per-ebook cache/epub RDF as **catalog metadata**, identical to
  the bulk metadata catalog. This does not specify an interactive acquisition API
  or authorize arbitrary file URLs as application download policy.
- [Automated access](https://www.gutenberg.org/policy/robot_access.html): prohibits
  ordinary website automation except described interfaces. Its wget example uses
  `/robot/harvest`, a two-second wait and format/language filters; mirroring is
  described separately. It describes changing files and rebuilt generated formats.
  It does not designate RDF → `/files` as an interactive ereader contract, or
  answer whether harvest should serve one selected publication. No harvest request
  or crawl was made during this correction.
- [Terms of use](https://www.gutenberg.org/policy/terms_of_use.html): human website
  access, stable `/ebooks/<id>` informational links, no permanent deep-file links,
  identifiable OPDS User-Agent/contact web page or email, browser-like volume and
  a single result page per search. It recommends mirrors for many-book access;
  the footnote mentions ~100 books/day, **not a client quota or email promise**.
  We neither implement nor claim that threshold. It does not establish that
  every direct file returning 200 is a supported app-acquisition interface.
- [Official mirror list](https://www.gutenberg.org/MIRRORS.ALL), linked by those
  pages: lists collection/storage mirrors, including `gutenberg.pglaf.org`.
  Listing a mirror is not permission to wildcard domains or rewrite advertised
  acquisition URLs. No mirror was contacted.

The repository issues page remains a public contact matching the documented web
page/email requirement. User-Agent stays:
`INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)`.

## C. Current live service observations

Investigation used the exact email endpoint, its advertised query template and
publication self link; no guessed service host, endpoint enumeration or dumps.

- Root returned HTTP200, HTTP MIME `application/json`. Its self/start link type
  `application/opds+json`, JSON metadata/links/groups/navigation/publications and
  templated search identify an OPDS2-style service. No explicit numeric OPDS
  minor version or production guarantee was observed.
- Root search: `https://opds-test.pglaf.org/opds/search{?query,title,author}`.
  Query is URI-encoded. Search returns 25 items/page, currentPage/numberOfItems,
  self and explicit next/last links with query/limit/page.
- `query=Frankenstein`: 8 publications. `query=shakespeare`: 25 of 571,
  advertised page2/last23; neither continuation was requested in research.
- Stable metadata.identifier is `https://www.gutenberg.org/ebooks/<number>`.
  Detail self link is `/opds/publications?id=<number>` with link type
  `application/opds-publication+json`; the observed item84 detail uses that HTTP MIME.
- Book metadata includes title, author object/list, language, description,
  published date, subjects/accessibility. Item84 rights appear as **prose inside
  description**, `Rights: Public domain in the USA.`, not a standalone license.
  We do not extract that prose into a structured rights field or infer worldwide
  status. No book license/hash/revision was separately supplied in that response.
- Root/search/detail advertised `http://opds-spec.org/acquisition/open-access`
  and `application/epub+zip`, with integer `length`. Item84 points to
  `https://www.gutenberg.org/cache/epub/84/pg84-images-3.epub`.
  The inspected result and detail **do not advertise UTF-8 TEXT**. This is evidence
  about the inspected records, not a claim that no service record can ever have TEXT.
- Acquisition links are delivery locations, not publication identity or proven
  immutable byte revisions. None was fetched: delivery redirects, mirrors,
  file MIME and file length were not validated by this correction.
- The email calls it development; the root only titles itself Project Gutenberg.
  No linked production-preview endpoint or announcement was found in the inspected
  responses/pages. We do not probe invented production URLs.

## D. INFINILECT decisions

Choose **experimental catalog only** (option B, with development-only exposure).
Internet Archive is the default initial source and retains the real TEXT reader.
Gutenberg is explicitly labelled experimental, not production-ready. Search and
metadata-only Library are useful; reading remains disabled pending a verified
current TEXT acquisition contract and production-service direction.

`GutenbergSource : PublicationSource` → `GutenbergCatalog` → narrow bounded
OPDS2 JSON parser. Both Desktop and Android share exactly the same parser/policy,
using already-present serialization-json 1.11.0; no platform XML parser/fallback.
Legacy XML adapters, RDF acquisition/parser, text-download diagnostic and their
obsolete tests/fixtures are removed. Test-only kXML is no longer needed. Historical
merged ADRs/verification still describe earlier work accurately.

No requests happen at source creation. First explicit Search verifies the root's
self/search template, then fetches **one result page**; later searches reuse that
in-session template decision. This extra root discovery is not a result prefetch.
Only Next page requests a continuation. No recommendations, images, enrichment,
retry, crawling or background requests. Root groups are bounded but not rendered.

### Identity and metadata

`PublicationId(SourceId("gutenberg"), canonical positive decimal ebook number)`;
positive Int-range numbers, no leading zeros. Informational canonical URL remains
`https://www.gutenberg.org/ebooks/<id>` per public linking docs, never byte authority.
`getPublication` uses the **observed advertised publication-self route pattern**
for a validated ID, rejects mismatched identifier/self, returns null on404. This
is a development-service observation, not a permanent API guarantee.

Known descriptive EPUB resources use key `epub`, EPUB, `application/epub+zip`,
revision=null. Only exact fresh advertised open-access relation/type/item path
is mapped; URLs are not keys and are not stored in Library. TEXT/unknown formats
are not made readable resources. An EPUB metadata entry does not enable a reader.
`loadResource` is explicitly unsupported for every own-source resource (including
stored/forged TEXT), validates ownership and issues **zero requests**.

Missing author/language/rights stay absent; a dedicated rights string, if supplied,
is kept verbatim. Metadata description/CC0 declarations are not book-license facts.
No stable byte revision is demonstrated; none is fabricated. There is no Gutenberg
resource transfer/cache population. IA revision=null behavior is unchanged.

### Limits and trust boundary

- Exact HTTPS `opds-test.pglaf.org`, no credentials, explicit ports, fragments,
  cross-authority resolution, HTTP upgrade, wildcard or redirect handling.
- Root and observed search/detail routes only. Pagination tokens are source-owned,
  versioned opaque encodings; checked **before I/O**, same original query,
  exactly query/limit25/page, no duplicate parameters, next exactly current+1,
  pages≤1000. Old tokens are rejected, not converted.
- Query1–256 characters, no control characters; one result page≤25 publications;
  duplicate IDs/self/next keys rejected. Catalogue JSON≤1MiB actual stream plus
  one overflow probe, 8KiB transport buffer; declared length must match if present.
- HTTP200 expected (404 only for details), expected OPDS JSON or observed generic
  application/json, optional UTF-8 charset, identity encoding only. No HTML/XML
  parsing, fallback or renderable untrusted markup.
- Strict UTF-8 and JSON, depth32 checked **before recursive JSON parsing**,
  duplicate decoded object keys rejected (including escape aliases), ≤20,000 nodes,
  arrays≤256, object keys≤64, string values≤16,384 characters; title2048,
  ≤64authors×512 and languages×64, ≤32links and ≤8rels/link.
- Parsing off UI dispatcher; cancellation in preflight and mapping. Serialized
  operations, connect5s/request15s/configured socket15s (engine capability applies).
  Source close cancels active and waiting work, then client/engine; idempotent.
- No source byte acquisition means hostile download URLs cannot trigger SSRF.
  Descriptive EPUB URLs nevertheless require exact fresh item-specific www path.
  Unknown official hosts fail closed. No mirror allowlist was invented.

### Library, History, progress and reader

Add from results stores metadata only. Restart retains the Library item; Open gives
an existing fixed unavailable-source error and preserves it. No successful History,
ReadingProgress or cache bytes are created for catalog-only attempts. Previous
Gutenberg user metadata/progress are not deleted. If acquisition is eventually
enabled, saved IDs must re-resolve through normal source authorization.

IA reader, large-TEXT preparation, 16MiB limit, UTF-8/BOM, history success-only,
logical code-point progress, resource-cache rules and all schema remain unchanged.
No external acquisition is inserted into core, UI reader or persistence.

## Final opt-in development check

`./gradlew :app:gutenbergSearchCheck --args=Frankenstein` is separate from
build/test/check. It attempts root → one search → one current detail and reports
only status/counts/IDs/formats. Final run returned root HTTP503 and stopped after
one request, no retries. It did not validate the corrected live catalog chain.
Earlier research200 observations do not remove this external availability gate.
It never requests EPUB/TEXT, images or next pages.
[Exact final timestamp/requests/bytes/build/APK](VERIFICATION.md).

Previous successful 84/1342 RDF transfers are retained as historical observations
in VERIFICATION, explicitly superseded as justification for shipping acquisition.

## Human Android correction smoke plan (not performed by Codex)

A. Install corrected APK over merged PR10/previous PR11 without clearing app data.
B. Existing Library/History/ReadingProgress remain; Internet Archive is initially selected.
C. IA `identifier:gmb-2015-93040` → Open text → real text → scroll → Back.
D. Wait≥3s before leaving; reopen and full-process restart restore IA progress.
E. Select **Project Gutenberg (experimental)**; catalog-only notice, no Open text on results.
F. Search **Frankenstein**; real metadata/author/language, no automatic downloads.
G. Add item84 from results; no reader/History/progress is created.
H. Terminate/relaunch; Gutenberg Library entry persists.
I. Open that Library entry: fixed unsupported-source error; Back to Library keeps entry.
J. Remove Gutenberg Library entry: previous History/progress and other Library entries remain.
K. Search **shakespeare** → explicit Next page; no automatic pagination.
L. Change source during search; no late old-source results or crash.
M. IA reopen/History/Library still work; Android Back retains IA query/results.
N. Clear **cache only**: Library/History/progress remain; IA prepares again normally.
O. No normal-operation storage/save errors. No claim of Gutenberg reading or >512KiB acquisition.

## External gates / adversarial review

Need a current advertised UTF-8 TEXT acquisition contract, delivery/rights/size
semantics and production/preview readiness from Gutenberg before enabling reading.
EPUB-only links do not justify ZIP extraction, RDF fallback or charset guessing.
The production-preview service is **not proven available** by this investigation.

If `/ebooks/search.opds/` disappears tomorrow, this implementation still works
against its selected experimental interface: **no executable path references it**.
If development OPDS2 changes/unavailable, show a safe error; never fall back to legacy.
