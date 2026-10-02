# Official acquisition routes compared — 2026-10-02

Comparison for `Publication → PublicationResource → ResourceContent`, not catalog
coverage or future priority. [OAPEN evidence](OAPEN.md), [IA evidence](INTERNET_ARCHIVE.md).

| Aspect | OAPEN | Internet Archive |
| --- | --- | --- |
| Discovery | REST documented, environment 403; official OAI/feed/export alternatives | Official advanced search JSON, one explicit page verified |
| Metadata | OAI GetRecord 200/10,525 bytes; full MARC/JSON/ONIX/CSV exports | Official item JSON 200/5,359 bytes, files included |
| Client suitability | Incremental/paged harvesting, not free-text search; dump/local indexing outside scope | Search/details need no local catalog/persistence |
| Resource links | MARC books export explicitly includes downloads; XOAI direct OAPEN PDF link | Documented `/download/{identifier}/{filename}` with storage redirect |
| Individual transfer | Reader downloads generally allowed; PDF HEAD403; Ktor OAI timed out | Public CC0 TXT Range206 then production source HTTP200/prefix verified |
| Formats observed | PDF/extracted TXT; no EPUB in one record | TXT/PDF/other derivatives; no EPUB in selected item |
| Rights | Metadata CC0, example PDF CC-BY-NC 3.0 | Selected government text CC0; rights/licenseurl distinct |
| Access | REST address restriction, file anti-bot; no bypass | Private/restricted CC0 candidate rejected; selected item no login/lending |
| Streaming/size | PDF known 9,593,943 bytes; transfer not proved | TXT known 2,566 bytes; bounded ResourceContent/early close verified |
| Hash/revision | MD5 supplied, no byte verification | MD5/SHA-1 supplied; prefix only, revision null |
| Update/rate guidance | Daily feeds, OAI datestamps; no numeric quota found | Bot UA/model, 429/Retry-After/cache/checksum/concurrency guidance; no universal numeric quota found |
| Complexity/risk | Resolve transfer access plus client discovery/index strategy | Conservative license/access gates and verified-host redirects; unknown hosts unsupported |

**Choose one narrow Internet Archive source and CLI demo.** Official search,
item/file metadata, individual transfer and public CC0 item passed real checks.
The decision follows rights/access/byte evidence, not convenience alone. OAPEN
metadata is usable; transfer and client discovery remain separate gates. No
OapenSource, bypass, aggregator, scraping or change to Gutenberg.
[ADR 0011](adr/0011-verified-source-acquisition.md).
