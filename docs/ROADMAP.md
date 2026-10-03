# Roadmap

## Foundation and second technical pass

- Bilingual README, GPLv3, contribution and third-party guidance.
- Architecture, source/cache policy and ADRs.
- Two-module Kotlin/Compose desktop scaffold.
- Pure domain contracts and tests for identity and resource ownership.
- Sequential resource handles with bounded small-resource materialization.
- Minimal language/provenance/rights metadata and revision-aware cache identity.
- Verified patch toolchain and documented migration to separate platform entry points.

## v0.0.1 — one real end-to-end path

Open INFINILECT → search for a book → get real results → open one → read it.

1. **Implemented:** verify Gutenberg's official OPDS search endpoint/OpenSearch
   query template and usage rules; no human-page scraping or aggregator.
2. **Implemented:** trusted desktop GutenbergSource using Ktor outside core,
   bounded StAX parsing, deterministic fixtures/MockEngine tests and an opt-in
   one-page live check. Detail/acquisition operations are explicitly unsupported.
3. **Implemented:** Compose search/results UI, Idle/Loading/Results/Empty/Error,
   explicit Next page action with no prefetch. Visual execution still needs a
   graphical desktop; see VERIFICATION.md. This is a search slice, not v0.0.1 completion.
4. **Implemented for a narrow subset:** select Internet Archive in Desktop,
   search public CC0 items, open a TEXT resource through ResourceLoader/
   ResourceContent, validate complete UTF-8 up to 512 KiB, display TextReader,
   and Back to retained query/results. Gutenberg remains search-only. No EPUB/PDF,
   reader settings or EPUB/PDF support. Graphical smoke verification is pending.
5. **First disk tier implemented:** bounded automatic resource cache with restart
   reuse only for trustworthy revisions, recency eviction and corruption checks.
   Current Archive null revisions still reacquire. L1 memory cache remains
   deferred; no SQL/database is needed for this disk slice.
6. **Implemented:** independent persistent logical reading progress for TEXT on
   Desktop/Android, code-point locator/current-line restoration, bounded atomic
   files and throttled saves. No bookmarks or source bypass;
   [progress policy](PROGRESS.md), [ADR 0015](adr/0015-persistent-reading-progress.md).
7. **Implemented:** local library and successful-open history in an app-private
   SQLDelight database, shared Search/Library/History navigation and source-resolved
   reopen. No cached metadata authorization or progress migration; physical A–J
   verification remains pending. [Policy](LIBRARY_HISTORY.md),
   [ADR 0016](adr/0016-local-library-history.md).
8. Verify the full path on Desktop and Android with real results and document its limits.

Completion means actual source-backed reading, not a simulated catalog or a
welcome window. No completed release is implied by `0.0.1-SNAPSHOT`.

## First acquisition experiment — verified TEXT prefix, no reader

OAPEN has accessible official alternate metadata with download links; REST403
and PDF transfer/access remain separate gates. See [OAPEN](OAPEN.md).
Internet Archive was selected after documented search/metadata/rights/file
experiments: one public CC0 government TXT was acquired through the existing
contracts, consumed up to 512 bytes and closed. A neutral CLI demo proves the
flow; no multiple-source UI, cache, persistent download, lending or reader.
[Comparison](ACQUISITION_COMPARISON.md), [scope](INTERNET_ARCHIVE.md).

That prefix experiment is now followed by the first UI TEXT reading slice:
[ADR 0012](adr/0012-bounded-text-reading.md). Full bounded UTF-8 validation is
implemented; full-file checksum revisions, broader charsets/formats and graphical
smoke validation remain separate. Gutenberg acquisition stays deferred pending
official guidance. The small government-document example is not a claim that
v0.0.1's full book/release roadmap is complete.

The first Android target now shares this exact reading path; build/APK inspection
are verified separately from physical-device smoke testing. See
[ADR 0013](adr/0013-first-android-application.md) and [verification](VERIFICATION.md).
No reader feature, new format or cache is added by the Android slice.

The first cache infrastructure slice added the persistent disk tier behind
ResourceLoader on both platforms; see [CACHE.md](CACHE.md) and
[ADR 0014](adr/0014-persistent-resource-cache.md). No cache was added by the Android
PR itself. Downloads/L1 memory and future revision mechanisms remain deferred. Independent
TEXT progress is now implemented in a separate persistent user-state store.

## After the first working slice

Android is now implemented as a separate launcher consuming the shared app library;
physical smoke verification remains pending. Add iOS with its own adapters/build
verification, accessible reader controls,
additional formats based on real needs, and explicit persistent downloads. If
Readium is selected, isolate it in an Android-specific reader. Expand the module
structure when concrete implementations justify it. A validated declarative
source schema can follow a second engine use case.

Translation, synchronization, dozens of sources, arbitrary executable plugins and
a complete plugin system are outside v0.0.1 and this search slice.

Gutenberg documents XML OPDS retirement planned for 2027 and an OPDS2 testing feed
requiring contact. Recheck the official interface before further source work;
do not silently substitute an aggregator or an undocumented endpoint.
