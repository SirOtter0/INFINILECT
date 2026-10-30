# Wikisource, passive XHTML and source visibility — PR #39

Status **30 October 2026**, continuation of `a145e925735c78a0ad64b856f7de8869abcb8f70`: both EPUB compatibility blockers are corrected. The fresh official EPUB passes preparation, semantic parsing and forward/backward reader navigation. A second live check uses the production Wikisource adapter and existing retained-download manager, closes the network source, recreates the store and restores an actual durable EPUB locator.

**Coverage is deliberately limited: the Spanish reviewed catalog currently contains one complete work, _El matadero_, page 1425/revision 1393104. This is not unrestricted Wikisource integration or automatic complete-book/rights classification.** Unreviewed works are excluded, including chapters, indexes and versions pages. Other editions, PDF/EPUB2 export and general author search are not advertised. Physical Android validation remains pending; PR #39 stays Draft.

The branch remains independent of open OAPEN PR #38. Gutenberg, Internet Archive's adapter, the updater, Android manifests/permissions, signing, dependencies and persistent schemas are not changed.

## EPUB corrections and specifications

Primary references consulted:

- [EPUB 3.3 XML restrictions](https://www.w3.org/TR/epub-33/#sec-xml-constraints): declarations may omit external identifiers. External entity declarations and XInclude are prohibited.
- [HTML XML syntax](https://html.spec.whatwg.org/multipage/xhtml.html): an XML-serialized HTML document may have a DOCTYPE; it is not required and does not imply a DTD/network request.
- [XML 1.0 §2.8](https://www.w3.org/TR/xml/#sec-prolog-dtd): document type declaration syntax and internal/external subsets.
- [OPF2 metadata](https://idpf.org/epub/20/spec/OPF_2.0_latest.htm) and [EPUB 3.3 optional Dublin Core](https://www.w3.org/TR/epub-33/#sec-opf-dcmes-optional): repeatable rights statements.

`EpubLegacyXml.kt` recognizes exactly the inert short `<!DOCTYPE html>` with XML whitespace, a bounded prolog/declaration and a required XHTML `html` root. Only that recognized declaration is omitted from the parser's in-memory view; the original ZIP/XHTML is unchanged. Existing specific PUBLIC XHTML/NCX declarations remain supported. Short HTML5 does **not** introduce HTML named entities; the old fixed `nbsp` compatibility applies only to the previously supported legacy PUBLIC XHTML forms. No arbitrary declaration stripping, internal subset, custom entity, SYSTEM DTD, resolver, network/file access, XInclude or processing instruction is enabled. Byte/depth/node/text, ZIP/CRC/expansion/path and cancellation limits remain unchanged. This is a restricted reader, not a full EPUB conformance validator. [Full boundary](EPUB.md).

`validateEpubContent` permits external references only on ordinary XHTML `a[href]`, outside actual TOC navigation. `epubExternalReference` accepts a conservative **HTTPS-only** URI subset: bounded length, canonical public-form DNS host and default/443 port, no credentials, IP literals, private suffixes, whitespace, controls, backslashes or malformed escapes. Dangerous schemes, HTTP and scheme-relative URLs fail closed. There is no DNS lookup or HTTP request to validate a citation.

The semantic model keeps `EpubExternalReference` separate from internal `EpubTarget`. The Compose reader renders these citations as passive text: **no external browser action, auto-open, URL annotation or remote load**. Original URI spelling and attribution text remain in the model/cache and original file. Real internal links, fragments and TOC navigation retain their original owned-file rules. Images, stylesheets, fonts, SVG, scripts and other resources do not inherit the citation exception. Session semantic cache format advances to `EPUBWIN2`, with URI bytes counted against existing quotas and validated on reading; no persistent user schema is involved.

The prior `List<EpubRightsStatement>` correction is retained: all declarations, order, duplicates, literal decoded text, IDs and effective language/direction survive. Limits remain 32 statements/32,768 aggregate UTF-16 units and 128-unit language/direction, plus existing per-node limits. The two statements are never interpreted as a combined license or universal download grant.

## Real evidence and mandatory gate

Limited sequential HTTPS requests used:

```text
INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)
```

| Check, 2026-10-30 | Observed result |
| --- | --- |
| `https://es.wikisource.org/w/api.php?action=query&list=search&srsearch=intitle%3A%22El%20Matadero%22&srnamespace=0&srlimit=5&format=json&formatversion=2&maxlag=5` | HTTP 200, one hit, page 1425. |
| Same API: `titles=El_matadero&redirects=1&prop=info%7Cpageprops%7Crevisions&inprop=url&rvprop=ids` | Canonical title `El Matadero`, Spanish, page 1425/revision 1393104, canonical HTTPS URL. MediaWiki's JSON title redirect is not an HTTP redirect. |
| Original title-search experiment (earlier PR work) | `srwhat=title` returned `search-title-disabled`; use `intitle:` instead. Don Quijote query returned continuation and versions/edition pages, not automatic proof of completeness. |
| Author full-text query | HTTP 200 containing `cirrussearch-too-busy-error`; it is a retryable API error, not an empty success. No general author-search availability claim. |
| Fresh official `https://ws-export.wmcloud.org/?lang=es&page=El_matadero&format=epub-3` | **32,937 bytes**, SHA-256 `e9ab9b87cf76a8cde8efb4d61894c5459bfeefb46924b2234d7502b7cd3b737d`. Original bytes supplied to `:app:wikisourceFeasibilityCheck`, no edits. |
| Production file probe | Preparation, both rights, title/author/Spanish, three spine sections, **37,049 code points**, four distinct passive attribution URLs, forward/back reader navigation and cleanup pass. The preparer/reader's local resource contract cannot make URL requests. |
| Final production source + offline manager, **19:35:12 UTC** | Fresh export **32,937 bytes**, SHA-256 `c977af4b1f8e04269e3e9b4a7301748ed19a4f1b66e92ff6657e37f3721814ca`. Search, metadata revalidation before/after transfer, full-file validation and atomic retained publication pass. |
| Offline verification | Source/HTTP client closed, retained store recreated, same publication/resource identity reopened locally; all three chapters navigated, two rights preserved, durable locator restored from a new progress store; removal preserves progress. Temporary files/owners cleaned. |

Hashes differ because WS Export generates timestamps/ZIP metadata dynamically; no immutable byte revision is invented. Earlier exports (`194e9d…`, `e0094c…`) recorded the former blockers. The preceding successful 18:26:40 offline check produced 32,938 bytes / `5a0ffe5b61014b8fea7164c67bdddcd98c94c558e42de3130ac6aefe20510726`; both final probes passed again after the API26 encoder correction. These timestamps/hashes describe separate unmodified dynamic exports.

The first new network JavaExec attempt lacked the managed environment's proxy properties and failed before acquisition. A separate, external init script passed the existing proxy/CA settings without changing product networking. The next attempt correctly rejected our initially mistyped expected license URLs. Inspecting the unchanged original established the exact four HTTPS destinations; correcting the reviewed record made the gate pass. Neither failed attempt published an offline copy or caused checks to be bypassed.

## Source, completeness and acquisition policy

Production files: `wikisource/WikisourceCatalog.kt`, `WikisourceSource.kt`, `WikisourceExportValidation.kt`; registration/policy binding in `network/CreateSources.kt`. Source ID `wikisource-es`, local ID is the canonical MediaWiki page ID. Publication identity never depends on its title. Only Spanish EPUB 3 is enabled.

Search uses the official Action API (`intitle:` with an encoded quoted literal), namespace 0, 25 results/page, query-bound opaque offset tokens and a 1,000-offset ceiling. Results are deduplicated by page ID and intersected with the independently reviewed records, then revalidated against current canonical metadata/revision. This can yield empty pages despite other wiki hits. Author matching is accent/case-insensitive over reviewed bibliographic authors only, followed by real metadata lookup. No author names or book payloads are extracted from arbitrary snippets. Home may show the reviewed landing selection, backed by fresh metadata; no fake search results are generated when HTTP fails. Missing optional descriptions/covers/publisher/date/ISBN are not fabricated.

**A page in namespace 0, a search hit, a root title or advertised MIME is not a complete-book or copyright proof.** There is no reliable generic complete-work/territorial-rights signal established by the observed API. Broader automatic admission is therefore stopped. This PR admits only the explicitly reviewed complete short story and original attribution pages. The source's localized name says “verified catalog”; it does not promise the full wiki.

The versioned review binds page 1425/revision 1393104, canonical title/author/language/identifier, both separate rights statements, **all three semantic spine fingerprints** (including credits) and **all four exact attribution URLs**. No remote response can add or replace review records. Fresh metadata is required immediately before and at EOF after export; edit races fail closed. Structural checks run first; an additional owning-source `FileEpubPreparer.validateDocument` policy can only reject afterward. The same preparer/policy applies to direct reads and retained-download validation/reopening. Unrelated imported EPUBs do not inherit a Wikisource approval requirement.

The semantic evidence uses length-framed UTF-8 paragraph text, incremental SHA-256, bounded parser windows and a bounded reference set; no whole-book RAM buffer. It binds reviewed semantics, not publisher-signed authenticity or identical ZIP bytes. A changed revision, attribution, text, reference set or incompatible export requires another review rather than silently downloading. Resource key `epub3-policy1-r1393104` is stable; resource byte revision is null because exports are dynamic. Full retained-file SHA-256 still protects the local saved file against corruption.

### Rights and attribution

Primary policy: [Spanish Wikisource copyright](https://es.wikisource.org/wiki/Wikisource:Derechos_de_autor), [Wikimedia terms](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use), [WS Export](https://wikisource.org/wiki/Wikisource:WSexport).

The reviewed historic original is Esteban Echeverría's complete _El matadero_; the official author record (`Autor:Esteban Echeverría`) records his death in **1851**, separately from the modern wiki edition/contributions. The copyright policy explicitly distinguishes work jurisdiction and the licensing of contributions; wiki presence alone is not a worldwide public-domain ruling. No residence/profile preference is repurposed as a legal eligibility test. No unreviewed modern work, translation or edition is admitted by age/title heuristics.

The verified EPUB declares `Creative Commons BY-SA 3.0` and `GNU Free Documentation License` (both `xml:lang="en"`), with license/contributor/source pages intact. The current website policy also refers to CC BY-SA 4.0. These layers are not silently substituted for one another or for the original work's rights. The file is kept and read unmodified; all credits and exact original links survive. App GPL-3.0-or-later does not relicense the book; API facts, editorial text, contributors and service code are distinct. No service code, cover artwork or book binary is copied into the repository. A source revision/metadata statement alone is not treated as permission for other works.

### How to extend the reviewed subset

For each proposed work, independently review the original/edition/translation and applicable rights, distinguish full work from chapter/index/versions pages, obtain current API identity/revision and a fresh official export, preserve credits, and pass the real preparer/reader/offline probe. Add reviewed metadata, exact reference set and bounded semantic evidence with deterministic tests. No runtime approval based on a license keyword. Retain old resource keys/review policies for already downloaded editions if future revisions are admitted; do not simply replace the current record and invalidate saved copies. The current one-revision implementation has no historical migration need yet; multi-revision retention is required before expanding an existing work's revision. The fingerprints also depend on semantic paragraph segmentation. A future parser change must retain the validator for existing resource keys or provide a verified compatible policy; replacing fingerprints alone could otherwise make a previously retained copy unreadable. This is a maintenance requirement, not an automatic migration implemented here.

## Transport, storage and lifecycle

Only `https://es.wikisource.org/w/api.php` and `https://ws-export.wmcloud.org/` are request authorities, constructed from internal constants and encoded query values. No API-provided URL is fetched. HTTP redirects are rejected rather than followed; export failure never falls back to an external host. HTTPS platform certificate/hostname validation remains enabled. No scraping, credentials, DRM bypass, remote telemetry or new Android permission.

One serialized metadata operation and one streamed export per source; source-owned IO jobs have explicit close/cancellation ownership. JSON: 128 KiB actual/declared, strict UTF-8/tree shape, depth 24, bounded tokens/strings/object keys and duplicate/escaped-key rejection. MediaWiki JSON errors under HTTP 200 are classified. HTTP 429/`maxlag`/busy ends the request with a bounded 30–300-second cooldown; integer Retry-After is honored within that bound. No hidden automatic retries/prefetch. Connect 5s, metadata request 20s/socket 15s, export request/release 60s; export cap **32 MiB**, plus unchanged stricter EPUB limits. MIME/length/EOF/encoding must match; HTML cannot become an EPUB by filename.

Existing `OfflineDownloads`/`FileOfflineDownloads` supplies explicit consent, one global transfer, bounded queue, real/indeterminate byte progress, quotas/free-space admission, fsync+atomic publication, restart recovery, cancellation, retry, reader leases and safe deletion. No second manager, new path naming, schema or cache eviction policy. Unknown generated size remains indeterminate until real response data; it is not the example file's size reused as a promise. Android background/process cancellation semantics remain [unchanged](OFFLINE_DOWNLOADS.md).

The parameter encoder uses `URLEncoder.encode(String, String)` with named UTF-8, available at minSdk 26. The newer Charset overload was replaced during final compatibility review after checking [Android 8 libcore](https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/java/net/URLEncoder.java). The Unicode/reserved-character HTTP regression verifies that query data cannot change the official route or add parameters. No SDK/version/signing change is required.

Direct reading uses the existing source resource loader and preparer. Retained reading first acquires the original identity from private local storage, without catalog/WS Export requests. Library membership, favorites/tags/reading status, History/progress and downloads remain independent. A source outage does not change its implemented capability or hide saved publications.

## Implemented: configurable catalog-only visibility

### Capability and data flow

`SourceOption.supportsIntegratedReading` derives from the existing, explicitly configured `textReadingEnabled`, `epubReadingEnabled`, `pageReadingEnabled` and `pdfReadingEnabled` routes. These flags enable actual reader/acquisition integrations in `ReadingSession`; they are not inferred from advertised MIME types or remote availability. Adding a source requires reviewing its real path to bytes/reader before enabling a flag.

`visibleInCatalogs(showCatalogOnly)` is the common policy used by unified search/filter options, Home's general and genre requests, and the legacy single-source search selector. The provider registry remains complete for saved-publication lookup. There is no source-name allow/deny list or startup health request.

Current PR registrations (`network/CreateSources.kt`):

| Source | Declared implementation capability | Default discovery visibility |
| --- | --- | --- |
| Internet Archive | Integrated TXT/PDF routes, subject to existing access/rights/format checks | Visible |
| Project Gutenberg | Metadata/catalog only; no reader route enabled | Hidden |
| Imported files | Existing local TXT/EPUB/pages/PDF routes; no remote recommendations | Visible in applicable selectors; library/import unaffected |
| Development demo sources | Existing development-only exclusion | Unchanged |
| Wikisource (Spanish, verified catalog) | Reviewed EPUB 3 export and retained-file route | Visible; currently one reviewed work |
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

## Reproduction and verification

Ordinary automated suites use original synthetic EPUBs and MockEngine fixtures, not downloaded books or live service availability. New cases cover short/legacy/no DOCTYPE, wrong XML contexts, malicious DTD/entities/paths/schemes, resource-vs-citation boundaries, mixed styles/internal links, semantic-cache roundtrips, duplicate/malformed/oversized API data, query-bound pagination, exact revision/metadata, errors/cooldown, cancellation/close, export redirects/type/size/EOF, altered but structurally valid books, atomic rejection, restart, network-closed reopening, leases, retry and space errors. Existing rights, visibility, storage, Library, readers, localization and updater suites remain enabled.

The two opt-in checks are not dependencies of build/test/check:

```sh
curl --fail --proto '=https' --max-time 90 --max-filesize 16777216 \
  --user-agent 'INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)' \
  'https://ws-export.wmcloud.org/?lang=es&page=El_matadero&format=epub-3' \
  --output /tmp/wikisource-el-matadero.epub
./gradlew :app:wikisourceFeasibilityCheck --args=/tmp/wikisource-el-matadero.epub
# Deliberate one-book network download + network-closed local reopening:
./gradlew :app:wikisourceOfflineCheck
```

The first task has no network client. The second uses the actual default source/review and manager; no injected approval bypass. Both clean their private temporary storage and print technical stages/counts/hashes, not book text or private paths. Development environments must preserve their existing proxy and CA settings for the JavaExec child too.

Final complete verification on Linux x86_64, JDK 21, Gradle 9.7.1, one Gradle worker and cached Skiko for headless Compose:

| Suite | Executions | Failures / errors / skipped |
| --- | ---: | --- |
| Desktop | 1,747 | 0 / 0 / 0 |
| Android-host | 1,466 | 0 / 0 / 0 |
| Core JVM | 60 | 0 / 0 / 0 |
| Core Android-host | 60 | 0 / 0 / 0 |
| **Total** | **3,333** | **0 / 0 / 0** |

**BUILD SUCCESSFUL in 14m 31s**. This final run includes the minSdk-compatible encoder and Unicode HTTP regression. All suites were actually executed, with no disabled tests, skipped test cases or weakened malicious-input assertions. The continuation adds 23 cases on each app host (9 XHTML/citation, 10 source/transport, 4 retained-download); Core's existing 60 cases per target remain intact. A prior full 3,331-case run also finished successfully during the environment disconnection; its workspace and reports were recovered, then the final code was verified again.

Android debug and **unsigned** release assembly, Desktop compilation, Android `check`, Lint debug/release, updater merged-manifest policies and `verifyDebugLanguageResources` passed. Lint XML reports have zero issues. Existing compiler warnings about deprecated test clipboard APIs and unrelated non-null assertions remain; they were not suppressed. `tools/check_localization.py`: **546 strings and 3 plurals** in en/es/ca; all six resource assets per APK match generated files byte-for-byte. Test fixtures, probes, book binaries and credential files are absent from the APKs.

Both merged manifests retain package `org.infinilect.app`, INTERNET, ACCESS_NETWORK_STATE and AndroidX's internal signature permission. The updater receiver remains explicitly non-exported, without an intent filter; REQUEST_INSTALL_PACKAGES is absent. Protected provider/download/updater/platform/signing/dependency/schema files are unchanged. Changed-file secret/artifact checks and `git diff --check` pass. Source hashes were frozen during validation; no code changed afterward. No APK/release is published.

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

These JVM/Android-host results and opt-in reader probes are not physical Android/Desktop graphical acceptance or Android frame-time measurements. Actual realme C55 validation remains pending.

## Physical validation — pending

1. On Android/Desktop, search **El Matadero** with Wikisource selected; only the reviewed Spanish work is expected. Search **Echeverría** to exercise the limited reviewed-author lookup. Other unreviewed wiki hits must not be offered as complete/downloadable books.
2. Open the EPUB online, verify all three sections including complete credit/license text. Internal chapter links/TOC navigate; external citations do not open automatically or fetch images/CSS. Inspect both original rights statements.
3. Request the EPUB download explicitly, observe real/indeterminate progress, cancel then retry; switch tabs during transfer. Backgrounding Android cancels according to the existing manager policy. An interrupted export must not show offline-ready.
4. Complete the download, enable airplane mode with Wi-Fi off, close/reopen the app, open the same record from Library/Downloads, navigate chapters, close/reopen and check exact position/history. Do not uninstall or clear data.
5. Delete only the download with confirmation; Library, tags/favorites/status, History and progress must remain. Check existing Internet Archive copies and imported EPUB/TXT/CBZ/PDF.
6. Verify source visibility default off, Gutenberg hidden, Internet Archive and Wikisource visible even during a transient network error. Opt in/out while requests are pending; hidden-source late results must not reappear and saved books remain accessible.
7. Spanish/English/Catalan UI, dark/light, narrow layout, large fonts and accessible controls. This PR does not announce English/Catalan Wikisource editions, PDF export, broad catalog coverage, external-link launching or completed realme C55 validation.

No APK or release is published. Keep PR #39 Draft until code review and physical acceptance.
