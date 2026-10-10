# Search, discovery and local recommendations — PR #29

Audited against main `1d11bdc8a8e8f94a26fb2409182bbd02167b2879`, the
PR #28 squash merge. Sources obtain publications; existing readers still display
freshly authorized resources. No reader engine, progress/identity schema,
acquisition permission, dependency, application ID or signing change.

## Actual capabilities

| Source | Search / browse | Categories | Language | Pagination | Artwork | Acquisition | Rights / limitations |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Internet Archive | Existing advanced-search API; unified literal title/author/keyword search; exact subject browse | Supplied `subject`; seven documented exact aliases/segments | Supplied tags, locally filtered | Source-owned token, 10/page | Catalog covers not exposed by this verified adapter; typographic fallback | Existing refreshed public CC0 TEXT subset; PDF delivery metadata exists but this app option does not enable PDF reading; EPUB/CBZ acquisition remains disabled | Search remains its narrow CC0/texts subset. Item/file restrictions, current license and delivery validation remain authoritative; CC0 search indexing is not worldwide eligibility |
| Project Gutenberg | Experimental development OPDS2 search; general keyword discovery, no verified genre browse route | Optional OPDS subject names; the live classics page did not supply subjects | Supplied tags, locally filtered | Advertised validated `next`, 25/page | Advertised medium/small JPEG thumbnail only on exact per-book Gutenberg routes; optional/fallback | **Catalog only**, resources empty. No EPUB/TEXT download or legacy RDF/mirror fallback | Preserve dedicated rights when supplied; absent means unknown. USA rights prose and metadata CC0 do not establish worldwide book rights |
| Imported files | Existing private title/author lookup; `*` lists imports | Not present in saved metadata; never guess from title | Available saved language metadata | UI slices 25 matches/page; existing source is bounded | Existing real EPUB/CBZ thumbnails; PDF/TEXT fallback | Existing owned local resources, unchanged | No network; publication-specific library/history/progress retained |
| Original debug EPUB/comic demos | Existing explicitly selectable fixtures, not public discovery | None | Only supplied data | Small original fixture sets | Existing/fallback | Existing development route only | Excluded from default unified queries and Home discovery |
| OAPEN | Diagnostic REST/OAI-PMH access experiments only | Not an app source | Not an app source | Not an app source | None implemented | No `OapenSource` | Prior REST403 and alternate metadata are separate from verified delivery |
| Standard Ebooks | Planned, no implemented source | — | — | — | — | No integration | No guessed endpoint or new provider |

## Ownership and navigation

`DiscoverySource` is an optional application adapter alongside the unchanged core
`PublicationSource`. `DiscoveryEntry` holds inert presentation metadata, not byte
permission. Existing clients/parsers are reused. `ApplicationSession` owns one
shared `DiscoveryCatalog`, latest-intent `DiscoveryController` and optional
`HomeDiscovery`. No second HTTP client, global navigation queue or reader session
is created for each result. Opening details requires no Library membership.
Opening content routes through the result's own source and existing fresh metadata,
rights/resource validation, owned preparation and semantic locator restoration.

Search supports independent source filters, 350ms debounce, Enter/IME submission,
per-source retry and explicit More. Editing cancels obsolete work immediately;
generation checks reject stale results. One failed provider leaves other results
intact. Duplicate submit coalesces. Failed pagination keeps previous results and
retries the failed token. Four pages/source are retained, then users refine the
query. Filtering languages is **local over loaded pages**, not a guarantee of a
server-wide filtered total: en/eng, es/spa, fr/fra/fre, de/deu/ger, pt/por and their
regional tags; missing language is excluded. Other supplied languages remain
visible when no filter is selected. No persistent recent-search record yet.

Archive's unified free text is escaped as a literal phrase; `*` is an explicit
browse-all request. A fixed enum supplies quoted subject filters. User operators
cannot break the outer source subset or impersonate a genre query. The existing
source-level advanced-search diagnostic contract is unchanged. Categories map
only exact supplied names or `--` subject segments, never title guesses. Unsupported
sources are excluded from a genre request. Subject-row membership can rely on the
source's exact filter even when a long optional subject list is excerpted.

Deduplication uses only `(SourceId, localId)`. Cross-source work/edition identifiers
are absent, so same titles, translations, editions and different rights situations
remain separate. Local imports of the same work cannot safely be equated to a
catalog identifier. Metadata details reuse the viewport-constrained PR #28 UI,
plain-text synopsis, existing membership actions, confirmation on removal, legal
notice and keyboard/Back dismissal. Catalog-only library entries remain metadata.

## Home and explainable recommendations

Local greeting, Continue reading, Recently added and import/empty states remain
available offline. Online Home discovery is off until explicitly enabled in Home
or Settings. Once enabled it requests one general page per implemented remote
catalog and two exact Archive subject pages; it never preloads publication bytes
or traverses pagination in the background. Empty genre rows are omitted; View all
opens the actual subject query. Failures/retry do not block local Home.

Personalization uses optional explicit genre interests and at most 20 recent local
History entries **whose subjects are already known in the current bounded catalog
results**. No hidden per-Library metadata enrichment is performed. Each matching
interest adds 4; recent known genre frequency adds at most 2. Stable score ordering
and provider round-robin supply up to 12 distinct candidates; exact Library IDs
are excluded. No signal means **Explore the catalogs**, explicitly general discovery.
Missing metadata limits inference, especially for local imports and Gutenberg.

Settings can disable personalization while preserving interests; disabled means
neither ranking nor Home subject selection uses those interests/history. Reset
clears explicit interests and stores a cutoff for inferred activity; it does not
delete Library, History or saved progress. Later real reading can build new signals.
The existing private atomic appearance/profile record is backward-compatible with
its legacy v1/v2 records and adds bounded v3 discovery preferences, still ≤512 bytes.
Theme/profile edits retain discovery choices and vice versa. No database migration.

## Privacy, legal and resource boundaries

Only public query/filter parameters and source-advertised thumbnail requests leave
the device. Local Library/History, semantic positions, name and residence country
are never uploaded. Selected interests can influence ordinary subject queries.
Requests use the existing project user-agent/contact and normal network transport;
providers see connection/network information. Country is user-declared, not verified
legal eligibility, and is not sent or used as a universal copyright classifier.
Discovery is separate from acquisition; no restriction, licensing or geographic
acquisition check is bypassed. No cloud profile, AI API, new provider or scraper.

Explicit budgets:

- Two active catalog preparations; source clients also serialize their existing
  operations. At most one latest page task per configured source plus one Home coordinator with at most two child requests. No automatic retry, crawling or unlimited prefetch.
- Eight LRU pages ×25 display entries, five-minute TTL, shared Home/Search keys;
  same-key callers borrow one cancellable job. Last borrower cancellation retires it.
  Search retains ≤100 entries/source (40 for Archive's four 10-entry pages), Home
  at most four pages/100 raw candidates. Active production sources number three;
  debug demos add only their small existing fixtures. Buffers do not grow with time.
- Per entry: title2048, eight authors×256, eight languages×64, sixteen subjects×256,
  synopsis4096 and verbatim source rights≤16,384 UTF-16 units. Optional Archive display
  fields are independently capped/ignored when malformed; identity and acquisition
  fields stay strict. Existing HTTP/JSON size/depth, cancellation and URL bounds
  still apply (Gutenberg1MiB, Archive2MiB); no security parser limit is increased.
- Existing cover owner: 24 combined lease/cache/pending slots, one decode/conversion,
  thumbnails≤192×288; ≤5,308,416 bytes cached RGBA bitmap payload, plus one transient
  bounded raster/conversion. No extra bitmap cache or private disk content cache.
- Gutenberg remembers at most100 advertised thumbnail declarations. HTTPS exact
  `www.gutenberg.org/cache/epub/<id>/pg<id>.cover.{medium,small}.jpg` only for that
  validated ID; never guessed when absent. No credentials/query/fragment/redirect,
  arbitrary URL or publication payload. MIME JPEG, ≤512KiB actual stream and ≤512×768
  encoded dimensions, 3s request timeout, existing bounded sampled decoder. At most
  one thumbnail task in the shared pipeline (up to two other catalog tasks). Artwork
  has **no publication format/acquisition authority**. Missing/failed art uses a
  typographic cover; negative thumbnail results may persist in that session cache.
- Closing cancels catalog/controller/Home work and clears metadata and advertised
  thumbnail maps. Screen/reader exit cancels obsolete requests. Cover leases reuse
  existing release/cancellation. Preferences alone persist privately; no book payload,
  thumbnail or catalog content is added to disk by discovery.

## Verification and manual acceptance

Deterministic tests use original synthetic metadata/images and MockEngine, not live
services. They cover normalization, independent failures/retry, stale/cancelled work,
pagination, filtering, exact subjects, recommendation bounds/opt-out, cache TTL/LRU,
coalescing/deduplication, private preference migration, unknown rights and unchanged
acquisition authorization. Discovery copy is centralized for future localization. Existing App-level continuity
and reduced-height layout fixtures use the new details/grid flow without removing
their ownership, remount, preparation-count or accessibility assertions.
Shared real headless Compose tests cover compact/wide,
large-font/light/dark layouts, details, source attribution, keyboard/Back, offline
Home, opt-in and publication-specific saved progress. Existing reader/security suites
remain intact. Preview images are synthetic headless UI, not physical-device photos.

Live metadata diagnostics on 2026-10-10: experimental Gutenberg root200, one classics
search200 (25 entries; subjects absent in inspected samples; 50 advertised image links);
Archive Philosophy query200 (10 entries, indexed total415). These are bounded public
metadata observations, not ongoing provider availability or verified book acquisition.
With this environment's supported proxy and system CA trust (external diagnostic
configuration only), the **real Ktor adapters** also succeeded at
2026-10-10T01:37:58Z: Archive one request/389,714 bytes, ten entries with categories,
resources0; Gutenberg root + search + one advertised JPEG, three requests/239,331
bytes, 25 entries and thumbnails, resources0, decoded thumbnail100×141 with no
publication format authority. All four responses200, no redirects, details,
publication payloads or pagination. Earlier diagnostic failures were missing
runtime proxy/CA configuration, not justification to disable TLS or change app
configuration. Provider ordering/results can change between probes. Final app regressions: Desktop1342 and Android-host1160,
zero failures/errors/skips in their final runs; Android assembleDebug and Desktop
compileKotlin passed on JDK21/Gradle9.7.1. One combined attempt hit an unchanged
PageSpreadLayoutTest presentation wait; its full24-test suite and the complete
Desktop rerun passed without relaxing assertions. Exact cause remains unconfirmed.
No Android-device timings are claimed.

Android acceptance remains pending:

1. Upgrade without clearing data; verify profile, Library, History and deep reader
   progress. Local Home must stay useful with no connection and online Home disabled.
2. Search a title/author; toggle sources and five supported language filters; type
   rapidly, retry a failed provider, and explicitly load more without losing others.
3. Browse a declared subject and Home View all; enable Home discovery, select interests,
   opt out/reset them, and verify no fake or empty recommendation rows.
4. Inspect result attribution, optional covers, synopsis, languages, categories and
   rights uncertainty; Gutenberg must remain catalog-only. Details/Library actions
   must not reset reading progress or duplicate identity. Confirm removal scope.
5. Open a compatible Archive item through fresh validation and a local EPUB/CBZ/PDF/TEXT;
   return to the retained Home/Search state and reopen at the saved position.
6. Check narrow/rotated screens, large fonts, dark system bars, TalkBack, keyboard
   focus/Enter/Escape and Desktop resizing. Native Desktop graphical acceptance is
   separate and pending.
