# Foundation verification

Performed on 2026-10-02 in Linux x86-64.

## Results

- Official toolchain versions and compatibility checked before creating build files;
  see [TOOLCHAIN.md](TOOLCHAIN.md).
- Gradle 9.7.0 binary distribution and generated wrapper JAR verified against
  official SHA-256 checksums. The distribution checksum is pinned in the wrapper.
- `build` completed successfully through `./gradlew` (Gradle 9.7.0, Eclipse Temurin
  JDK 21.0.12.1+1). Both core and desktop Kotlin code compiled; `core:jvmJar` and
  `app:desktopJar` were generated. Final build reported `BUILD SUCCESSFUL` in 28s.
- `core:jvmTest`: 4 tests, 0 failures, 0 errors, 0 skipped. Covers source-aware
  identity (including ambiguous separator cases), type/format independence,
  resource ownership/uniqueness and blank metadata rejection.
- App has no UI tests yet (`desktopTest` was `NO-SOURCE`); no UI test pass is claimed.
- Runtime, JVM test and build-plugin dependency graphs were inspected, with Maven
  license declarations recorded in [DEPENDENCIES.md](DEPENDENCIES.md). JUnit and
  Hamcrest are absent from the application runtime graph. Core imports only Kotlin
  code and has no Compose, Ktor, Readium or platform API dependency.
- Local Markdown links and staged patch whitespace checked before committing
  (verbatim upstream license copies retain their original whitespace).

## Environment adjustments and command

The environment initially had only a JRE. A full Temurin JDK 21 was downloaded
from its official GitHub release and its SHA-256 verified, installed only in `/tmp`.
The downloaded JDK used the environment's existing Java certificate store to
retain TLS validation through the session proxy.

Maven Central returned HTTP 429 (IP rate limit). A temporary Gradle init script,
**not part of the project**, replaced repositories with JetBrains' official cache
redirector for the same Maven Central/plugin portal/Google Maven coordinates.
The repository's normal Maven Central, Google and plugin portal configuration was
retained. The successful invocation was:

```sh
JAVA_HOME="$INFINILECT_JDK" \
JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080' \
GRADLE_USER_HOME=/tmp/infinilect-gradle \
./gradlew -I /tmp/infinilect-repositories.init.gradle build foundationDependencyInventory \
  --no-daemon --console=plain --max-workers=2 --warning-mode=all
```

`foundationDependencyInventory` was a temporary inspection task, not a committed
application task. It read resolved artifact coordinates for `desktopRuntimeClasspath`,
`jvmTestRuntimeClasspath` and the root build-plugin `classpath`. No proxy addresses,
local JDK paths or repository overrides are required by the project itself.

Normal contributor verification remains `./gradlew build` with an installed JDK 21.
An unmodified online repository-resolution run could not complete in this session
because of the Maven Central rate limit; the successful build used the mirror above.

## Limits and next checks

No graphical desktop or Xvfb was available, so `:app:run` and visual/interactive
behavior were not verified. Native installer packaging, complete native-component
redistribution notices, Windows/macOS execution, Android and iOS builds are not
claimed. The JAR is not a standalone executable bundle.

Gutenberg/OPDS search, reader behavior, caching, downloads and persisted progress
are future implementations, so no end-to-end v0.0.1 test exists yet. Verify these
in the order described in [ROADMAP.md](ROADMAP.md).
