# Wikisource: under-demand catalog, EPUB and retained reading — PR #39

Continuation of `3ff47bba94dbeead061d5dac5e3e24c0fd453f09`, **31 October 2026**.
The catalog no longer intersects results with an incorporated list of titles,
page IDs, revisions, text fingerprints or attribution URLs. A single adapter is
configured for **Spanish, English and Catalan**. It searches public pages under
demand, including author and index namespaces. Search never exports a book.

This is **not universal book completeness, copyright clearance or EPUB support**.
Unknown pages remain exploratory documents. Individual pages may have only web
information, insufficient rights evidence, a potential export, a previously
validated export, or a controlled reader/download error. An ordinary API hit,
namespace 0, a header, a MIME label and HTTP 200 do not prove a complete book.
No PDF/EPUB2 export or other linguistic project is advertised. The generic EPUB
boundary itself continues to support its existing EPUB2/3 subset.

PR #39 remains Draft. Android physical acceptance is pending. No APK/release is
published. PR #38/OAPEN, Gutenberg, Internet Archive's policy, updater, permissions,
signing, SDK, dependencies and persistent schemas are unchanged.

## Official references and observations

- [Action API](https://www.mediawiki.org/wiki/API:Action_API),
  [Search / generator](https://www.mediawiki.org/wiki/API:Search),
  [Revisions](https://www.mediawiki.org/wiki/API:Revisions),
  [Allpages](https://www.mediawiki.org/wiki/API:Allpages).
- [ProofreadPage index / scan model](https://www.mediawiki.org/wiki/Extension:ProofreadPage),
  [WS Export](https://wikisource.org/wiki/Wikisource:WSexport),
  [export service](https://ws-export.wmcloud.org/).
- [Wikimedia User-Agent policy](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy),
  [API etiquette](https://www.mediawiki.org/wiki/API:Etiquette),
  [terms of use](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use).
- [Spanish rights policy](https://es.wikisource.org/wiki/Wikisource:Derechos_de_autor),
  [English copyright policy](https://en.wikisource.org/wiki/Wikisource:Copyright_policy),
  [Catalan rights policy](https://ca.wikisource.org/wiki/Viquitexts:Drets_d%27autor).
- [English PD-old declaration](https://en.wikisource.org/wiki/Template:PD-old),
  [Commons PD-scan](https://commons.wikimedia.org/wiki/Template:PD-scan),
  [PD-old-100-expired](https://commons.wikimedia.org/wiki/Template:PD-old-100-expired),
  [Commons licensing](https://commons.wikimedia.org/wiki/Commons:Licensing).
- EPUB/XML/HTML specifications and retained DOCTYPE/citation protections:
  [EPUB boundary](EPUB.md).

Small read-only research calls on 31 October verified official siteinfo, page
metadata, wikitext declarations, indexes and Commons file declarations in all
three editions. Namespace IDs differ: ES author 106 / index 104; EN author 102 /
index 106; CA author 106 / index 104 (`Llibre`). Site rightsinfo declares CC BY-SA
4.0 for contributions; **this is not an original-work license**. No HTML scraping,
service protection bypass, massive crawl or private wiki request was used.

## Architecture and API

`WikisourceEdition` internally fixes project, API, language and namespace IDs.
Only `es.wikisource.org`, `en.wikisource.org`, `ca.wikisource.org`, the fixed
Commons Action API (scan-rights metadata only), and `ws-export.wmcloud.org` are
used. Remote data cannot select another project, API, path or export server.

`WikisourceSource` owns transport/lifetime and delegates to:

- `WikisourceCatalog`: general queries, page identities, pagination, metadata and
  small cache. `WikisourceJson` applies strict UTF-8/JSON bounds and duplicate-key
  rejection. `WikiTextMetadata` reads bounded declarations, not rendered HTML or
  an executable MediaWiki interpreter.
- `WikisourceRights`: separate positive-evidence rules. No title/page/revision
  allowlist. Unknown syntax, missing evidence and contradictory restrictions
  fail closed for new acquisition, while metadata remains useful.
- `WikisourceExportValidation`: pure technical identity/semantic validation,
  after all ordinary `FileEpubPreparer` ZIP/XML/resource validation. The exact
  acquired/retained `Publication` snapshot is passed to the optional rejection
  hook. It cannot bypass the preparer or require a live network on reopening.
- Common `CatalogPublicationSource` / `CatalogPublicationInfo`: individual page
  capability and resolved bibliographic snapshot, separate from source-wide
  implemented reader capability. Details show localized classification and EPUB
  state, available authors/rights and an explicit official-page browser action.
  Delayed metadata for another page cannot enable its controls. Retained copies
  remain readable/removable even if current metadata fails.

Official metadata requests use `action=query&format=json&formatversion=2&maxlag=5`:

| Operation | Parameters / authority |
| --- | --- |
| Terms/title/author-name search | `generator=search`, encoded `gsrsearch`, configured main/author/index namespaces, `gsrlimit=20`, `gsroffset`; inexpensive `info|pageprops|revisions`, `rvprop=ids` (no `rvlimit` on a generator). |
| Selected page | Positive numeric `pageids`, same metadata plus `rvprop=ids|content&rvslots=main&rvlimit=1`. |
| General Home browsing | Under-demand `list=allpages`, namespace 0, nonredirects, 20/page, then metadata enrichment of only those IDs. No featured title list. |
| Scan-rights chain, if applicable | Selected page's single ProofreadPage index → matching index namespace record → same-named Commons File record. Only fixed official APIs, never a metadata-supplied file URL. |
| EPUB export, explicit open/download | `https://ws-export.wmcloud.org/?lang=<configured>&page=<encoded canonical title>&format=epub-3`. |

Search includes author pages and full-text matches of author names. This is not
an exhaustive structured “all works by author” index: project markup varies.
No author is invented from snippets; the selected page's plain header author is
used when reliable. Missing covers/descriptions/publishers/ISBNs remain absent.
Users can use normal MediaWiki search terms, including `intitle:`. Query text is
encoded as data and cannot inject URL parameters.

Only the selected page requests categories: category-property continuation must
not mask the search generator's next page. Real requests exposed and corrected
MediaWiki's rejection of `rvlimit` for multiple pages and the official
`proofread-index` content model on indexes. Synthetic regressions now enforce
both contracts. Search rank is retained when supplied; IDs are deduplicated within a page and by
the existing discovery controller across loaded pages. Opaque continuation
values are query **and edition** bound, never network URLs; search offsets stop
at 1,000 and the discovery UI's existing loaded-result limits still apply.
Requests are cancelable. Hiding a provider retires its generation and prevents
late results from returning. An unrecognized page remains `DOCUMENT`, not `BOOK`.
Author/index/disambiguation/versions evidence excludes acquisition; a header's
nonempty section or proofread slice is displayed as a section, without promising
the entire source volume. No generic complete-work inference is introduced.

## Rights policy, independent of parser validity

Positive rules currently implemented:

1. English namespace-0 page with a **direct, unparameterized, standalone
   `{{PD-old}}` declaration**. This template explicitly declares publication
   expiry and at least 100 years since the author's death. Unknown alternate PD
   declarations, copyright warnings, examples and ambiguous translation/edition
   scope do not grant. This is source evidence, not a warranty about every country.
2. A single proofread-page slice, with no unverified descendant TOC/transclusions
   or additional prose/artwork, tied to a matching official index and Commons
   scan. The Commons file must explicitly declare `PD-scan|PD-old-100-expired`.
   The declaration must be top-level or the Book's actual Permission field.
   Automatic-expiry/death-year inference is not enabled; a device clock is not
   authoritative rights evidence.
   Generic “Public domain”, `Copyrighted=False`, PD-old-80, mismatching files,
   unknown/contradictory claims and a site-wide footer are insufficient.

These rules accept arbitrary titles satisfying the evidence, not arbitrary
licenses. Other valid free licenses may remain unrecognized and catalog-only.
The UI states that evidence is insufficient and offers the official page; it
does not suggest a corrupt file or universal permission. Broader license patterns
need documented semantic/territorial analysis and reproducible tests, not more
approved-title entries. Runtime template definitions are not evaluated; the
semantics of recognized declarations are a reviewed policy dependency.

Original works, modern editions/translations, scan reproductions, wiki
contributions and API facts are distinct. The official declaration and revision
references are retained as source evidence. The EPUB's own separate `dc:rights`,
credit text and links are preserved **without substituting the website's CC
BY-SA 4.0, merging licenses or applying INFINILECT's GPL to a book**. A valid EPUB
alone never grants download rights. Profile/country is not sent or used as a
false legal-eligibility proof. No credentials or book text are logged.

**Legacy El Matadero:** existing copies retain `wikisource-es` / page `1425`,
resource `epub3-policy1-r1393104`, original bytes and progress keys. Reopening uses
only the retained snapshot and hash-checked file. The new generic policy does
not grandfather a title whitelist: its current header does not itself establish
one of the automatic positive declarations, so a **new acquisition** can be
catalog-only. This does not revoke, overwrite, hide or reauthorize old saved
copies. Additional general rights rules would need independently verified data.

## Dynamic EPUB validation, limits and versioning

No exact chapter count, text/reference hash, fixed rights list or known revision
is required. After complete structural validation, the policy binds configured
edition/page identity, EPUB resource key, language and the official OPF identifier
to the source snapshot's canonical page URL. An old first-letter case alias is
accepted only together with an exact bibliographic title match. Bibliographic
titles may omit an edition suffix if the identifier matches exactly. All spine
items are interpreted with the real bounded parser windows; nonempty readable
content is required. Fingerprints remain an **opt-in evidence tool only**.

Original ZIP/XHTML is never rewritten. HTTPS-only passive citations, inert
DOCTYPE forms, all rights/attributions and internal navigation are preserved.
Scripts, remote images/CSS/fonts/embedded resources, external DTD/entities,
unsafe paths, ZIP bombs and XML/resource excesses remain rejected by the same
production parser. A narrowly attribute-limited XHTML `link` with a single qualified RDFa relation
(e.g. `dc:relation`, not a provider-name rule) is retained as inert metadata.
Only `rel`/`href` attributes and a strictly valid HTTPS target qualify; mixed
stylesheet/preload relations, loading hints and other URL-bearing nodes keep
resource validation. This fixes the actual Catalan interlanguage annotation
`mw:PageProp/Language` without granting access to an external resource. The
reader does not evaluate RDFa or render/fetch these annotations. Their original
bytes remain intact. [RDFa Core](https://www.w3.org/TR/rdfa-core/) and
[HTML link-type distinction](https://html.spec.whatwg.org/multipage/links.html#links)
were checked on 31 October. No automatic network request/browser opening is
introduced by EPUB analysis. The details dialog's separate official-page button opens a
validated HTTPS project page only after an explicit user click.

| Bound | Current policy |
| --- | --- |
| Metadata transport / selected wikitext string | 1 MiB / 512 Ki UTF-16 units, strict UTF-8 and duplicate-key rejection; depth 24, 10,000 structural tokens. The larger metadata envelope is for one selected page, not EPUB/XML limits. |
| Detailed cache | 32 records, 120 s TTL; raw wikitext discarded after evidence evaluation. No whole catalog/index cached. |
| Technical validation observations | At most 32 page/resource pairs per source/session; not immutable future-byte promises. Every acquired file is still validated. |
| Network | One metadata operation and one streamed export per configured project; existing global retained-download concurrency remains one. No new background crawler/retries. |
| Timeouts | Connect 5 s, metadata total 20 s/socket 15 s; export request/handle 60 s. |
| HTTP errors | No HTTP redirects, including same-site redirects. 403/404/5xx fail; 429 Retry-After/maxlag/busy impose bounded 30–300 s cooldown, then explicit retry. Provider response text is not surfaced. |
| EPUB | 32 MiB archive, 64 MiB expanded, 8 MiB entry, ratio 100, 512 ZIP entries, 1 MiB XML, 256 manifest items, 128 spine entries; existing semantic quotas unchanged. |

Source ID is `wikisource-<language>`, local ID the positive MediaWiki page ID.
Resource key remains `epub3-policy1-r<root revision>`, including existing books.
The root revision is checked fresh before and at EOF after export, including
rights evidence. It is **not** a snapshot of all templates, images or descendant
revisions; WS Export is dynamic. `resource.revision` remains null so changing ZIP
bytes are never an unconditional resource-cache hit. A retained copy stores its
complete SHA-256 and original publication/resource snapshot. No automatic update
or overwrite occurs: the existing store rejects a new pending download while
that ID already has a completed copy. Explicit removal/reacquisition is required;
there is no cross-edition locator migration. Exact-position checks here concern
the **same retained bytes**. After explicit deletion/reacquisition, descendants
can have changed without a new root revision; the existing reader may restore
an approximate position or fall back to a valid location. No exact cross-edit
position guarantee is made. All actual acquisitions
are fully validated regardless of the last observed session state.

## Offline route and source visibility

The existing manager owns consent, streamed progress (indeterminate when size is
unknown), cancellation, explicit retry, quotas, atomic publish, partial cleanup,
leases, deletion and recovery. No second manager, SQL schema or import copy is
created. Abandoning a dialog does not leak a source/client; source cancellation
and stream cleanup run on owned IO jobs, and final application closure joins the
Wikisource owners. Android background/process recovery semantics are unchanged:
interrupted downloads require explicit retry; no HTTP-range resume is promised.

Opening first tries the real private retained copy; its source and transport can
be closed. A new store/reader/progress writer can reopen it without API/WS Export.
Library membership, statuses, favorites, tags, History and reading positions stay
independent. Removing the downloaded file does not remove those records.

“Show sources without integrated reading” remains **false by default**, persisted
with existing preferences. All three Wikisource editions have implemented EPUB
routes but page capabilities vary. Internet Archive remains visible; Gutenberg
remains catalog-only/hidden initially. Capability is implementation-based, never
source-name exceptions or transient network health. Library and History always
use the full source registry, even when a discovery provider is hidden.

## Reproducible checks and recorded results

Ordinary suites use original synthetic EPUBs and deterministic HTTP fixtures.
Coverage includes es/en/ca, general queries/author namespaces, bound pagination,
classification, incomplete metadata, strict JSON, cancellation, HTTP/redirect/
rate limits, dynamic rights, unknown/contradictory claims, scan binding, arbitrary
EPUB2/3 shapes/attributions, identity mismatch, legacy keys, unsafe resources,
revision changes, partial cleanup, retained restart, no overwrite, source-closed
opening, UI late results, translated narrow/large-font details and explicit links.
The fixed-text rejection tests were replaced by arbitrary-valid-text acceptance
plus identity/security rejection, as required by the new policy; parser/security
assertions are retained.

Network tasks are explicit opt-in, separate from ordinary test/check/build:

```sh
./gradlew :app:wikisourceCatalogCheck --args='en Austen 72137 4172199'
./gradlew :app:wikisourceOfflineCheck --args='en 72137'
./gradlew :app:wikisourceOfflineCheck --args='es 2331'
./gradlew :app:wikisourceOfflineCheck --args='ca 3094'
# Legacy real-file compatibility regression; reads local original bytes, no network:
./gradlew :app:wikisourceFeasibilityCheck --args=/path/to/official-el-matadero.epub
```

Each offline check uses the actual adapter, rights policy, preparer/parser,
retained manager and reader. It records technical counts/hash, closes the source,
recreates the store, navigates chapters, persists/restores a real locator, removes
the download while retaining progress, then cleans private temporary files.
Maintain the execution environment's existing proxy/CA for JavaExec when needed.

### Real sample matrix — 31 October 2026

These were limited production-adapter checks, not synthetic availability claims:

| Official case / project / page / observed root revision | Classification and rights | Export, real preparation and reading |
| --- | --- | --- |
| [The New Colossus](https://en.wikisource.org/wiki/The_New_Colossus), en / 72137 / 15492692 | Proofread slice, direct PD-old; positive original-work evidence | **Passed**, 22,453 bytes, 3 spine sections, 2 rights. Network source closed, store recreated, real locator restored. |
| [Canción del pirata](https://es.wikisource.org/wiki/Canci%C3%B3n_del_pirata), es / 2331 / 1248127 | Proofread slice, exact index 256323/r1607011 → Commons file 84568029/r1121815753, PD-scan/PD-old-100-expired | **Passed**, 19,801 bytes, 3 sections, 2 rights; same source-closed reopening/progress/removal checks. |
| [L’Atlàntida / Introducció](https://ca.wikisource.org/wiki/L%27Atl%C3%A0ntida/Introducci%C3%B3), ca / 3094 / 178423 | Chapter, index 10336/r167458 → Commons file 11771737/r1097684182, PD-scan/PD-old-100-expired | **Passed after inert RDFa metadata correction**, 66,320 bytes, 3 sections, 2 rights; source-closed reopening and locator restored. The unmodified inspected export also declares local PNG/JPEG resources. |
| [Pride and Prejudice (1813)](https://en.wikisource.org/wiki/Pride_and_Prejudice_(1813)), en / 4172199 / 14498612 | Unclassified complete-volume candidate, direct PD-old positive | Real export acquired but **rejected with EpubFailure.LIMIT** in XML element text accumulation (existing 8,192-unit per-element bound). Reader/offline-ready not reached; partial file removed. No limit was raised to admit it. |
| [Author:Jane Austen](https://en.wikisource.org/wiki/Author:Jane_Austen), en / 941 / 15193679; [Autor:Miguel de Cervantes](https://es.wikisource.org/wiki/Autor:Miguel_de_Cervantes), es / 3830 / 1626564 | AUTHOR / web-only | Real metadata, no export/download offered. |
| [The Raven](https://en.wikisource.org/wiki/The_Raven), en / 4031 / 15436642; [Tirant lo Blanc](https://ca.wikisource.org/wiki/Tirant_lo_Blanc), ca / 21078 / 168278 | DISAMBIGUATION / web-only | Real metadata, no export. |
| [Pride and Prejudice](https://en.wikisource.org/wiki/Pride_and_Prejudice), en / 4185790 / 13374640; [Don Quijote de la Mancha](https://es.wikisource.org/wiki/Don_Quijote_de_la_Mancha), es / 18672 / 1626565 | VERSIONS / web-only | Category evidence verified; not presented as one exportable book. |
| [Llibre:La Atlantida (1886).djvu](https://ca.wikisource.org/wiki/Llibre:La_Atlantida_(1886).djvu), ca / 10336 / 167458; [Espronceda index](https://es.wikisource.org/wiki/%C3%8Dndice:Jos%C3%A9_de_Espronceda_-_Obras_po%C3%A9ticas_completas.djvu), es / 256323 / 1607011 | INDEX / web-only | Actual proofread-index metadata accepted; no source scan downloaded. |
| [La vaca cega](https://ca.wikisource.org/wiki/La_vaca_cega), ca / 1909 / 136085 | Slice, insufficient recognized original/edition rights evidence | Catalog-only, no export. Its Commons scan's PD-old-80 is not the required positive rule; extra page transclusions are not assumed licensed. |
| [El Matadero](https://es.wikisource.org/wiki/El_Matadero), es / 1425 / 1393104 | General catalog metadata; no recognized automatic rights declaration | New acquisition withheld. Separate **legacy original-file regression** verifies the previous retained identity and unchanged EPUB through the current technical policy/reader, not a new authorization. |

Observed SHA-256 values of the successful dynamic exports (no fixed admission hashes):

```text
en 72137   2026-10-31T08:07:03Z  9acc020741964aa6b76a8d06c0974df96323f4fcf8d8656cdd97d954374242c5
es 2331    2026-10-31T08:15:27Z  3ce1908e02ec61d79ab183b7a1a71c0b82d5859a3fc9001ec24b1de38fe2eaed
ca 3094    2026-10-31T08:28:29Z  403b01ef683d5a87fde4870f8ac59b47ddcf1b8456b69314999518195aba1e98
```

A separate unmodified Catalan inspection export was 66,321 bytes /
`c4f54de3cef92276c22882b294a1ec803183edaa9a15a164624fc1a61e9624fa`.
Different ZIP timestamps produce different bytes; no fixed byte hash is used for
remote admission. Ordinary suites contain no downloaded book binary/text.

Real term/author-name queries `Austen`, `Cervantes` and `Maragall` each produced
20 results, a real continuation and another 20 results with zero overlapping IDs
in the checked two pages. This verifies a sample of paging, not exhaustiveness
or an immutable server order. Classification/rights examples above used selected
page IDs; no EPUB was downloaded while searching. Early failed probes revealed
API-contract and RDFa issues and published no retained copies. Those failures
were corrected with bounded generic behavior; the long-novel limit is retained.

### Final full verification

Linux x86_64, JDK 21 / Gradle 9.7.1, one Gradle worker and the existing cached
Skiko runtime for headless Compose. Source/build inputs were held unchanged
through the run (454 files checked). **BUILD SUCCESSFUL in 13m 45s.**

| Complete suite | Executed cases | Failures / errors / skipped |
| --- | ---: | --- |
| Desktop | 1,785 | 0 / 0 / 0 |
| Android-host | 1,501 | 0 / 0 / 0 |
| Core JVM | 60 | 0 / 0 / 0 |
| Core Android-host | 60 | 0 / 0 / 0 |
| **Total** | **3,406** | **0 / 0 / 0** |

All four suites actually ran, with no disabled/skipped test cases or weakened
security assertions. The prior 3,333-case figure is the preceding commit's
baseline, not this continuation's result. This continuation adds 38 Desktop and
35 Android-host cases. Ordinary fixtures are original; live probes are separate.

Android debug, **unsigned** Android release, Desktop compilation, Android check,
Lint debug/release, packaged languages and both updater-manifest policies passed.
Both Lint XML reports contain **zero issues**. The manifest-policy task also ran
its eight synthetic cases separately from the JUnit count above. Existing
unrelated compiler warnings (deprecated test clipboard API, unused expressions,
PDF non-null assertions) were not suppressed or changed.

`tools/check_localization.py`: **566 strings and 3 plurals** in en/es/ca. All six
resource assets in **both APKs** were compared byte-for-byte with generated
resources. No test/probe class names, private keys or book/partial-file assets are
packaged. Debug APK signature verification passes; the release output is verified
unsigned and is not an installable public release. Signing configuration is
unchanged; no externally supplied key or password was needed/published here.

Both built manifests retain `org.infinilect.app`, versionCode 1 /
`0.0.1-SNAPSHOT`, minSdk 26, targetSdk 37, INTERNET, ACCESS_NETWORK_STATE and the
AndroidX internal signature permission. REQUEST_INSTALL_PACKAGES remains absent;
the updater receiver remains non-exported. No manifest/SDK/updater/download-store/
Archive/Gutenberg/OAPEN/schema/dependency implementation changes were made.
Changed-file checks found only Kotlin/XML/Markdown/Gradle source, no private-key
markers or generated artifacts. `git diff --check` passes. No APK/release is
published and no Android device/Play Protect/FPS acceptance is claimed.

```sh
./gradlew --max-workers=1 --continue \
  :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:assembleRelease \
  :androidApp:verifyDebugLanguageResources :androidApp:check \
  :androidApp:lintRelease :desktopApp:compileKotlin
python3 tools/check_localization.py
git diff --check
```


## Physical acceptance and remaining limits

1. On Android/Desktop search arbitrary title/author terms with each of the three
   Wikisource editions; load another page. Main pages must not all say “complete
   book”; author/index/versions/disambiguation pages must not offer fake readers.
2. Open a positively authorized sample from the recorded table; distinguish
   potential from validated EPUB. Verify original rights/credits, local images,
   notes, TOC and internal links; passive external citations do not fetch/open.
3. Explicitly download, cancel, retry and switch tabs during transfer. Use a slow
   or absent connection. No partial copy should become offline-ready; cooldowns
   must not cause unbounded retries or UI blocking.
4. Complete, disable Wi-Fi/mobile data, close/reopen, read from Library/Downloads,
   then reopen again and check exact locator/History. Test a previously retained
   El Matadero and existing Archive/imported EPUB/TXT/CBZ/PDF without clearing data.
5. Deleting a download must preserve Library/status/favorite/tags/History/progress.
   A remote revision must not silently replace an existing retained copy.
6. Toggle catalog-only visibility during pending requests; Gutenberg stays hidden
   initially, saved publications remain accessible and late hidden results stay
   retired. Temporary source outages must not change capabilities.
7. Repeat es/en/ca UI, themes, narrow screens, fonts 150/180%, keyboard/TalkBack.

Catalog search is general; rights patterns, project markup, export availability,
format/parser subset and sample coverage remain limited. There is no wholesale
wiki index, reliable universal completeness detector, all-author bibliography,
whole-transclusion revision snapshot, arbitrary external resource download or
universal territorial clearance. No Android frame-time or physical acceptance
claim follows from JVM probes. Keep Draft until review and physical validation.

## Adding an edition

Verify official siteinfo/namespaces, copyright semantics, search and a real
authorized export. Add an internally fixed `WikisourceEdition`, localized label
and namespace/rights fixtures. Reuse the same source/catalog/manager; no remote
host configuration. Test identifiers, Unicode, actual reader/offline persistence,
negative rights/unsafe files, state/visibility and all platforms before declaring
its reader capability. Add general rights rules only with policy evidence and
negative tests, never a title/revision allowlist.
