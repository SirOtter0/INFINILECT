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
4. Resolve one supported representation through ResourceLoader/ResourceContent
   and implement a minimal reader (prefer TEXT if the verified source offers it),
   with a measured byte limit, verified charset and source metadata display.
5. Add bounded memory/disk caching with restart-safe eviction and an independent
   progress store; verify progress survives clearing cache. Add SQLDelight only
   if this persistence implementation needs it.
6. Verify the full path on desktop with real results and document its limits.

Completion means actual source-backed reading, not a simulated catalog or a
welcome window. No completed release is implied by `0.0.1-SNAPSHOT`.

## After the first working slice

Add Android and iOS targets with build verification, using the platform-entrypoint
migration in ARCHITECTURE/ADR 0008, accessible reader controls,
additional formats based on real needs, and explicit persistent downloads. If
Readium is selected, isolate it in an Android-specific reader. Expand the module
structure when concrete implementations justify it. A validated declarative
source schema can follow a second engine use case.

Translation, synchronization, dozens of sources, arbitrary executable plugins and
a complete plugin system are outside v0.0.1 and this search slice.

Gutenberg documents XML OPDS retirement planned for 2027 and an OPDS2 testing feed
requiring contact. Recheck the official interface before further source work;
do not silently substitute an aggregator or an undocumented endpoint.
