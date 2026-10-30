# Wikisource feasibility and source visibility — PR #39

Status, **30 October 2026**: source visibility is implemented. **Wikisource is not integrated**: a real official EPUB download succeeded, but the existing production EPUB preparer rejected it. The required search → export → readable-file gate has therefore not passed. No Wikisource catalog adapter, acquisition link, download button or offline-reading claim is introduced.

This branch starts at `main` (`6a34f4d63e4f868bd61c4de96cb865feb3a80b2a`), independently of the open OAPEN PR #38. It neither merges nor copies that branch. Gutenberg's implementation and endpoints are unchanged.

## Official interfaces and conditions investigated

Primary references checked on the date above:

- [MediaWiki Action API search](https://www.mediawiki.org/wiki/API:Search): `action=query&list=search`, continuation, namespace restriction and documented title/text modes. A response can contain an API error even with HTTP 200.
- [Wikimedia API access policy](https://www.mediawiki.org/wiki/Wikimedia_APIs/Access_policy) and [rate limits](https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits): identify clients, use bounded requests, honor throttling/`Retry-After` and server errors. Current guidance recommends no more than three concurrent requests; actual endpoint limits may change. `maxlag=5` was supplied to the Action API checks. No production polling or load test was added.
- [Wikisource WS Export documentation](https://wikisource.org/wiki/Wikisource:WSexport) and the [current official service form](https://ws-export.wmcloud.org/): GET parameters `lang`, `page`, `format`; the current form advertises `epub-3`, `epub-2`, PDF variants and other formats. Only EPUB 3 was downloaded in this investigation. These are generated exports, not an assertion that every wiki page is a book.
- [Wikisource reader selection work](https://meta.wikimedia.org/wiki/Wikisource_Reader_app_(Android)/Selection): transclusion, proofreading/validation and work/index relationships matter when selecting books. An ordinary search result in namespace 0 is insufficient proof of a complete work.
- [Spanish Wikisource copyright guidance](https://es.wikisource.org/wiki/Wikisource:Derechos_de_autor): copyright depends on the work, author, edition and jurisdiction. A freely accessible page is not sufficient authorization in every country.

Searches use the language-edition host (`es.wikisource.org` here). Other editions use their own wiki hosts, templates and completeness conventions. The export service accepts a language parameter. Spanish search and export were exercised; English/Catalan exports, author search, PDF output, throttling and server redirect behavior were **not** validated live.

## Limited real checks

Requests were sequential HTTPS requests with timeouts and an identifying User-Agent:

```text
INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)
```

No authentication, challenge bypass, scraping, credential handling or download flood was used. No redirects were needed for the successful export; accepting arbitrary redirects has not been implemented.

| Check | Request / result observed |
| --- | --- |
| Literal title search | `https://es.wikisource.org/w/api.php?action=query&list=search&srsearch=Don%20Quijote%20de%20la%20Mancha&srnamespace=0&srwhat=title&srlimit=3&format=json&formatversion=2&maxlag=5` returned HTTP 200 with `search-title-disabled`. A future adapter must inspect API error objects. |
| Documented search syntax | The same endpoint with `srsearch=intitle%3A%22Don%20Quijote%22` and without `srwhat=title` returned three results, `totalhits=178` and continuation `sroffset=3`. Results included an edition/volume path, a differently attributed second volume and a versions page. No second page was requested. |
| Redirect / incomplete identity | `action=query&titles=Lazarillo%20de%20Tormes&redirects=1&prop=info%7Cpageprops%7Ccategories&format=json&formatversion=2&maxlag=5` resolved to `La vida de Lazarillo de Tormes`, page ID 24190, with category `Versiones`. It was not accepted as a verified complete book. |
| Work metadata | `action=query&titles=El%20Matadero&prop=info%7Cpageprops%7Ccategories%7Crevisions&rvprop=ids%7Ccontent&rvslots=main&format=json&formatversion=2&maxlag=5` returned page ID 1425, revision 1393104, Spanish, with work content and author attribution to Esteban Echeverría. The earlier title `El matadero` was resolved using `redirects=1`. This establishes an export example of a complete short story, not a general completeness algorithm for novels. |
| Actual export and download | `https://ws-export.wmcloud.org/?lang=es&page=El_matadero&format=epub-3` returned HTTP 200, `application/epub+zip`, **32,937 bytes**. ZIP: 11 entries; OPF: EPUB 3, language `es`, author, source/identifier on Spanish Wikisource, three spine entries including title and attribution pages. |
| Existing reader compatibility | The unmodified downloaded file was passed to **`FileEpubPreparer.prepare`**, which failed with `EpubException(INVALID)` at `EpubPackage.kt`'s `requireEpub(rights.size <= 1)`. No chapter or UI reading success was reached. |

SHA-256 of the one observed export (exports may change):

```text
194e9d8214df5beb54724772f29150f37e309baca34492784a8511dc9d114e5d
```

The export contains **two `dc:rights` elements**: `Creative Commons BY-SA 3.0` and `GNU Free Documentation License`, with corresponding license links. The reader's current at-most-one-rights metadata contract rejects that file. This is a compatibility limitation, not evidence of a malicious EPUB. The original file was neither modified nor stripped of its rights/attribution to obtain a passing test. No third-party book binary, response cookies or captured book text is committed.

### Rights and metadata are separate

The source describes the historic work and wiki contributions under potentially different rights. The Spanish wiki footer's CC BY-SA 4.0 wording and this generated EPUB's older CC BY-SA 3.0/GFDL declarations must not be silently collapsed into “public domain everywhere.” Exported contributor/source/license pages must remain intact. No legal guarantee for every jurisdiction is made.

Bibliographic facts are distinct from copyrightable descriptions, snippets and editorial wiki text returned by MediaWiki. This integration does not assume all Action API results are CC0. Wikidata has its own structured-data licensing; no Wikidata adapter is implemented here. The WS Export service's code license also does not authorize every work it can export. No engine/service code or graphics are copied.

### Why implementation stopped

The user explicitly requires both functioning official search and an exported file **readable by INFINILECT** before implementing `WikisourceSource`. Search and HTTP export work; reader compatibility does not. A metadata-only Wikisource source would fail this gate and is deliberately absent.

Safe next steps require a separate, reviewed compatibility change to preserve **all** rights statements in EPUB metadata, synthetic EPUB2/EPUB3 regression tests and a new real export/preparer/chapter-parser check. After that, completeness identification across editions, current rights/attribution and jurisdiction policy, bounded host/path/redirect rules for both wiki and export service, acquisition expiry, throttling/error handling and actual offline reopening still need validation. No existing EPUB guards are weakened in this PR, and no new remote host is added to the acquisition/download allowlists.

### Reproduce the compatibility probe

The development-only task has no network access of its own and is **not** a dependency of ordinary tests or builds. Supply one locally downloaded, authorized official export (maximum 16 MiB):

```sh
curl --fail --proto '=https' --max-time 90 --max-filesize 16777216 \
  --user-agent 'INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)' \
  'https://ws-export.wmcloud.org/?lang=es&page=El_matadero&format=epub-3' \
  --output /tmp/wikisource-el-matadero.epub
./gradlew :app:wikisourceFeasibilityCheck --args=/tmp/wikisource-el-matadero.epub
```

Expected with the observed export: rejection by the existing rights-count guard. If preparation succeeds for a future file, the probe also parses every spine item with the real parser, requires substantial text and reports counts only. It releases the document/preparer and removes its temporary workspace. A different export passing later would require renewed review; it does not retroactively validate this integration. This JVM probe is not Android device testing.

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
| Wikisource | Not registered: viability gate failed | Absent |
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

Deterministic tests cover capability-based/default visibility, future providers, network failure versus visibility, cancellation with barriers/non-cooperative replies, paused search, selection restoration, cache reuse/no duplicate visible requests, preferences edited during loading, historical preference layouts, roundtrip/corruption/storage errors and preservation of saved metadata/history/progress. Shared Compose tests exercise localized settings/switch semantics and search filters, three locales, light/dark themes, narrow 320 dp at 180% font scale and Desktop dimensions. Existing discovery fixtures explicitly opt in when their purpose is to test metadata-only catalogs; their functional/lifecycle assertions remain in place.

Executed on Linux x86_64 with JDK 21 and the repository's Gradle 9.7.1 toolchain, one Gradle worker and an installed Skiko native library for headless Compose tests. These are host results, not Android frame-time measurements or physical interactions.

| Full suite | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| `:app:desktopTest` | 1,708 | 0 / 0 / 0 |
| `:app:testAndroidHostTest` | 1,427 | 0 / 0 / 0 |
| `:core:jvmTest` | 59 | 0 / 0 / 0 |
| `:core:testAndroidHostTest` | 59 | 0 / 0 / 0 |
| **Total executions** (shared tests run on both targets) | **3,253** | **0 / 0 / 0** |

The first aggregate run passed Desktop/Core but had two Android-host Gutenberg cancellation timeouts: their metadata-only Home fixtures no longer started requests with the new default. Those fixtures now explicitly set `showCatalogOnlySources=true`; cancellation barriers, strict-thread/stream guards, repetitions and assertions are unchanged. The complete Android-host suite was rerun successfully, not only the two cases. The production source/transport is unchanged. Earlier standalone discovery fixtures were similarly made explicit; no tests were disabled or assertions relaxed.

Android debug and unsigned release APK assembly, Desktop compilation, Android `check`/Lint debug and release, updater manifest-policy verification for both variants and `verifyDebugLanguageResources` completed. Lint debug/release XML reports contain zero issues. The six packaged string/plural assets for en/es/ca match generated resources byte-for-byte; `tools/check_localization.py` passes **544 strings, 3 plurals, 189 production Kotlin files**. Final manifests still declare only INTERNET, ACCESS_NETWORK_STATE and the AndroidX internal signature permission; no REQUEST_INSTALL_PACKAGES. Package identity, signing configuration, SDK levels, dependencies and native libraries were not changed. `git diff --check` and changed-file credential/binary checks pass. APKs were built locally for verification, not published.

Reproducible task set (the full Android-host suite/builds were repeated after the fixture adjustment):

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

The independent live/export compatibility probe is an **expected negative result**, excluded from the passing unit/UI suite count. No physical realme C55, Android network/device export, native PDF export or actual Wikisource offline reading has been validated. The Wikisource objective remains incomplete, and this PR must remain Draft pending a decision on the documented blocker and physical review.

## Physical validation checklist (Android and Desktop)

1. Update/install without deleting existing app data. In a new/default profile confirm Gutenberg is absent from Search → Sources & filters and Home. Internet Archive remains visible.
2. Settings → Discovery: enable the switch. Confirm catalog-only labels and existing Gutenberg metadata-only behavior; no fake read/download action.
3. Select/deselect sources, start a search and hide catalog-only providers while it is pending. Confirm their results disappear, Internet Archive remains usable and a late response cannot reinsert hidden rows.
4. Re-enable: restore prior in-session selection. Repeat through Home/Search/Library/Settings, with slow/offline connectivity and return to the existing scroll positions.
5. Restart: verify the visibility choice, profile/country, appearance, application and reading languages remain. Check saved books from hidden sources, history, favorites, tags and exact reader progress; import/open existing EPUB/TXT/CBZ/PDF and Internet Archive offline copies.
6. Repeat in Spanish/English/Catalan, dark/light and large text. No Wikisource source is expected. No APK or release is published by this PR.
