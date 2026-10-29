# OAPEN: verified metadata lookup and acquisition restrictions


## PR #38 — current status, 2026-10-29

**Partial integration, not ready for the full offline-reading acceptance criteria.** OAPEN is selectable under Search → Sources & filters. Paste a handle such as `20.500.12657/25287`, or its exact HTTPS OAPEN handle page, to resolve one public record. The UI offers the official catalog for title/author/subject searches and explicitly explains that integrated free-text search and downloads are pending verified access. No hardcoded sample results are shipped.

The current network denies both REST search and file transfer. This PR implements only the permitted, verified metadata path. It does **not** register OAPEN as an OfflineDownloadProvider, return acquisition resources, enable a reader for OAPEN, or claim that a PDF/EPUB has been downloaded. All per-file licenses and MIME declarations are display metadata, not grants inferred from a website footer. Internet Archive retains its existing provider and policy unchanged.

### Official documentation and observations

Consulted primary sources:

- [Current metadata page](https://oapen.org/librarians/metadata): OAI-PMH GetRecord example, exports, metadata feeds licensed CC0. The live page was fetched successfully on 2026-10-29. Its current HTML does not expose the REST section present in older indexed versions.
- [Previously documented REST search](https://www.oapen.org/article/8185269-search-using-a-rest-api): indexed documentation describes `/rest/search`, `query`, `expand`, `limit`, `offset`, fielded Solr searches and JSON/XML Accept headers. A direct current fetch of the canonical no-www page returned **404**. This is historical evidence, not a promise of a supported current production contract.
- [Linked REST guide](https://oapen.o172i.upcloudobjects.com/151b0a45669f429381cb46fe441f23b6.pdf): downloaded successfully; describes query/fq/sort and limit/offset pagination, but no complete observed current response schema. No REST mapping was guessed from a DSpace version.
- [POSI self-audit](https://oapen.org/oapen/posi-self-audit): publisher licenses govern books independently of CC0 metadata. It is not a universal Creative Commons acquisition grant.
- [Official OAI-PMH specification](https://www.openarchives.org/OAI/openarchivesprotocol.html): GetRecord is identifier lookup, not arbitrary search. No full-library harvest or local index is introduced.

| Request, 2026-10-29 UTC | Observed result | Consequence |
| --- | --- | --- |
| REST `/rest/search?query=handle:%2220.500.12657/25287%22&expand=metadata,bitstreams&limit=2&offset=0` | HTTP 403 | No JSON schema or pagination verified |
| REST `/rest/search?query=water&expand=metadata,bitstreams&limit=1&offset=0`, identified INFINILECT client | HTTP 403, `You address is not allowed to access this API.` | No alternate host, proxy rotation or challenge bypass attempted |
| OAI `/oai/request?verb=GetRecord&identifier=oai:library.oapen.org:20.500.12657/25287&metadataPrefix=xoai` | HTTP 200, valid XOAI | Actual observed record is a reproducible test fixture |
| Announced `https://library.oapen.org/bitstream/20.500.12657/25287/1/9789085551201_OA_version.pdf` | GET HTTP 403, HTML Anubis challenge | **Not a PDF download**; body never passed to a reader or offline manager |

A small number of sequential read-only requests were made. No load test, bulk download, challenge solution, cookies/authentication workaround, contact or submission on behalf of the user occurred. No numeric rate limit, universal authentication policy or request quota was verified; one metadata request at a time, no automatic retries and explicit user action are the conservative implementation limits. The observed IP denial does not establish that every user's network will be denied, nor that the licensing itself prohibits downloads.

The record verified through OAI is *The deliverance of open access books*, Ronald Snijder, 2019, `eng`, Amsterdam University Press, DOI `10.26530/OAPEN_1004809`, ISBN `9789085551201`. The ORIGINAL bundle announces PDF `application/pdf`, 9,593,943 bytes and file-specific `CC-BY-NC` / `http://creativecommons.org/licenses/by-nc/3.0/`. It is not treated as CC0 or as a universally reusable book. There is no ORIGINAL EPUB in this record; no catalog-wide EPUB claim is made. Derivative TEXT/THUMBNAIL/EXPORT bundles are excluded from the announced original-format display. No commercial redistribution/modification right is inferred.

### Implementation and architecture

- `OapenSource` is shared JVM code used by Android and Desktop through the existing platform HTTP engine. It implements PublicationSource and the optional display-only PublicationBibliographySource. Its acquisition method fails before network I/O. No new module/dependency/permission or storage schema exists.
- Source namespace `oapen` and stable OAPEN handle are used for PublicationId; no title identity and no collision with Internet Archive. It is listed separately and **not enabled by default** in discovery or Home. Merely starting the application, switching tabs or composing a card makes no OAPEN request.
- Only exact handles with prefix `20.500.12657` and numeric suffix are supported. Exact canonical HTTPS handle-page input is accepted. DOI/ISBN lookup, title/author/language/subject server filters, arbitrary handles and free-text search are **not implemented**; the official browser catalog is offered. Lookup has no pagination and rejects non-null page tokens instead of fabricating continuation tokens.
- OAI responses are bounded to 1 MiB using an 8 KiB buffer and one overflow byte; connect 5s/socket 10s/request 15s timeouts; fixed HTTPS host/path, encoded fixed query parameters, redirects entirely rejected and no retries or credential use. No metadata URL can change a request destination or local path.
- SAX parsing uses Android/JVM public APIs with strict UTF-8, namespace checks, entity/DTD rejection, lexical handler and no external resource resolution. Bounds: depth 32, 4,096 elements, 16 attributes per element, 16 KiB attributes/text. Cancellation checks run during read/parse; parsing runs outside the main thread. Exact record identity is verified; deleted/missing records yield no result; malformed records fail safely.
- Optional metadata includes original title/authors/description/language, publisher/date/subjects, DOI/ISBN, original-file MIME declarations and verbatim rights. Missing optional fields stay absent, never manufactured. Descriptions render as bounded inert plain text; no HTML engine or external images are added.
- Existing discovery caching deduplicates same-ID/query results and shares requests, without a new cache. Its eight-page bound, cancellation and obsolete-generation rejection remain intact. Details perform one cancellable, optional lookup; language changes do not requery or reset reading state.
- Shared details show bibliography and an explicit restricted-capability notice, with a canonical official-record browser action. Saved Library details resolve optional metadata using the source without adding resources to snapshots or altering records. Browser destinations are built solely from validated handles, not untrusted metadata links. Errors use reviewed resources and never raw HTTP/exception bodies.
- Ten new resource keys in English, Spanish and Catalan. Existing compact layout/scrolling/touch targets are reused; no reader, navigation-bar or Library redesign.

### Acquisition gate and remaining work

Before enabling offline acquisition, obtain official access guidance for REST/file transfers and verify a successful permitted transfer with the real source. Then verify the actual current search schema/pagination and map only advertised authorized resources. License policy must handle file-level overrides, non-CC/ambiguous/third-party material conservatively, retain attribution, and accept only reviewed hosts/routes with bounded redirects. EPUB must be separately observed and validated. No assumptions based on the free-access claim, title age, extension or Content-Type alone are sufficient.

At that point register OAPEN with the existing FileOfflineDownloads provider list and existing PDF/EPUB preparers. That manager already owns consent, quota, single-worker concurrency, progress, effective cancellation, atomic completion, hash/format validation, private hashed paths, retry and retained-copy deletion. None of these mechanisms has been duplicated or relaxed here. Until the gate is resolved, **OAPEN offline reading, download persistence and cancellation of OAPEN file transfers are not acceptance-tested or claimed**. Internet Archive's offline tests remain regression coverage, not evidence of OAPEN downloads.

### Testing the partial integration

Android/Desktop: open Search → Sources & filters, select OAPEN (optionally deselect other sources), paste `20.500.12657/25287`, then open Details. Expect bibliographic metadata and the official record action; no download or reading action. Add/remove the metadata-only Library entry if desired; no file/progress record is created. Reopen its details to resolve optional bibliography. Try a missing numeric handle, network-off errors/retry, a plain-text query (honest handle-lookup explanation plus official catalog action), es/en/ca, light/dark, small screens and large fonts. Switching language must not alter the publication, Library data or request count. A browser is used only after an explicit tap; browser access/download is outside INFINILECT's verified transfer path.

`./gradlew :app:oapenLookupCheck` is an opt-in single GetRecord experiment using the real shared adapter; normal suites use the captured CC0 public metadata fixture and MockEngine, never the live server. Existing `oapenApiAccessCheck` / `oapenAlternateAccessCheck` remain development probes, not production dependencies. Final test/build results for PR #38 are recorded below. No physical Android verification or APK publication is claimed.


### Final verification — PR #38

Code tested: runtime implementation `c2459317cd9515767e9284e94ba161ee1a99fc4d`, with the deterministic test-scheduler correction in `001162f541c986846648ac106bec569f6023960e`. All source/resource hashes were checked against the frozen inputs; subsequent changes are documentation only.

| Suite | Cases | Failures / errors / skipped |
| --- | ---: | --- |
| Desktop | 1,713 | 0 / 0 / 0 |
| Android-host | 1,432 | 0 / 0 / 0 |
| Core JVM | 59 | 0 / 0 / 0 |
| Core Android-host | 59 | 0 / 0 / 0 |
| **Total** | **3,263** | **0 / 0 / 0** |

Final full run: **BUILD SUCCESSFUL, 12m 28s**, JDK 21 / SDK 37 / Gradle 9.7.1, one Gradle worker, test tasks forced to execute with the managed environment's verified Skiko path. Existing tests/assertions were not removed, disabled or weakened. Equivalent tasks:

```sh
./gradlew --max-workers=1 --continue :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest :androidApp:assembleDebug \
  :androidApp:assembleRelease :androidApp:verifyDebugLanguageResources \
  :androidApp:check :androidApp:lintRelease :desktopApp:compileKotlin
python3 tools/check_localization.py
git diff --check
```

Android debug and unsigned release APKs and Desktop compiled. Lint debug/release: **zero issues** in both XML reports. Localization: **551 strings and 3 plurals in en/es/ca**, 193 production Kotlin files scanned; all six locale CVRs match generated resources in each APK. Test record XML, test integration entrypoint classes and signing files are absent from the APKs. Neither APK has been published, uploaded or offered as a physically validated installation.

New coverage: 16 shared source tests (run on both targets) and two Desktop shared-UI tests, including 18 language/theme/size/font combinations and browser-action/error handling. Earlier focused verification passed **65 Desktop + 20 Android-host** cases. Coalescence uses both a controlled MockEngine dispatcher and controlled parser dispatcher so a real IO thread cannot race the test clock's virtual timeout; production timeouts/concurrency were not changed.

An earlier complete run failed that scheduling test and the existing CBZ `PageSpreadLayoutTest.sparseFastTouchAtEitherEdgeTurnsOnceInBothLayoutsAndReadingDirections` (initial spread not presented in the fixture's bounded wait). The scheduling test was corrected, then the complete suites were rerun. The unchanged CBZ case passed focused rerun, an isolated run on pristine main `6a34f4d63e4f868bd61c4de96cb865feb3a80b2a`, and the final complete run. **Its intermittent host-test failure has no confirmed root cause and is not claimed fixed by OAPEN.** No reader, native decoder, assertion or wait was modified to obtain the final result. These host render tests do not establish physical Android behavior.

Diff review confirms no changes to Internet Archive, the retained-download manager, readers, translation/models, core, SQL/migrations, updater, Android manifest/permissions, signing or SDK configuration. Their regression tests passed in the final suites. The new Android registry expectation explicitly includes OAPEN with every reading flag false and no OfflineDownloadProvider capability; it retains the checks for the other sources.

The opt-in real `:app:oapenLookupCheck` then passed (**BUILD SUCCESSFUL, 15s**, 2026-10-29). It used the new shared OapenSource with the real Desktop Java engine, the project User-Agent and the fixed public GetRecord endpoint: bibliography parsed, publisher/description/identifiers present, one announced format and **zero file requests**. Network proxy/CA properties were forwarded to JavaExec by an external environment init script, with TLS verification retained; no networking workaround was added to the application. This validates a real host metadata lookup, not Android networking, REST search, file transfer or offline reading.

Remaining acceptance blockers: integrated free-text discovery and REST pagination, a successful permitted PDF transfer, any verified EPUB transfer, OAPEN retained-copy/offline/progress integration, and physical Android validation. A green host/build result does not remove those gates. Keep Draft; do not merge or publish releases/APKs.

---

## Historical investigation (2026-10-02)

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
