# Verification

## Gutenberg search slice — 2026-10-02

Started from integrated main `28619ed`, on `feature/gutenberg-search`. Core models,
contracts, build configuration and tests are unchanged. Kotlin/Compose compiler
2.4.20, Compose Multiplatform 1.12.1, Gradle 9.7.1 and Eclipse Temurin JDK
21.0.12.1+1 remain selected; additions are Ktor 3.6.0 and coroutines 1.11.0.

### Actual final checks

| Check | Result |
| --- | --- |
| `clean :core:jvmTest :app:desktopTest build` | BUILD SUCCESSFUL in 23s; 28 tasks executed, desktop recompiled/JAR built |
| Core JVM tests | 23 tests; 0 failures/errors/skipped |
| App desktop tests | 24 tests: parser 11, source 8, search controller 5; 0 failures/errors/skipped |
| `git diff --check` / staged diff | Passed |
| Core purity | No core diff; Kotlin-only imports; resolved runtime remains kotlin-stdlib 2.4.20 and annotations 13.0 |
| Resolved dependency/license inventory | Core runtime 2, app runtime 63, core tests 6, app tests 69, build plugins 22; all license evidence recorded in DEPENDENCIES.md |
| Local documentation links and bilingual README coherence | Passed |
| Optional live source check | Success in 19s including compilation/inventory; one real search page for `shakespeare`, 25 books, next token present |

Tests are deterministic and require no Internet for Gutenberg. Authored fixtures
cover actual search navigation shape, structured multiple authors, languages,
optional absence, resources/type mapping, revision absence, empty results and
pagination. Malformed/unsupported XML, DTD/external entities, namespace lookalikes,
size/depth/entry/namespace bounds, encoded URL preservation, forged/mismatched
continuations and wrong-source IDs are tested. MockEngine covers headers, one-page
requests, explicit next, no redirect/retry, HTTP errors, wrong media/compression,
missing/misleading lengths, actual body limits, cancellation, close and serialization
of concurrent callers. Controller tests cover all five states, ignored busy actions,
manual pagination, previous-page preservation/retry, cancellation and generic errors.
Normal tests do not exercise real server outages, timeouts or resource acquisition.

### Real Gutenberg evidence

Before implementation, read official Offline Catalogs and Feeds, Terms of Use and
Robot Access documentation. With an identifiable/contact User-Agent, fetched the
OPDS entry page and its linked OpenSearch description, then one `query=shakespeare`
page to verify the query parameter, navigation entries and next relation. No human
publication HTML page was fetched, scraped or used as a catalog. See SOURCES.md for
exact endpoints and rules, including planned XML OPDS retirement in 2027.

The separate live task then used the production GutenbergSource/Ktor Java engine,
not fixtures or curl, against the same official HTTPS OPDS search endpoint. It
returned 25 mapped book entries after filtering author/subject navigation. Example
IDs/titles: 1513 — Romeo and Juliet; 100 — The Complete Works of William Shakespeare;
49008 — The Works of William Shakespeare [Cambridge Edition] [Vol. 8 of 9]. The
real feed's next URL was validated/encoded but never requested. Real search entry
languages/resources/rights were absent; mapping those fields is verified with
fixtures, not claimed as an acquisition integration test. Details/resources were
not requested by the live task. A later URL-escape/namespace hardening adjustment
was covered by the final clean tests/build; no extra live request was made.

### Commands and environment

Successful final commands used the original repository configuration, with the
same environment-local JDK/proxy/certificate settings as the foundation. No HTTP
429 occurred during this slice's Gradle runs. No Maven mirror override or permanent
repository change was needed. The earlier foundation's 429 is historical below.
TLS verification stayed enabled. With JDK 21 configured:

```sh
./gradlew clean :core:jvmTest :app:desktopTest build \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew -I /tmp/infinilect-search-inventory.init.gradle \
  :app:gutenbergSearchCheck --args=shakespeare searchDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

The temporary inventory init script only registers an inspection task; it does not
replace repositories. Contributors use `:app:gutenbergSearchCheck` without it.
The live task is not a dependency of test/check/build. No compiler/deprecation
warnings remained in the final clean build. SLF4J reported that no provider exists
and selected its NOP logger during the live check: intentionally no logging backend,
telemetry or extra dependency was added.

### Limits and review before the reader slice

- No graphical desktop/Xvfb: SearchScreen was compiled, not run visually. No UI
  tests, desktop interaction/accessibility or native installer verification is claimed.
- Source/transport/parser are currently desktop-only; shared UI/state and core remain
  KMP-compatible. Android/iOS adapters are deferred, not assumed to work with JDK StAX.
- `getPublication` and `loadResource` throw explicit UnsupportedOperationException
  after source ownership checks. Before offering Open, verify detail/acquisition
  endpoints and implement ResourceContent, charset, cancellation/close and revisions.
- Gutenberg search often supplies author display summaries instead of structured
  authors, and omits languages/resources. No enrichment requests are made. Only the
  observed book/search subset is implemented; not a complete OPDS validator/engine.
- Pagination is tested with fixtures/MockEngine and its real next link is validated;
  the actual second page was not fetched. Redirects/compressed feeds are rejected.
- There is no cache, download, persisted progress or reader. v0.0.1 is not finished.
- Recheck Gutenberg's XML retirement/OPDS2 access plan and source rights before
  extending the adapter. Verify graphical execution and IDE import manually.

## Foundation review (historical)

Second technical pass performed on 2026-10-02 in Linux x86-64. Final versions:
Kotlin/Compose compiler 2.4.20, Compose Multiplatform 1.12.1, Gradle 9.7.1,
Eclipse Temurin JDK 21.0.12.1+1.

### Actual checks and results

| Check | Result |
| --- | --- |
| Official Compose release/tag/API | 1.12.1 exists, published 2026-09-22, not draft/prerelease; retained |
| Wrapper update and regeneration under 9.7.1 | Success; distribution and JAR SHA-256 verified, see TOOLCHAIN.md |
| `clean :core:jvmTest` | Success in 20s, 22 tests before adding the multi-buffer regression case |
| `build foundationDependencyInventory` | Success in 16s; core and app built, desktop Kotlin compiled and JAR generated after clean |
| Final `:core:jvmTest build foundationDependencyInventory` | Success in 15s; 23 tests, 0 failures/errors/skipped |
| Build using original repositories (without mirror override) | Failed in 11s during plugin resolution: Maven Central HEAD returned HTTP 429 for Kotlin's BOM |
| Dependency comparison with original foundation | No changes in desktop runtime (46 artifacts, including local core), test (6) or build-plugin (22) graphs; no new dependencies |
| Core runtime graph | Only kotlin-stdlib 2.4.20 and JetBrains annotations 13.0; no Compose/Ktor/Readium/platform API imports |
| Local Markdown links and `git diff --check` | Passed |

Final tests: PublicationTest 7, ResourceContentTest 13, ResourceCacheKeyTest 3.
They cover source-aware identity, type/format independence, metadata absence and
preservation, ownership/uniqueness, representation/revision separation, unknown
revision keys, short reads across multiple buffers, known/unknown size limits,
EOF/zero limits, overflow-safe arithmetic, cleanup and error/cancellation propagation.
Fixtures are in-memory and use Kotlin's standard coroutine primitives without a
new dependency. Cancellation tests inject a cancellation signal; they do not claim
real HTTP scheduling/cancellation, adapter close behavior or cache persistence.
App tests remain `NO-SOURCE`; no UI test pass is claimed. No compiler/deprecation
warnings were reported by the final successful build with `--warning-mode=all`.

### Environment and commands

The initial environment provided only a JRE. The full Temurin JDK was downloaded
from its official GitHub release, checksum-verified and installed under `/tmp`.
It uses the environment's Java certificate store; TLS verification remains enabled.

The session still encounters a Maven Central IP rate limit. A direct GET returned
200, but Gradle's HEAD through the plugin portal returned 429, so that single GET
was not evidence of restored full resolution. Successful checks used a temporary
init script replacing repositories with JetBrains' official cache redirector for
the same Central/plugin portal/Google Maven coordinates. Project repository
configuration is unchanged. With the verified JDK and proxy/certificate settings:

```sh
./gradlew -I /tmp/infinilect-repositories.init.gradle clean :core:jvmTest \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew -I /tmp/infinilect-repositories.init.gradle build foundationDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
./gradlew -I /tmp/infinilect-repositories.init.gradle :core:jvmTest build foundationDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

The init script and inventory task are inspection tools outside the repository.
They inspect `desktopRuntimeClasspath`, `jvmTestRuntimeClasspath`, `jvmRuntimeClasspath`
and the root build-plugin classpath. POM license declarations are recorded in
[DEPENDENCIES.md](DEPENDENCIES.md), with upstream notices in THIRD_PARTY_NOTICES.md.
The Gradle patch license remains Apache-2.0. No runtime or test library was added.
Normal contributor verification remains `./gradlew :core:jvmTest` and `./gradlew build`
with JDK 21; retry normal repository resolution outside this rate-limited session.

### Limits and manual checks before merge

- No graphical desktop/Xvfb was available: `:app:run` and interactive/visual behavior
  were not tested. Manually open the existing welcome window in a graphical session.
- JetBrains' Kotlin 2.4.20 compatibility table still ends at Gradle 9.7.0. The patch
  9.7.1 is Gradle's recommended update and passes this project's tests/build, not an
  asserted update to JetBrains' certification table. Check IDE import with JDK 21.
- Review the resource ownership/close and unknown-revision revalidation contracts
  before implementing a source. Transport cancellation, revision mapping, byte limits
  and charset handling need actual adapter integration tests later.
- Native installers, full native redistribution notices, Windows/macOS runtime,
  Android/iOS targets and platform entrypoint migration are not verified here.
- Gutenberg/OPDS, cache, downloads and persisted progress remain unimplemented;
  no end-to-end v0.0.1 reading test is claimed.

The earlier foundation passed 4 tests with Gradle 9.7.0; that historical result is
superseded by this pass's final 23-test/9.7.1 verification.
