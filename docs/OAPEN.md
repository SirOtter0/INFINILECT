# OAPEN: REST restriction and alternate metadata access

Verified 2026-10-02 on `feature/oapen-acquisition`, based on integrated main
`86d70c1bc4f281e7f866207ff8bfcfed647e058d`. **REST access blocked from this
environment does not mean OAPEN is unusable.** Official OAI-PMH metadata was
accessible and announced a PDF download URL. Its transfer remains unverified:
PDF HEAD returned HTTP 403; the opt-in Ktor alternate check later timed out before
an HTTP response. No challenge bypass or OapenSource exists.

Internet Archive is the first verified acquisition implementation; see
[comparison](ACQUISITION_COMPARISON.md), [Internet Archive](INTERNET_ARCHIVE.md)
and [ADR 0011](adr/0011-verified-source-acquisition.md).
[ADR 0010](adr/0010-oapen-verification-gate.md) preserves the original REST investigation.

## Official metadata mechanisms

[OAPEN Library metadata](https://www.oapen.org/librarians/oapen-library-metadata)
explicitly targets libraries/aggregators and licenses **all metadata feeds under
CC0 1.0**. [Library services](https://www.oapen.org/librarians/services-libraries)
states that metadata feeds are updated daily. This concerns metadata, not book
rights or the website's general CC BY 4.0 footer.

| Mechanism | Official URL / format | Download information and client suitability |
| --- | --- | --- |
| MARCXML, all | `https://memo.oapen.org/file/oapen/OAPENLibrary_MARCXML.xml` | Full library export, not a per-query catalog API |
| MARCXML with links | `https://memo.oapen.org/file/oapen/OAPENLibrary_MARCXML_books.xml` | Explicitly books containing download links; `_chapters.xml` also exists |
| MARCXML without links | `https://memo.oapen.org/file/oapen/OAPENLibrary_MARCXML_no_links.xml` | Deliberately omits download links |
| KBART | `https://memo.oapen.org/file/oapen/OAPENLibrary_KBART.tsv`, `_books.tsv` | TSV full exports for knowledge bases |
| ONIX 3.0 | `https://memo.oapen.org/file/oapen/OAPENLibrary_ONIX.xml` | Full XML export, not downloaded |
| JSON / CSV | `https://memo.oapen.org/file/oapen/OAPENLibrary.json`, `OAPENLibrary.csv` | All full-text titles; not downloaded or assumed to have a particular schema |
| RIS | `https://library.oapen.org/download-export?format=ris` | Export, not downloaded |
| OAI-PMH | `https://library.oapen.org/oai/request` | ListMetadataFormats, ListRecords with metadataPrefix=xoai, one documented GetRecord |
| RSS / Atom | `https://library.oapen.org/feed/rss_1.0/site`, `rss_2.0/site`, `atom_1.0/site` | Discovery feeds, not assumed to provide arbitrary full-text search or complete resources |
| Selective export | [Official explanation](https://oapen.hypotheses.org/261) | Selected sets, recommended at most 500 titles; no export requested |

MARC books export HEAD returned HTTP 200/application/xml without Content-Length.
Approximate dump size is **unknown**; no complete dump was downloaded. OAI-PMH
allows minimal/incremental harvesting: the
[official protocol](https://www.openarchives.org/OAI/openarchivesprotocol.html)
defines `from`/`until` datestamp filters and `resumptionToken` continuations.
Repository page sizes/token behavior were not exercised. Harvesting is not a
free-text search API; a local catalog/index would introduce persistence outside
this slice. No numeric request quota was found in the consulted OAPEN pages;
no parallel requests/retries/bulk harvest occurred.

## Minimal alternate experiment

OAPEN documents this exact example:

```text
https://library.oapen.org/oai/request?verb=GetRecord&identifier=oai:library.oapen.org:20.500.12657/25287&metadataPrefix=xoai
```

A development GET returned HTTP **200**, `text/xml;charset=UTF-8`, **10,525 bytes**,
no redirect. XOAI elements/fields supplied:

- Identifier `20.500.12657/25287`; title *The deliverance of open access books*;
  author Ronald Snijder; language `eng`; type `book`; canonical handle
  `http://library.oapen.org/handle/20.500.12657/25287`.
- ORIGINAL PDF `9789085551201_OA_version.pdf`, MIME `application/pdf`,
  **9,593,943 bytes**, MD5 and direct link
  `https://library.oapen.org/bitstream/20.500.12657/25287/1/9789085551201_OA_version.pdf`.
- PDF rights **`CC-BY-NC`** and license URL
  **`http://creativecommons.org/licenses/by-nc/3.0/`**, separate supplied values.
- Extracted `text/plain` derivative (**488,807 bytes**), thumbnail and metadata
  exports. The text derivative lacked its own rights fields; none were invented.
  No EPUB in this record; this is not a catalog-wide claim.

The download link points to OAPEN, not a third party. Development HEAD returned
**HTTP 403**, no redirect, with an Anubis challenge indicated by response headers.
Zero PDF body bytes consumed. No JavaScript/proof-of-work, cookies, alternative
host or authentication workaround was attempted.
[Reader guidance](https://www.oapen.org/researchers) permits downloads without fees
or registration; export documentation explicitly includes download links. The
remaining gate is successful permitted transfer/access, not blanket absence of
an official downloadable-link mechanism.

```sh
./gradlew :app:oapenAlternateAccessCheck
```

Independent opt-in check: one example record, hardened JDK StAX extraction and
PDF HEAD only if metadata succeeds. No redirects/file download. Fixed HTTPS host,
exact example ID/bitstream path, 2 MiB metadata limit plus one overflow probe,
8 KiB buffer, connect 5s/request 15s, XML depth 32, 20,000 events, 32 bitstreams,
16 attributes/namespaces, 16 KiB fields/attributes. DTD/external entities forbidden;
cancellation/close releases I/O. As of the 2026-10-03 review, all clients/probes
share `INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)`.
The initial run below used the former experimental tool/model identification;
only identification was normalized, and no OAPEN check was rerun.

**Run once at 2026-10-02T23:11:53Z:** GET timed out after 15s before HTTP status;
zero application-consumed bytes, HEAD not issued. Task exit 1; no retry. This
does not erase the earlier curl HTTP 200/403 observations.

## Historical REST evidence (retained)

[Official REST search](https://www.oapen.org/article/8185269-search-using-a-rest-api)
and linked [guide](https://oapen.o172i.upcloudobjects.com/151b0a45669f429381cb46fe441f23b6.pdf)
document `/rest/search`, `query`, `expand=metadata,bitstreams`, `limit`, `offset`.
Two initial development requests returned HTTP 403 with the exact body:

```text
You address is not allowed to access this API.
```

Existing `./gradlew :app:oapenApiAccessCheck --args=water` was previously run once:
HTTP 403, zero bytes consumed, exit 1. **Not rerun** in this pass. No successful
REST schema or universal authentication requirement is inferred.
[POSI self-audit](https://www.oapen.org/oapen/posi-self-audit) distinguishes open
metadata from publisher-licensed publications.

Before OAPEN implementation, resolve permitted access to announced files and a
documented discovery strategy suitable for clients. Do not require REST success
if another official route works, invent free-text search over harvesting, or
treat open access/CC0 metadata as public-domain books. No contact sent on the
user's behalf. [Actual verification](VERIFICATION.md).
