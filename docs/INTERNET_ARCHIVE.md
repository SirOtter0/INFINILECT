# Internet Archive: first bounded resource acquisition

Official documentation and real integration verified **2026-10-02**. Narrow
desktop adapter for public **CC0 text items**, plus CLI demonstration; not complete
Archive support or a reader. Gutenberg UI/policy unchanged. Core has no source,
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
- [Metadata schema](https://archive.org/developers/metadata-schema/index.html):
  `licenseurl` identifies a license; `rights` is a separate statement;
  `access-restricted-item` flags restrictions. Neither Archive presence nor
  successful metadata access proves download permission.
- [Official download guidance](https://archive.org/developers/internetarchive/cli.html):
  individual files, formats and HTTP Range. Some EPUB derivatives are generated
  on demand; no such route is implemented here.
- [Automated access](https://archive.org/developers/bots.html): descriptive
  tool/version User-Agent, AI agent/model identification; respect 429/Retry-After,
  limit concurrency, delay bulk work, avoid unchanged downloads/check checksums,
  cache responses. No universal numeric search/read quota found. This slice is
  serialized, with no bulk action, automatic retry or prefetch. HTTP 429 stops
  the action without an automatic retry; any future retry must honor Retry-After.
  No cache/login introduced. Permission
  metadata is refreshed before opening rather than cached across actions.

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
preserves `rights` and `licenseurl` separately; existing `Publication.rights`
contains verbatim rights if supplied, otherwise verbatim license URL. Never
concatenates/translates them or converts them to “public domain”.

Acquirable files require exact CC0 1.0 license URL, `mediatype=texts`, no
restricted/dark/lending flags or known restricted collections, no file-private/
restricted flag. File-specific rights or a non-CC0 file license are excluded
rather than overridden by item CC0. Conservative subset, not exhaustive Archive access policy.
Unknown licensing yields no selectable files. Only `DjVuTXT`/`Text` plus `.txt`
map to TEXT/`text/plain`; `Text PDF`/`PDF` plus `.pdf` map to PDF/`application/pdf`.
These MIME expectations are validated against HTTP Content-Type, not invented
source MIME fields. No EPUB/HTML/archive acquisition. Demo verifies a UTF-8 TEXT
prefix, not all files' charsets or PDF validity.

Resource keys are validated filenames, never URLs. Size must be known, positive,
at most 64 MiB. Hashes supplied by IA are not verified by a prefix check:
**revision=null**, no unconditional cache key. Full-stream checksum verification deferred.

## URLs, hosts and streaming

Identifiers: first ASCII alphanumeric, then alphanumeric/`_-.`, max 100;
additionally reject `..`. Filename: narrow ASCII basename, max 200, with spaces
encoded as a segment. Nested/non-ASCII names, percent encodings, separators,
schemes, controls/traversal unsupported. Ownership and the whole resource
reference are checked against **fresh metadata** before acquisition.

Exact acquisition allowlist: **`archive.org`** (official permalink) and
**`dn760105.eu.archive.org`** (observed delivery host). No wildcard CDN allowlist.
Metadata hints `ia903102.us.archive.org`/`ia803102.us.archive.org` were observed,
are **not enabled**, and do not construct file URLs. Unknown delivery hosts fail
closed pending evidence/review; acquisition of other items is deliberately limited.

At most two redirects, loop detection, HTTPS/default or 443 port, no userinfo/
fragment/query, exact identifier/filename, permanent path or observed numeric
`/N/items/{identifier}/{filename}` storage shape. Traversal/ambiguous percent
encodings rejected before URI normalization. No configured cookies/credentials.

Require HTTP 200, expected Content-Type, identity encoding. Content-Length must
match file metadata; actual reads reject overlong/truncated streams. Metadata:
2 MiB plus one overflow probe, 8 KiB buffer, JSON depth 32, 200 files/array values,
16 KiB mapped strings. Strict UTF-8/bounded nesting precede kotlinx.serialization
parsing. Connect 5s, metadata request 15s, file request 60s; no Java-engine socket
idle-timeout guarantee. Requests serialize while a handle is open.

`ResourceContent` bridges Ktor public scoped `execute {}` to a single-consumer
handle. Reads use the caller buffer. Close cancels channel/producer without
waiting; opening/read cancellation and shutdown release I/O. Non-scoped
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
UTF-8 sequence is excluded without fetching more bytes. A later consumer requires
a fresh handle. Independent of unchanged Gutenberg UI, no global source manager.

**Once at 2026-10-02T23:13:46Z:** 5 requests, HTTP 200/200/200/302/200, one validated
redirect, **11,824 application-consumed bytes**, including **512 resource bytes**.
Known size 2,566; no complete file explicitly consumed/persisted. Transport may
buffer more bytes than the application reads. Earlier development HEAD observed
the same 302; Range 0–31 then returned HTTP 206, 32 bytes,
`text/plain; charset=utf-8`, Content-Range `bytes 0-31/2566`. No IA 403/429.
No graphical UI/reader executed. [Verification](VERIFICATION.md).
