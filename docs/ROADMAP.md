# Roadmap

## Durable local-file workflow — Draft PR #19

Android SAF/Desktop native selection → bounded validated owned copy → local source
→ existing TEXT/EPUB/CBZ readers → existing Library/History/progress is implemented.
Source identity is independent of the original location; exact-byte deduplication
and non-destructive Library removal preserve user ownership. Physical picker/update/
restart and graphical Desktop checks remain separate human gates. PDF, explicit
import deletion/orphan cleanup, bulk/directory import and backup remain future work.
[Policies and limitations](LOCAL_IMPORT.md). No v0.0.1 completion claim.

## EPUB usability and local media — Draft PR #16

Session font/spacing/margin/theme controls, semantic heading/list distinctions, captions/
preformatted/separators and bounded local PNG/JPEG presentation are implemented for
the original development route. Passive-only, no CSS/browser/production acquisition.
Layout changes preserve typed progress; TEXT remains untouched. Device codec/layout/
accessibility and graphical Desktop acceptance remain pending. [Budgets and plans](EPUB_READER.md),
[ADR 0022](adr/0022-bounded-epub-media-and-presentation.md). No v0.0.1 completion claim.

## First semantic EPUB reader — merged PR #15

A narrow shared Compose EPUB3 subset now reads original development publications
through real preparation, chapter/TOC/internal navigation, typed persisted semantic
progress and source-resolved Library/History reopen. Production EPUB acquisition
is not enabled. At that stage CSS/images/active content were unsupported.
The user reported physical Android verification including corrected system Back;
PR #16 expands passive presentation only, with new manual verification pending. [Exact subset and plans](EPUB_READER.md). No release or
v0.0.1 completion claim. The large TEXT behavior from merged, approved PR #14 is
preserved; its Android improvement was reported by the reviewer.

## Large TEXT hardening — Draft PR #14

The reported Android backward-scroll speed defect is traced to cold multi-line
windows temporarily collapsing to one-line loading items. Shared reading now
retains measured geometry, uses global logical keys and one bounded loading worker
with symmetric neighbor prefetch. Tests cover bidirectional anchors, eviction,
late loads, Unicode and durable progress compatibility. Android gesture symmetry
and Desktop graphical behavior still require human verification; host simulations
are not physical smoke tests. [Policy](TEXT_READER.md),
[decision](adr/0020-stable-text-viewport.md), [results](VERIFICATION.md).
The separate niri/Wayland black-area observation is untouched, pending Windows
comparison. No EPUB renderer, source acquisition change or completed release.

## Bounded EPUB foundation — merged PR #13

Pure reader-facing EPUB contracts and bounded ZIP/container/package/manifest/
spine/XHTML structural preparation are implemented for Desktop and Android.
At that stage no renderer or UI reading action was enabled. Limits/subset and platform
verification gaps are in [EPUB.md](EPUB.md) and [ADR 0019](adr/0019-bounded-epub-foundation.md).
PR #15 adds a separate passive shared renderer and a typed EPUB
logical locator; TEXT progress cannot be reused as EPUB progress. Gutenberg stays
experimental catalog-only and IA rights/acquisition/cache policy stays unchanged.
No release/v0.0.1 completion or new manual Desktop observation is claimed.

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

1. **Historical:** original Gutenberg XML discovery; deprecated in direct email.
   PR #11 removes production use of that unmaintained feed.
2. **Experimental in corrected PR #11:** official email-supplied OPDS2 development
   catalog, shared bounded JSON mapping, search/details and metadata-only Library.
   No verified current TEXT acquisition contract; no RDF or legacy fallback.
3. **Implemented:** Compose search/results UI, Idle/Loading/Results/Empty/Error,
   explicit Next page action with no prefetch. Visual execution still needs a
   graphical desktop; see VERIFICATION.md. This is a search slice, not v0.0.1 completion.
4. **Implemented for a narrow subset:** select Internet Archive in Desktop,
   search public CC0 items, open a TEXT resource through ResourceLoader/
   ResourceContent, incrementally validate/index UTF-8 up to 16 MiB, display TextReader,
   and Back to retained query/results. Gutenberg remains experimental catalog-only. Production EPUB/PDF and
   TEXT reader settings remain deferred; global persistent EPUB settings are in Draft PR #16. Codex graphical Desktop verification is pending;
   PR #10 Android large-TEXT success was human-reported.
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
   reopen. No cached metadata authorization or progress migration. The user reports
   that corrected PR #8 Library/History survive physical Android process restart;
   PR #9 physical testing also confirmed catalog actions and the basic reading flow. [Policy](LIBRARY_HISTORY.md),
   [ADR 0016](adr/0016-local-library-history.md).
8. **Implemented in PR #10:** disk-backed indexed TEXT preparation, bounded lazy
   windows and existing logical progress compatibility. Human-reported physical Android verification confirms >512 KiB opens, forward/
   backward scrolling, Back/reopen and process-restart progress; brief extreme-scroll
   loading resolves quickly. This was not a Codex device test; [TEXT policy](TEXT_READER.md).
9. **Human Android verification passed through merged PR #11:** existing persistent
   state, IA reading/progress, experimental Gutenberg OPDS2 search, explicit
   pagination, metadata-only Library, safely unsupported Gutenberg opening and the
   final Next correction starting at the top/first result. Library/History/catalog
   actions and cache deletion preserving user state also passed. This evidence was
   supplied by the reviewer, not a Codex physical-device test. **Graphical Desktop
   verification remains pending**; automated checks and the reviewer plan are in
   [VERIFICATION.md](VERIFICATION.md).

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
smoke validation remain separate. PR #11 corrects its earlier acquisition assumption: the actual email directs
OPDS2 testing, not RDF/direct downloads; see [Gutenberg policy](GUTENBERG.md). The small government-document example is not a claim that
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
human-reported physical verification through merged PR #11 covers persistence,
large TEXT and the corrected experimental Gutenberg catalog, including Next at
the first result. Graphical Desktop verification is the remaining platform smoke
gap for this slice. Add iOS with its own adapters/build
verification, accessible reader controls,
additional formats based on real needs, and explicit persistent downloads. If
Readium is selected, isolate it in an Android-specific reader. Expand the module
structure when concrete implementations justify it. A validated declarative
source schema can follow a second engine use case.

Translation, synchronization, dozens of sources, arbitrary executable plugins and
a complete plugin system are outside v0.0.1 and this search slice.

Gutenberg's public page still mentions planned XML retirement in 2027, but direct
email explicitly discourages the unmaintained OPDS0.9 feed now. Its supplied OPDS2
endpoint is development-only; production preview and TEXT acquisition direction
remain external gates. No silent fallback/aggregator or guessed production URL.

## Library actions from catalog results — implemented in PR #9

The previous v0.1 follow-up is implemented: Search results expose Add/Remove and
committed Library membership without opening a reader. A single bounded library
snapshot serves all result rows; pending actions serialize per PublicationId.
Search/Reader share membership, and Library/History retain simple metadata lists.
Saving changes no History, ReadingProgress or cached bytes and makes no source
request. Later opening still resolves PublicationId through its owning source;
stored metadata/rights/URLs never authorize acquisition. No schema/dependency
change, cover requests or broader reader features. PR #9 physical testing has passed; [policy and manual plan](LIBRARY_HISTORY.md#pr-9-catalog-action-physical-test-plan).

## Experimental Gutenberg OPDS2 — corrected PR #11

Search/details/catalog Library saves now use the email-supplied development JSON
service. The inspected item advertises EPUB only; reading is deliberately disabled.
Previous RDF/direct-file transfers are technical history, not an acquisition
recommendation. IA remains the verified reader path; user stores are unaffected.
[Evidence/limits/manual plan](GUTENBERG.md). Human Android verification now passed
including the final pagination correction; Desktop graphical verification is still
pending. v0.0.1 is not declared complete.

## First bounded comic/page foundation (Draft PR #17)

A controlled original 24-page development comic now exercises PageDocument → bounded
raster decode → shared PageReader on Android/Desktop, with RTL/LTR paging, zoom/pan,
lazy vertical/webtoon, durable semantic progress and separately persisted global mode.
No production comic source, CBZ, PDF, manga-site compatibility or Mihon integration.
Physical Android and graphical Desktop acceptance remain explicit review gates.
Future CBZ/web/download adapters may reuse PageDocument; PDF needs a separate reader.
This does not complete v0.0.1. [Limits/manual checklist](PAGE_READER.md).

## Bounded CBZ preparation (Draft PR #18)

The original development comic now also ships as an opt-in CBZ resource. A shared
ZIP32 inspector serves EPUB and CBZ structural safety; strict PNG/JPEG CBZ pages map
to the existing PageDocument/PageReader through synthetic ordinal keys and
deterministic natural path order. ZIP order is not reading order. No production comic
source, CBR/RAR/7z, local import, PDF, permissions or dependency is added. Android
uses app cache storage and Desktop its safe per-user cache root; preparation bytes are
disposable and separate from persistent progress and collections. Draft status means
the full verification matrix and physical Android/Desktop acceptance remain open.
