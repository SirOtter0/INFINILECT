# Search, discovery and local recommendations — PR #29

Audited against main `1d11bdc8a8e8f94a26fb2409182bbd02167b2879`, the
PR #28 squash merge. Sources obtain publications; existing readers still display
freshly authorized resources. No reader engine, progress/identity schema,
acquisition permission, dependency, application ID or signing change.

## Actual capabilities

| Source | Search / browse | Categories | Language | Pagination | Artwork | Acquisition | Rights / limitations |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Internet Archive | Existing advanced-search API; unified literal title/author/keyword search; exact subject browse | Supplied `subject`; seven documented subjects with exact aliases/segments | Supplied tags, locally filtered | Source-owned token, 10/page | Catalog covers not exposed by this verified adapter; typographic fallback | Existing refreshed public CC0 TEXT subset; PDF delivery metadata exists but this app option does not enable PDF reading; EPUB/CBZ acquisition remains disabled | Search remains its narrow CC0/texts subset. Item/file restrictions, current license and delivery validation remain authoritative; CC0 search indexing is not worldwide eligibility |
| Project Gutenberg | Experimental development OPDS2 search; general keyword discovery, no verified genre browse route | Optional OPDS subject names; the live classics page did not supply subjects | Supplied tags, locally filtered | Advertised validated `next`, 25/page | Advertised medium/small JPEG thumbnail only on exact per-book Gutenberg routes; optional/fallback | **Catalog only**, resources empty. No EPUB/TEXT download or legacy RDF/mirror fallback | Preserve dedicated rights when supplied; absent means unknown. USA rights prose and metadata CC0 do not establish worldwide book rights |
| Imported files | Existing private title/author lookup; `*` lists imports | Not present in saved metadata; never guess from title | Available saved language metadata | UI slices 25 matches/page; existing source is bounded | Existing real EPUB/CBZ thumbnails; PDF/TEXT fallback | Existing owned local resources, unchanged | No network; publication-specific library/history/progress retained |
| Original debug EPUB/comic demos | Explicit developer-only fixtures; hidden from normal discovery | None | Only supplied data | Small original fixture sets | Existing/fallback | Existing development route only | Excluded from the normal source selector, unified queries and Home discovery |
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

Search supports a compact filter panel, inline Clear search, independent source filters, 350ms debounce, Enter/IME submission,
per-source retry and explicit More. Editing cancels obsolete work immediately;
generation checks inside state updates reject stale results. Tab return preserves scroll/results and resumes only interrupted requests, including the exact pending pagination token. Platform backgrounding retires discovery without closing the retained application session; foregrounding resumes its visible destination. One failed provider leaves other results
intact. Duplicate submit coalesces. Failed pagination keeps previous results and
retries the failed token. Four pages/source are retained, then users refine the
query. Filtering languages is **local over loaded pages**, not a guarantee of a
server-wide filtered total: en/eng, es/spa, fr/fra/fre, de/deu/ger, pt/por and their
regional tags; missing language is excluded. Other supplied languages remain
visible when no filter is selected. No persistent recent-search record yet.

Archive's unified free text is escaped as a literal phrase; `*` is an explicit
browse-all request. A fixed enum supplies quoted subject filters and exact OR aliases (for example Adventure stories/Adventure and Love stories/Romance). User operators
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
  developer demos remain separately owned for explicit development workflows and are not part of normal discovery. Buffers do not grow with time.
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
  one thumbnail task in the shared pipeline and the source image gate (up to two other catalog tasks). Image HTTP/decode no longer holds the serialized Gutenberg catalog-state lock; slow artwork cannot block unrelated metadata search. Artwork
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


## Android acceptance follow-up

USER-REPORTED PHYSICAL ANDROID: most search/discovery, persistence and acquisition
checks worked, but Home/online discovery and Search → Home → Search caused frequent
closures; cover browsing was slow; Adventure appeared empty. These reports are
reproduction evidence. No device crash stack or device performance measurement was
available during this follow-up. The fatal Android crash root cause remains
**unconfirmed**, and this PR is **not ready for merge** on automated results alone.

Five deterministic defects were reproduced before changing production:

- A source cancelling its own transport task propagated `CancellationException`
  into an otherwise live consumer, leaving Search/Home loading indefinitely.
  Caller/owner cancellation still propagates; independent transport cancellation
  now becomes a controlled retryable failure. This is a loading defect, not proof
  of the reported process crash.
- Leaving during pagination retained content but did not resume the pending token.
  Interrupted loads now resume on return; completed results are not refreshed.
- Search's composition-local effect scrolled to the beginning on every remount.
  The search viewport intent now belongs above the tab composition.
- `DiscoveryState.entries` flattened/deduplicated on each read. One immutable
  snapshot per state and memoized Home ranking avoid repeated presentation work;
  stable item keys/types, thumbnail dimensions and all cache budgets are retained.
- Gutenberg thumbnail HTTP/decode held the catalog metadata mutex. It now uses
  a separate one-permit image gate under the same source cancellation ownership;
  metadata mutations remain serialized. No bitmap cache or resource-budget increase.

Adventure verification: five bounded public metadata requests on 2026-10-10,
all HTTP200, no acquisition. Adventure stories alone returned indexed total0;
Adventure stories OR Adventure returned33; Philosophy415; Love stories OR
Romance21; History1323. Only two records/request were inspected. These totals are
provider snapshots within the existing CC0/texts subset, not worldwide legal
eligibility or full-catalog coverage. Language filtering remains local; an empty
filtered page retains its source pagination token and does not imply an empty genre. An empty Archive response page also retains explicit pagination when the bounded indexed total reports later pages; the existing four-page UI limit still applies.

Normal debug APKs no longer automatically enable demos. Android requires the
explicit `developmentSourcesEnabled` factory argument *and* a debuggable app;
Desktop's existing explicit environment/property switches remain. Fixture sources
remain available to tests/developers, but are excluded from normal discovery.
The filter panel and contextual help retain language, privacy and rights caveats;
rights also remain in details/results. Clear search keeps source/language/genre
choices; Reset filters restores defaults without clearing the typed query.

Debug Android diagnostics use `INFINILECTDiscovery` with source ID and an enum
failure category only. No search query, metadata, exception message, profile,
Library/History, URI, password or filesystem path is logged by this hook.
To capture a device failure with Android platform-tools:

```sh
adb logcat -v threadtime INFINILECTDiscovery:W AndroidRuntime:E '*:S' > infinilect-pr29-logcat.txt
```

Keep it running while reproducing Search Cervantes → Home → Search, then stop
with Ctrl+C. Immediately after a closure also capture the crash buffer:

```sh
adb logcat -b crash -d -v threadtime > infinilect-pr29-crash.txt
```

Share the relevant INFINILECT `FATAL EXCEPTION`/`Caused by` section and diagnostic
lines after redacting personal data and unrelated apps. Report whether Android
instead displayed an application-not-responding dialog. No device access, physical
fix acceptance, Android FPS, TalkBack or native Desktop graphical acceptance is
claimed here.

Focused physical re-acceptance:

1. Search Cervantes, scroll, switch Home/Search repeatedly and change the query;
   repeat during loading/pagination and after background/foreground.
2. Toggle Home discovery rapidly; open/close details; exercise offline/reconnect
   and source retries. Capture Logcat if a closure occurs.
3. Browse many covers in Search and Home. Verify smoothness on the device.
4. Check Adventure, Philosophy, Romance and History; exercise languages/More and
   distinguish this limited CC0 subset from the full Archive catalog.
5. Confirm demos are absent, × clears without resetting filters, filters/help
   remain accessible at large text sizes, and Enter/Back/Escape work.
6. Recheck existing profile/Library/History and saved local EPUB/CBZ/PDF/TEXT
   positions, plus unchanged acquisition validation and Gutenberg catalog-only behavior.


Host performance evidence (Linux amd64, JDK21.0.12.1): the exact former
flatten/deduplicate getter versus the new retained snapshot, 100 synthetic entries,
20,000 reads, median of five warmed samples. Former expression: 53.603274ms and
151,360,048 thread-allocated bytes. Snapshot: 0.788056ms and 48 bytes of measurement
overhead. This isolates repeated list allocation; it is not an Android frame-time,
network-latency or memory-pressure measurement. A timing threshold is deliberately
not a unit-test assertion. Slow-thumbnail regression uses a controlled transport
barrier and proves a second catalog request reaches HTTP before the image completes.

Keyboard verification covers both Enter on the query (immediate Search) and Enter
on the focused trailing × (Clear search). Filters expose checked state and ≥48dp
hit targets. The continuity fixture now finds the submit icon by its accessible
name, retaining every source/preparation/remount assertion.

Final focused selections: Desktop95 and Android-host57 tests, all passed with zero
failures/errors/skips, including controller/navigation, transport cancellation,
pagination, genres, privacy, thumbnail contention, headless UI/keyboard, retained
reading-session continuity and the unchanged 24-test spread UI suite. The initial
full run had a text-button lookup failure in the continuity fixture and a spread
fixture initialization-wait failure (`Spread 0 must be fully presented`). The former
was repaired for the accessible icon; the complete spread suite subsequently passed
unchanged. The final complete suites passed: Desktop1361 and Android-host1174,
with zero failures/errors/skips. Android `:androidApp:assembleDebug` and Desktop
`:desktopApp:compileKotlin` passed on JDK21 / Gradle9.7.1. Core and reader engines
were unchanged. No assertion was relaxed; no test was disabled or skipped.
