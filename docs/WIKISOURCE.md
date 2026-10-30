# Wikisource feasibility, EPUB rights and source visibility — PR #39

Status, **30 October 2026, continuation after `90c9de9`**: source visibility remains implemented. **Repeatable EPUB rights are now supported. Wikisource remains blocked and is not registered.** A newly downloaded official export passes the corrected metadata stage but still fails the existing XHTML doctype policy. No source/catalog placeholder or unverified reading/offline action is introduced.

This branch starts at `main` (`6a34f4d63e4f868bd61c4de96cb865feb3a80b2a`), independently of open OAPEN PR #38. It neither merges nor copies that branch. Gutenberg, Internet Archive, downloads, updater, permissions, source registrations, database schemas and the existing visibility setting are unchanged by this continuation.

## EPUB correction and specifications

Primary specifications checked on 30 October 2026:

- [OPF 2.0.1 §2.2 and §2.2.15](https://idpf.org/epub/20/spec/OPF_2.0_latest.htm): metadata may contain any number of instances of Dublin Core elements, interspersed with other elements. Rights is a statement or reference, not an instruction to grant acquisition.
- [EPUB 3.3 §5.5.3.2 optional Dublin Core elements](https://www.w3.org/TR/epub-33/#sec-opf-dcmes-optional): `dc:rights` is an optional, **repeatable** metadata child with a text content model and optional `id`, `xml:lang` and `dir` attributes.

The previous `EpubMetadata.rights: String?` and `rights.size <= 1` package check rejected valid multiplicity. The model now exposes **`List<EpubRightsStatement>`** in document order, including duplicate declarations. Each statement retains XML-decoded text **without trimming**, its optional local ID, effective language and direction (including metadata/package inheritance). Standard XML line-ending/entity normalization still applies. There is no concatenation, deduplication, choice of preferred license or invented AND/OR relationship between licenses.

Zero and one declaration remain supported. The single-statement consumer assertion was updated to compare the complete structured declaration. The field is an in-memory prepared-document contract, not a database or import-record format; existing imports are reparsed without rewriting their bytes, identity or progress. No production UI reads this EPUB field as a single string: publication details show the separate source-supplied `Publication.rights`, which is unchanged. Future EPUB metadata UI must display each statement separately, in order, without treating it as authorization. No conversion from EPUB declarations into source/download permission is added.

New finite metadata limits: **32 declarations**, **32,768 total UTF-16 text units**, and 128 units each for effective language/direction. The existing **8,192 units per XML text node**, XML byte/depth/node/attribute bounds, ZIP size/expansion/CRC checks, paths, resource ownership, DTD/entity protections, XHTML validation, timeouts and cancellation remain. Test limits may only lower production ceilings. Blank or element-containing `dc:rights` are rejected; markup is not silently discarded.

The original ZIP, OPF license links and attribution pages are never edited. Rights statements do not prove public-domain status or permission to download in every jurisdiction.

## Official Wikisource interfaces and policies

Primary references consulted:

- [MediaWiki Action API search](https://www.mediawiki.org/wiki/API:Search): search, namespace restriction and continuation; HTTP 200 can still contain an API error.
- [Wikimedia access policy](https://www.mediawiki.org/wiki/Wikimedia_APIs/Access_policy) and [rate limits](https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits): identify the client, bound requests and honor throttling/Retry-After. Current guidance recommends no more than three concurrent requests; this investigation used sequential, limited requests with timeouts and `maxlag=5` for Action API calls.
- [WS Export documentation](https://wikisource.org/wiki/Wikisource:WSexport) and [current official form](https://ws-export.wmcloud.org/): `lang`, `page`, `format`; the form advertises EPUB 3/2, PDF variants and other formats. Only EPUB 3 was actually downloaded. No HTML scraping or unofficial export endpoint was used.
- [Wikisource reader selection work](https://meta.wikimedia.org/wiki/Wikisource_Reader_app_(Android)/Selection): namespace 0/search hits are insufficient proof of a complete book. Editions, versions pages, indexes, chapters and proofreading/transclusion require distinct treatment.
- [Spanish Wikisource copyright guidance](https://es.wikisource.org/wiki/Wikisource:Derechos_de_autor): author, edition, contributions and jurisdiction matter; accessibility is not universal authorization.

Client identification:

```text
INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)
```

No authentication, protections/limits bypass, mass download, remote telemetry or third-party artifact upload was used. No new host/redirect allowlist or production networking policy is enabled.

## Real checks and current blocker

| Check | Observed result |
| --- | --- |
| Original Spanish title query | `https://es.wikisource.org/w/api.php?action=query&list=search&srsearch=Don%20Quijote%20de%20la%20Mancha&srnamespace=0&srwhat=title&srlimit=3&format=json&formatversion=2&maxlag=5`: HTTP 200 with `search-title-disabled`. |
| Working search syntax | Same endpoint with `srsearch=intitle%3A%22Don%20Quijote%22` and without `srwhat=title`: three results, 178 hits, continuation `sroffset=3`. A second page was not requested. Results included an edition/volume and versions pages; no complete-book guarantee. |
| Metadata / redirects | `action=query&titles=Lazarillo%20de%20Tormes&redirects=1&prop=info%7Cpageprops%7Ccategories&format=json&formatversion=2&maxlag=5` resolved to `La vida de Lazarillo de Tormes`, page 24190, category `Versiones`; not accepted as a complete book. |
| Work metadata | `action=query&titles=El%20Matadero&prop=info%7Cpageprops%7Ccategories%7Crevisions&rvprop=ids%7Ccontent&rvslots=main&format=json&formatversion=2&maxlag=5`: page 1425/revision 1393104, Spanish, attribution to Esteban Echeverría, short-story content. This was the earlier metadata observation, not a new completeness algorithm. |
| Initial official EPUB | `https://ws-export.wmcloud.org/?lang=es&page=El_matadero&format=epub-3`: HTTP 200 EPUB 3, **32,937 bytes**, SHA-256 `194e9d8214df5beb54724772f29150f37e309baca34492784a8511dc9d114e5d`; originally rejected by rights cardinality. |
| Fresh official EPUB, continuation | Same HTTPS URL, one new bounded download, **32,938 bytes**, SHA-256 `e0094cc9f9f924a882011fa8511620c03148fddd9489df13ed045a270a917590`. Valid ZIP/OPF identified as EPUB 3 with three spine entries, author/title/Spanish and both rights statements. Generated `dcterms:modified` is `2026-10-30T14:27:53Z`; dynamic exports need not have identical hashes. |
| Rights regression before correction | The new synthetic two-rights EPUB test fails with the old preparer (`EpubException(INVALID)`); after correction it and the focused metadata/preparer/import tests pass. |
| Fresh export with corrected production code | `:app:wikisourceFeasibilityCheck` still fails in `legacyEpubXml` at `EpubLegacyXml.kt:21`, called by `parseEpubXml → readEpubPackage` while validating manifest XHTML. It has passed the former rights-count check but **has not returned an EpubDocument**. |

The newly observed runtime blocker is the exact **`<!DOCTYPE html>`** declaration in all four XHTML resources (navigation, title, work, attribution). The existing normalizer only recognizes specific inert PUBLIC XHTML/NCX declarations and rejects the short HTML5 declaration. This is a compatibility limitation, not evidence that the official declaration is malicious. DTD/entity handling has not been changed to make this book pass.

A second, statically confirmed incompatibility is that `OPS/about.xhtml` contains four ordinary external attribution/source hyperlinks to Spanish Wikisource, Creative Commons and GNU. `validateEpubContent` applies the local-owned-target rule to all `href` values; `resolveEpubPath` rejects absolute URLs. The corresponding synthetic regression demonstrates that this existing restriction is still enforced. **The real probe does not reach this check yet**, because the doctype fails first. These are hyperlinks, not evidence of embedded remote image/script execution, and they must not be described as an observed SSRF attack.

### Consequence of the mandatory gate

The file must remain unmodified and pass the real preparer/chapter parser before source integration proceeds. It still cannot be prepared, so phases for `WikisourceSource`, acquisition, live chapter navigation and offline reopening are stopped. No catalog-only substitute, source registration, fake acquisition link or readable capability is added. No live chapter text, navigation, Android/Desktop rendering or Wikisource offline reading success is claimed.

Safe follow-up requires an explicit, tested passive-EPUB compatibility design: recognize the inert short doctype without enabling DTDs/entities/resolvers, then decide how to represent external attribution links without automatic network access, unsafe schemes, loss of visible credit or changes to the original EPUB. The current `EpubTarget`/reader contract represents owned internal targets. Removing doctypes/links from the downloaded file, omitting the attribution spine item, skipping full-manifest validation or treating URLs as local paths would hide the problem and is not implemented. Only a new successful real preparation/chapter/navigation probe would reopen the integration gate; completeness, source-specific rights/jurisdiction policy and secure export acquisition would still need verification.

### Licensing is not inferred from metadata

The fresh file retains `Creative Commons BY-SA 3.0` and `GNU Free Documentation License`, both explicitly `xml:lang="en"`, plus their license links and contributor/source pages. They are preserved independently; no combined license is invented. The wiki footer's CC BY-SA 4.0 and the export's older declarations must not be silently collapsed into public-domain status everywhere.

Bibliographic facts differ from copyrightable descriptions/snippets/editorial text. Action API data is not assumed universally CC0; Wikidata has separate structured-data licensing and is not integrated here. The export service's code license does not license every work it exports. No service code, book binary or graphics are copied into the repository.

### Reproduce without modifying the export

The opt-in task is not a dependency of test/check/build and has no network access of its own:

```sh
curl --fail --proto '=https' --max-time 90 --max-filesize 16777216 \
  --user-agent 'INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)' \
  'https://ws-export.wmcloud.org/?lang=es&page=El_matadero&format=epub-3' \
  --output /tmp/wikisource-el-matadero.epub
./gradlew :app:wikisourceFeasibilityCheck --args=/tmp/wikisource-el-matadero.epub
```

With the fresh observed export, expect an INVALID preparation failure at the short doctype, not an accepted/readable EPUB. The probe prints technical stage/cleanup markers only. If preparation succeeds in a future reviewed implementation, it additionally checks Spanish and both separate rights records before invoking the real chapter parser. Its document/preparer handles and temporary files are released on success/failure. A different future export would require renewed review. This JVM probe is not physical Android testing.

## Implemented: configurable catalog-only visibility

### Capability and data flow

`SourceOption.supportsIntegratedReading` derives from the existing, explicitly configured `textReadingEnabled`, `epubReadingEnabled`, `pageReadingEnabled` and `pdfReadingEnabled` routes. These flags enable actual reader/acquisition integrations in `ReadingSession`; they are not inferred from advertised MIME types or remote availability. Adding a source requires reviewing its real path to bytes/reader before enabling a flag.

`visibleInCatalogs(showCatalogOnly)` is the common policy used by unified search/filter options, Home's general and genre requests, and the legacy single-source search selector. The provider registry remains complete for saved-publication lookup. There is no source-name allow/deny list or startup health request.

Current `main` registrations (`network/CreateSources.kt`):

| Source | Declared implementation capability | Default discovery visibility |
| --- | --- | --- |
| Internet Archive | Integrated TXT/PDF routes, subject to existing access/rights/format checks | Visible |
| Project Gutenberg | Metadata/catalog only; no reader route enabled | Hidden |
| Imported files | Existing local TXT/EPUB/pages/PDF routes; no remote recommendations | Visible in applicable selectors; library/import unaffected |
| Development demo sources | Existing development-only exclusion | Unchanged |
| Wikisource | Not registered: real export still fails XHTML compatibility | Absent |
| OAPEN PR #38 | Not in this branch | No dependency or modification |

A future catalog-only OAPEN registration with all reader flags false would follow the same policy automatically. A future genuinely readable provider follows the visible path automatically. Network failure remains a failed/retryable catalog state, never a capability change.

### Preference, UI and persistence

Settings → Discovery contains **“Show sources without integrated reading”**, with the requested explanatory description. Spanish: **“Mostrar fuentes sin lectura integrada”**; Catalan: **“Mostra fonts sense lectura integrada”**. The switch row is keyboard/touch accessible, at least 48 dp high, wraps its text and shares the existing scrollable settings layout and themes. Included catalog-only source labels/attributions identify them explicitly; existing details still explain unavailable acquisition and do not acquire fake resources.

The default is **false** for new and existing installations. `DiscoveryPreferences.showCatalogOnlySources` is stored by the existing application-owned preferences writer, including edit-during-load handling, bounded coalescing, error reporting and retry. `FileApplicationAppearanceStore` accepts historical INF1–INF4 records with false as the default and adds INF5 only when this flag is true. It retains the checksummed, atomic, private ≤512-byte record; turning the flag off can write the existing compatible layout. Appearance, profile/country, UI language, independent reading language, recommendation preferences and inference timestamp are preserved. No SQL schema/migration, new database or duplicate library is introduced.

### Requests, selections and saved books

- Unified search keeps user-selected source IDs separately from the currently visible intersection. Hiding a catalog cancels its pending request and removes its search results; it cannot silently participate in subsequent searches. Visible source requests/results remain intact and are not refetched just for hiding another provider. Re-enabling restores the previous in-session selection; an explicit user deselection remains deselected.
- Hidden/retired callbacks cannot insert results into current catalogs. Cancellation is checked for late successful **and exceptional** completions. Enabling a source while search is paused marks its work interrupted and defers requests until search resumes.
- Home retires its generation on a visibility change, filters hidden entries out immediately and rebuilds eligible rows using the existing bounded catalog/cache on the next refresh. Changing a preference does not initiate network requests from an inactive search screen. Original concurrency/cache/memory budgets are unchanged.
- The legacy single-source selection uses a visible fallback while retaining its prior preferred source ID for restoration when eligible. Existing source selections were session state, not disk-persisted settings; this PR does not claim to add persistence for those selections.
- Library/history/progress are **never filtered or deleted** by this preference. Saved metadata remains available even for a hidden metadata-only provider. Existing reader availability constraints still apply to those books; visibility does not grant acquisition rights or create a missing reader.

## Verification and limitations

The continuation adds synthetic tests for EPUB2/3 with zero/one/multiple rights, ordered/duplicate statements, whitespace/entities/Unicode, inherited attributes, incomplete required metadata, count/aggregate/per-element limits, malicious XML, unchanged URL/doctype restrictions and byte-for-byte retention of the original ZIP. A host test restarts the production local-import store with a synthetic EPUB and verifies unchanged publication identity and both statements after reopening. Source visibility, preferences, downloads and all existing reader tests remain enabled.

Verification on Linux x86_64, JDK 21 and the repository Gradle 9.7.1 toolchain, with one Gradle worker and installed Skiko for headless Compose:

| Full suite | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| `:app:desktopTest` | 1,724 | 0 / 0 / 0 |
| `:app:testAndroidHostTest` | 1,443 | 0 / 0 / 0 |
| `:core:jvmTest` | 60 | 0 / 0 / 0 |
| `:core:testAndroidHostTest` | 60 | 0 / 0 / 0 |
| **Total executions** (shared tests run on both targets) | **3,287** | **0 / 0 / 0** |

The aggregate verification completed **BUILD SUCCESSFUL** in 14m 16s. Android debug and **unsigned** release assembly, Desktop compilation, Android `check`/Lint debug and release, both updater manifest-policy checks and `verifyDebugLanguageResources` passed. Debug/release Lint XML reports contain zero issues. `tools/check_localization.py` passes **544 strings and 3 plurals** in en/es/ca (189 production Kotlin files scanned); all six language/plural assets in the debug APK match generated resources byte-for-byte.

Both merged manifests retain `org.infinilect.app`, INTERNET, ACCESS_NETWORK_STATE and AndroidX's internal signature permission, without REQUEST_INSTALL_PACKAGES. No SDK, signing configuration, permission, dependency, source, download or updater changes were introduced. `git diff --check` and changed-file artifact/credential checks pass. No APK/release is published.

This includes 15 new rights-metadata cases on each app host, the new import-store restart case on each app host and one new Core model case on each Core target. The pre-correction red test and the live probe have separate logs; neither is presented as a successful reading test. No tests were removed/disabled or assertions relaxed. The existing single-rights assertion now checks the complete structured record instead of a nullable string.

Reproduction:

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

The independent official-export probe is deliberately separate: its negative result is a **current blocker**, not a passing reading/offline test. No physical realme C55, Android network/export or Wikisource reader/offline success has been validated. English/Catalan exports, author search, PDF output, live pagination beyond the advertised continuation and throttling/redirect behavior remain unverified. The PR remains Draft.

## Physical validation checklist (Android and Desktop)

1. Update/install without deleting existing app data. In a new/default profile confirm Gutenberg is absent from Search → Sources & filters and Home. Internet Archive remains visible.
2. Settings → Discovery: enable the switch. Confirm catalog-only labels and existing Gutenberg metadata-only behavior; no fake read/download action.
3. Select/deselect sources, start a search and hide catalog-only providers while it is pending. Confirm their results disappear, Internet Archive remains usable and a late response cannot reinsert hidden rows.
4. Re-enable: restore prior in-session selection. Repeat through Home/Search/Library/Settings, with slow/offline connectivity and return to the existing scroll positions.
5. Restart: verify the visibility choice, profile/country, appearance, application and reading languages remain. Check saved books from hidden sources, history, favorites, tags and exact reader progress; import/open existing EPUB/TXT/CBZ/PDF and Internet Archive offline copies.
6. Repeat in Spanish/English/Catalan, dark/light and large text. No Wikisource source is expected. No APK or release is published by this PR.
