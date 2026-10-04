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
- Inspected item84 advertises `http://opds-spec.org/acquisition/open-access`
  and `application/epub+zip`, with integer `length`. It points to
  `https://www.gutenberg.org/cache/epub/84/pg84-images-3.epub`.
  The inspected result and detail **do not advertise UTF-8 TEXT**. This is evidence
  about the inspected records, not a claim that no service record can ever have TEXT.
- Acquisition links are delivery locations, not publication identity or proven
  immutable byte revisions. None was fetched: delivery redirects, mirrors,
  file MIME and file length were not validated by this correction.
- The email calls it development; the root only titles itself Project Gutenberg.
  No linked production-preview endpoint or announcement was found in the inspected
  responses/pages. We do not probe invented production URLs.

### Length finding: availability is no longer the parser blocker

The historical frozen check at 08:01 UTC received root HTTP503. A separate
read-only check at **2026-10-04T08:31:21.415060219Z** received root200/search200,
2 requests/185,660 consumed bytes, then failed on an acquisition link's `length`.
The 503 was temporary; it is not the current parsing defect.

Exactly one diagnostic search at **2026-10-04T08:37:02.379563Z** returned200,
12,391 bytes/8 publications. Seven EPUB links have JSON integer `length` values:
474733, 367792, 2349907, 340095, 325327, 329136 and 302751. The eighth, ebook10414,
has open-access relation, `text/html`, informational href
`https://www.gutenberg.org/ebooks/10414`, and **no `length` property** (not null,
string or fractional). The old parser required a positive Long on every acquisition
link before checking its format; this optional-field assumption invalidated the
entire otherwise usable search. The authored regression fixture retains only the
minimal metadata/self/HTML links; the complete live response is not committed.

### Official specification, distinct from service observation

- [OPDS2 §1.1](https://specs.opds.io/opds-2.0#11-introduction),
  [§5.1–5.3](https://specs.opds.io/opds-2.0#5-publications) and
  [publication schema](https://specs.opds.io/schema/publication.schema.json)
  base OPDS publication links on Readium's Link Object.
- [Readium Web Publication Manifest §2.4](https://readium.org/webpub-manifest/#24-the-link-object)
  defines **optional `size`**, integer, original bytes prior to archive
  compression/encryption. Its [official Link schema](https://readium.org/webpub-manifest/schema/link.schema.json)
  requires only `href`; `size` is integer with exclusiveMinimum0. Absent size
  is permitted; zero, negatives, null, strings and fractions are not valid `size`.
- Neither this Link definition/schema nor OPDS2 defines **`length`**. It is not
  required and has no normative byte unit/type/range here. Its absence is not an
  OPDS violation. The schema permits extension keys, so the observed numeric
  extension alone does not establish Gutenberg non-conformance. Null/string/etc.
  extensions have no standardized byte-size semantics and must not be coerced.

**INFINILECT was wrong to require `length`.** We are a bounded catalog-subset
parser, not a universal OPDS validator. An inert, undefined delivery extension
does not justify rejecting safe catalog identity/display. This does not exempt
the response from JSON/UTF-8/bounds, source-ID/self or pagination validation.

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

**All Gutenberg Publications now have empty `resources`.** The earlier corrected
HEAD exposed descriptive EPUB refs despite unsupported acquisition; that is less
clear than a metadata-only model. All delivery hrefs and `size`/`length` extensions
are excluded, never retained as trusted bytes, URLs, revisions or resources.
Acquisition relation/type/href string structure and global JSON bounds remain
validated; numeric/string/null/object/array extensions are inert, never coerced.
Hostile or changed delivery URLs cannot escape the parser or cause requests;
only the independently validated ebook identifier/self determines publication
identity. Delivery-URL changes alone no longer invalidate catalog metadata.
Malformed required identity/self/type/string structure still fails closed.

This changes no Library schema: snapshots already exclude resources. UI was
already catalog-only, and future acquisition must introduce its own fresh verified
link/size/rights/delivery policy. No migration or capability is inferred from an
old descriptive EPUB ref, even if a consumer retained one in memory.
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
  Delivery hrefs are inert bounded strings, absent from the returned model, never
  rendered/followed/persisted. Fetched catalog hosts/routes remain exact and
  fail closed. No mirror/delivery allowlist or acquisition permission is inferred.

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
build/test/check. It now attempts only root → one search and reports bounded
status/counts/IDs/titles. No extra detail is needed for this length regression.
The historical 08:01 root503 and later 08:31 root/search200 parser failure are
retained as evidence; the current corrected run is recorded in VERIFICATION.
At **2026-10-04T08:47:29.718739554Z**, the frozen corrected check succeeded:
root200 (173,269 bytes), search200 (12,391), **8 publications /2 requests /
185,660 bytes**, resources0. No redirect/retry/429/503, details or acquisition.
This validates the current development catalog once, not production stability.
It never requests EPUB/TEXT, images or next pages.
[Exact final timestamp/requests/bytes/build/APK](VERIFICATION.md).

Previous successful 84/1342 RDF transfers are retained as historical observations
in VERIFICATION, explicitly superseded as justification for shipping acquisition.

## Human Android catalog smoke plan (not performed by Codex)

A. Install over PR10/current build without clearing app data.
B. Existing IA Library/History/ReadingProgress remain.
C. IA `identifier:gmb-2015-93040` → Open text → read/scroll/Back; wait≥3s and verify progress restores.
D. Select **Project Gutenberg (experimental)**; catalog-only notice, no Open text.
E. Search **Frankenstein**.
F. Verify catalog results render normally.
G. Add item84 from results without opening; metadata only.
H. Restart process; Gutenberg Library metadata persists.
I. Attempt saved Gutenberg opening: fixed unsupported behavior, no crash/new History/progress.
J. Remove that Gutenberg Library entry; other user stores remain.
K. Search **Shakespeare** and explicitly test Next page.
L. Switch source during search; no stale overwrite/crash.
M. Clear **CACHE ONLY**; permanent Library/History/progress remain.
N. No normal-operation storage/progress/network-policy errors.

## External gates / adversarial review

Need a current advertised UTF-8 TEXT acquisition contract, delivery/rights/size
semantics and production/preview readiness from Gutenberg before enabling reading.
EPUB-only links do not justify ZIP extraction, RDF fallback or charset guessing.
The production-preview service is **not proven available** by this investigation.

If `/ebooks/search.opds/` disappears tomorrow, this implementation still works
against its selected experimental interface: **no executable path references it**.
If development OPDS2 changes/unavailable, show a safe error; never fall back to legacy.
