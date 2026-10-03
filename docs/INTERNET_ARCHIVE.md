# Internet Archive: first bounded resource acquisition

Initial real integration verified **2026-10-02**; delivery/lifecycle review and
one new integration check **2026-10-03**. Narrow
desktop adapter for public **CC0 text items**, now consumed by the first minimal
Desktop TextReader as well as CLI demonstrations; not complete Archive support.
Gutenberg remains search-only, with unchanged source policy. Core has no source,
HTTP, JSON or platform dependency.

## Official interfaces and access

- [Search/API](https://help.archive.org/help/search-a-basic-guide/):
  `https://archive.org/advancedsearch.php`, metadata query `q`, `fl[]`, `rows`,
  `page`, `output=json`. One requested page (10 items) per explicit call.
- [Read metadata](https://archive.org/developers/md-read.html):
  `https://archive.org/metadata/{identifier}`. Public responses include `metadata`
  and `files[]`; HTTP 200 may contain `error`; missing items may return `[]`.
  Files include `name`, `format`, `source`, `size`, `mtime`, `md5`, `crc32`, `sha1`
  when supplied. Absent fields are not invented.
- [Items/permalinks](https://archive.org/developers/items.html): canonical
  `https://archive.org/details/{identifier}` and individual file permalink
  **`https://archive.org/download/{identifier}/{filename}`**. Storage redirects
  are expected; resulting storage URLs are not persisted identities.
- [MDAPI record](https://archive.org/developers/md-record.html): root `server`
  is the preferred node, `workable_servers` lists available data nodes, and
  `dir` is the absolute item directory. `d1`/`d2` describe primary/backup nodes.
  The documentation recommends the `/download/` service instead of constructing
  storage URLs, because items can migrate. Root state flags include `is_dark`,
  `nodownload` and `is_collection`. These are separate from editable item
  `metadata`; user JSON can also appear at the response root, so root placement
  alone is not proof that an arbitrary field is trustworthy.
- [Metadata schema](https://archive.org/developers/metadata-schema/index.html):
  `licenseurl` identifies a license; `rights` is a separate statement;
  `access-restricted-item` flags restrictions. Neither Archive presence nor
  successful metadata access proves download permission.
- [Official download guidance](https://archive.org/developers/internetarchive/cli.html):
  individual files, formats and HTTP Range. Some EPUB derivatives are generated
  on demand; no such route is implemented here.
- [Automated access](https://archive.org/developers/bots.html): descriptive
  tool/version User-Agent (and guidance for AI agents); respect 429/Retry-After,
  limit concurrency, delay bulk work, avoid unchanged downloads/check checksums,
  cache responses. No universal numeric search/read quota found. This slice is
  serialized, with no bulk action, automatic retry or prefetch. HTTP 429 stops
  the action without an automatic retry; any future retry must honor Retry-After.
  No cache/login introduced. Permission
  metadata is refreshed before opening rather than cached across actions.

All source clients/probes share the project identification:
`INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)`.
The development-tool/model identity has been removed; no private contact data.

Content retains its individual license. No blanket metadata/content license
is inferred from a footer. No lending/login/DRM support or restriction bypass.

## Scope and mapping

`InternetArchiveSource : PublicationSource`, `SourceId("internet-archive")`,
official item identifiers as local IDs. Search wraps a query with public-text/CC0
filters; item metadata remains the acquisition gate, not search. Query 1–256
characters, up to 1,000 explicitly requested pages, source-owned query-bound
opaque tokens. No automatic search enrichment/next page.

Search resources are empty until `getPublication()`. Details map title (identifier
fallback), creators, supplied language values and canonical permalink. Generic
`texts` conservatively map to `DOCUMENT`, not automatically books. The adapter
preserves `rights`, `licenseurl` and `possible-copyright-status` separately;
existing `Publication.rights`
contains verbatim rights if supplied, otherwise verbatim license URL. Never
concatenates/translates them or converts them to “public domain”.

Acquirable files require exact CC0 1.0 license URL, `mediatype=texts`, no
restricted/dark/no-download/collection/lending flags or known restricted
collections, no file-private/
restricted flag. File-specific rights or a non-CC0 file license are excluded
rather than overridden by item CC0. Conservative subset, not exhaustive Archive access policy.
Present restriction flags with ambiguous/null values fail closed; only absent
or explicitly false/zero flags pass. A copyright-status value is not converted
into a license or a public-domain assertion.
Unknown licensing yields no selectable files. Only `DjVuTXT`/`Text` plus `.txt`
map to TEXT/`text/plain`; `Text PDF`/`PDF` plus `.pdf` map to PDF/`application/pdf`.
These MIME expectations are validated against HTTP Content-Type, not invented
source MIME fields. No EPUB/HTML/archive acquisition. Demo verifies a UTF-8 TEXT
prefix, not all files' charsets or PDF validity. The separate TextReader consumes
the complete selected TEXT under its lower 16 MiB preparation limit and rejects invalid UTF-8.

Resource keys are validated filenames, never URLs. Size must be known, positive,
at most 64 MiB. Hashes supplied by IA are not verified by a prefix check:
**revision=null**, no unconditional cache key. Full-stream checksum verification deferred.

## URLs, hosts and streaming

Identifiers: first ASCII alphanumeric, then alphanumeric/`_-.`, max 100;
additionally reject `..`. Filename: narrow ASCII basename, max 200, with spaces
encoded as a segment. Nested/non-ASCII names, percent encodings, separators,
schemes, controls/traversal unsupported. Ownership and the whole resource
reference are checked against **fresh metadata** immediately before acquisition,
after waiting for the source mutex. Refresh and opening are one serialized
operation; another queued request cannot intervene.

### Item-scoped delivery trust boundary — reviewed 2026-10-03

Always begin with the official **`archive.org/download/{identifier}/{filename}`**
permalink. A redirect to that exact permalink is allowed, subject to loop/hop
checks. A storage redirect requires **all** of:

- An exact host/directory pair announced by this item's fresh HTTPS MDAPI
  response: documented root `server`/`workable_servers` with root `dir`, or
  `alternate_locations.workable[]` pairs of `server` and `dir`.
- Structurally parsed ASCII DNS labels in the `archive.org` zone, excluding
  punycode labels, Unicode, IP addresses, empty/invalid labels and trailing dots.
  This zone check is an additional constraint, **never wildcard authorization**.
- Exact `/N/items/{identifier}/{filename}` path for the same item/file, with
  `N` containing 1–3 decimal digits. The directory must match the announced pair.
- HTTPS, implicit/443 port, no userinfo, query or fragment, canonical path
  encoding. Reject dot segments, encoded separators/traversal, double encoding
  and any noncanonical alias. Normalize host case/default port for loop detection.

Only the validated redirect is followed; metadata never supplies a starting
download URL. Editable `metadata.server`, arbitrary URL fields, `d1`/`d2` alone,
and alternate `servers` entries not in `workable` do not authorize targets.
Limit to 16 primary and 16 alternate coordinates. Unknown/unannounced hosts,
even apparently official ones, fail closed without retry or a direct-node fallback.
Suffix lookalikes such as `evilarchive.org` or `archive.org.attacker.example`
are rejected. No configured cookies/credentials.

**Documentation vs observation:** official item documentation explicitly makes
redirects part of the permalink interface and documents primary node/directory
fields. The API response observed for the test item also announces
`alternate_locations.workable`, including `dn760105.eu.archive.org` with
`/0/items/gmb-2015-93040`; this shape is structured official-API evidence, **not
an exhaustive documented schema or hosting guarantee**. The reviewed pages
provide no exhaustive hostname family/list or guaranteed redirect count. The
maximum **two redirects** and numeric directory shape are conservative local
limits. Unsupported shapes/extra hops fail closed. No dweb route is needed.

The initial 2026-10-02 implementation authorized only one observed node.
The review replaces that production constant with fresh item-scoped locations,
without granting all subdomains access. Offline fixtures cover multiple nodes;
the single new live check reached a different documented primary node.

Require HTTP 200, expected Content-Type, identity encoding. Content-Length must
match file metadata; actual reads reject overlong/truncated streams, consuming
at most one probe byte beyond the known file size. Nonempty reads return positive
counts or EOF, never an unexpected zero. Metadata:
2 MiB plus one overflow probe, 8 KiB buffer, JSON depth 32, 200 files/array values,
16 KiB mapped strings. Strict UTF-8/bounded nesting precede kotlinx.serialization
parsing. Connect 5s, metadata request 15s, file request 60s; no Java-engine socket
idle-timeout guarantee. Requests serialize while a handle is open; a separate
**60s handle lifetime after handoff** closes an abandoned handle and releases
the mutex, even if transport already finished. This is a total ownership deadline,
not a promise of 60s socket inactivity detection.

`ResourceContent` bridges Ktor public scoped `execute {}` to a single-consumer
handle. Reads use the caller buffer. Close cancels channel/producer without
waiting; atomic idempotent close tolerates concurrent cleanup. Opening/read
cancellation, lifetime expiry and shutdown release I/O. Non-scoped
`execute()` (which saves the whole body) is never used. No giant ByteArray,
temporary book file, persistent download or cache.

## Real item and demo

**`gmb-2015-93040`**, *VERLENING OMGEVINGSVERGUNNING, OUDEWEG 2 GERSLOOT*: Dutch
government notice; creators Nederlandse overheid/Dutch government/Heerenveen,
language `dut`, licenseurl **`http://creativecommons.org/publicdomain/zero/1.0/`**;
no separate rights/restriction/lending fields, files not private.
TXT `gmb-2015-93040_djvu.txt`: **2,566 bytes**; PDF: **443,517 bytes**; HTML/other
derivatives also present; no EPUB. TXT MD5 `9b3a9ad6f53116f0442f19b930886212`, SHA-1
`3cfa3a8c0483927844cf78dc1097459dc27b6ccb` supplied, not recomputed.

Earlier candidate `second_annual_tour_of_homes_constantine_michigan_27_september_1975`
had CC0/public-domain rights but was **rejected**: restricted item/private files;
no download attempted. Licensing and access are separate gates.

```sh
./gradlew :app:internetArchiveAcquisitionCheck
```

Explicit opt-in CLI demo searches the known item, gets details, selects TEXT via
neutral format selector, refreshes permission metadata, opens through
`DirectResourceLoader → PublicationSource.loadResource → ResourceContent`, reads
at most 512 bytes, verifies UTF-8 and closes. No text logged. An incomplete trailing
UTF-8 sequence is excluded only at a partial 512-byte boundary, without fetching
more bytes; invalid/truncated UTF-8 at actual EOF fails. A later consumer requires
a fresh handle. Independent of unchanged Gutenberg UI, no global source manager.

**Once at 2026-10-02T23:13:46Z:** 5 requests, HTTP 200/200/200/302/200, one validated
redirect, **11,824 application-consumed bytes**, including **512 resource bytes**.
Known size 2,566; no complete file explicitly consumed/persisted. Transport may
buffer more bytes than the application reads. Earlier development HEAD observed
the same 302; Range 0–31 then returned HTTP 206, 32 bytes,
`text/plain; charset=utf-8`, Content-Range `bytes 0-31/2566`. No IA 403/429.
No graphical UI/reader executed. [Verification](VERIFICATION.md).

**Once at 2026-10-03T07:20:59.244405773Z:** the revised policy passed the same
opt-in check: 5 requests, HTTP 200/200/200/302/200, **11,824 application-consumed
bytes**, including **512 resource bytes**. This time the permalink redirected to
**`https://ia803102.us.archive.org/35/items/gmb-2015-93040/gmb-2015-93040_djvu.txt`**,
matching the fresh MDAPI primary-node/directory pair. One redirect; no 403/429 or
retry. This demonstrates another announced node, not all items/hosts/formats.
Gutenberg/OAPEN live checks were not repeated. No full-file checksum/content
validation, reader or graphical UI was run.

## First Desktop reading consumer — 2026-10-03

Select Internet Archive, submit a search and explicitly press Open text. The
opener fetches details, selects TEXT, opens through DirectResourceLoader, consumes
and closes the complete bounded resource, strictly decodes UTF-8 and passes a
TextDocument to the source-independent Compose TextReader. Back retains the
selected source/query/results. Changing source starts a fresh session; no
cross-source matching or simultaneous search. Search results are not prefetched
or automatically enriched. No compatible TEXT gives a controlled error; PDF/EPUB
are never selected by this reader.

Source rights/access/host/64 MiB limits remain unchanged. Reader requires known,
stable positive size **≤16 MiB**, exact EOF and valid complete UTF-8 (one leading
BOM removed). Whole open operation is bounded to 60s. Each reopening still acquires
again because revisions remain unknown. The later resource cache infrastructure,
persistent progress and Library/History are separate; no persistent Download or
reader settings. PR #10 indexes private temporary TEXT files and lazily displays
bounded windows; [TEXT_READER.md](TEXT_READER.md). The following
new opt-in task uses the same session/controllers, reads the full small verified
government text, reports counts only and verifies Back:

```sh
./gradlew :app:internetArchiveTextReadingCheck
```

It never runs in build/test/check and is not graphical UI validation. Exact live
result and environment limitations are recorded in [VERIFICATION.md](VERIFICATION.md).
The earlier prefix/redirect observations above remain historical evidence.

**Once at 2026-10-03T08:06:48.622357109Z:** full-text check succeeded with 5
requests, HTTP 200/200/200/302/200, one validated redirect to
`dn760105.eu.archive.org`. **13,878 application-consumed bytes**, including the
complete **2,566-byte TEXT**, decoded to **2,562 UTF-16 characters**. Exact
size/EOF and strict UTF-8 passed; handle closed and Back retained query/results.
No 403/429, retry, text logging or persistence. No graphical smoke test.
