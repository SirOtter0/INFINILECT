# INFINILECT

**Open knowledge. Infinite reading.**

[Español](README.es.md)

INFINILECT is an open-source, cross-platform reader for legally available books,
comics, magazines, articles and documents.

> A source obtains publications. A reader displays them. INFINILECT connects both.

INFINILECT does **not host publications**. Sources provide metadata and resources;
users must respect publication rights and the laws applicable in their country.
Project Gutenberg's availability in the US does not establish public-domain status everywhere.

## Status

Very early development. This foundational commit provides domain contracts, an
honest Compose desktop welcome screen, tests and architectural documentation.
Search, Gutenberg/OPDS integration, resource caching and reading are **not implemented**.
This is not a v0.0.1 release.

The deliberately small v0.0.1 goal is: open INFINILECT → search for a book → get
real results → open one → read it. Project Gutenberg/OPDS is the first planned source.
Desktop is the initial executable target; Android and iOS are future targets,
not currently supported builds.

## Build and run

Install JDK 21. The checked-in Gradle wrapper downloads Gradle on first use;
Internet access is needed for dependencies.

```sh
./gradlew :core:jvmTest :app:desktopJar
./gradlew build
./gradlew :app:run
```

On Windows, use `gradlew.bat`. Running requires a graphical desktop. Native
installers and mobile launchers are not included yet.

Versions: Kotlin/Compose compiler 2.4.20, Compose Multiplatform 1.12.1,
Gradle 9.7.0. See [toolchain evidence](docs/TOOLCHAIN.md) for official compatibility
references and [third-party notices](THIRD_PARTY_NOTICES.md) for license information.

## Small starting structure

- `core`: pure Kotlin domain models and contracts in `commonMain`; JVM target for verification.
- `app`: shared Compose UI and a desktop entry point; depends on `core`.
- `docs`: architecture, source policy, cache design, roadmap and decision records.

Ktor is the chosen future HTTP client, outside `core`. SQLDelight will be added
when persistence needs it. Readium may be used only by an Android-specific reader
implementation. None is needed or included in this foundation.

## Documentation and contribution

- [Architecture](docs/ARCHITECTURE.md)
- [Sources](docs/SOURCES.md)
- [Cache, downloads and progress](docs/CACHE.md)
- [Roadmap](docs/ROADMAP.md)
- [Architectural decisions](docs/adr/README.md)
- [Contributing](CONTRIBUTING.md)

## License

GNU General Public License version 3 only (`GPL-3.0-only`), see [LICENSE](LICENSE).
The project license does not relicense publications obtained from sources.

Copyright © 2026 SirOtter0 and INFINILECT contributors.
