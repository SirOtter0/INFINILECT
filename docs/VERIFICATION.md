# Foundation verification

Second technical pass performed on 2026-10-02 in Linux x86-64. Final versions:
Kotlin/Compose compiler 2.4.20, Compose Multiplatform 1.12.1, Gradle 9.7.1,
Eclipse Temurin JDK 21.0.12.1+1.

## Actual checks and results

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

## Environment and commands

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

## Limits and manual checks before merge

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
