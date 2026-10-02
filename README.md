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

Very early development. The desktop app now searches the official Project
Gutenberg OPDS catalog and shows real book results, authors and languages when
supplied. Search is submitted explicitly; the next page is fetched only when
you press **Next page**. There is no automatic search or prefetching.

Publication details, resource acquisition, caching and reading are **not implemented**.
This search slice does not complete v0.0.1. Pure domain contracts, bounded resource
access, revision-aware identity and architectural documentation remain in place.

The deliberately small v0.0.1 goal is: open INFINILECT → search for a book → get
real results → open one → read it. Project Gutenberg/OPDS is the first planned source.
Desktop is the initial executable target; Android and iOS are future targets,
not currently supported builds.

## Build and run

Install JDK 21. The checked-in Gradle wrapper downloads Gradle on first use;
Internet access is needed for dependencies.

```sh
./gradlew :core:jvmTest :app:desktopTest
./gradlew build
./gradlew :app:run
```

On Windows, use `gradlew.bat`. Running requires a graphical desktop and an
Internet connection for search. Native
installers and mobile launchers are not included yet.

Versions: Kotlin/Compose compiler 2.4.20, Compose Multiplatform 1.12.1,
Gradle 9.7.1. See [toolchain evidence](docs/TOOLCHAIN.md) for official compatibility
references and [third-party notices](THIRD_PARTY_NOTICES.md) for license information.

Gutenberg transport uses Ktor 3.6.0 outside core. The desktop parser uses JDK 21's
built-in StAX with external XML access disabled. See [source endpoints and limits](docs/SOURCES.md).
Offline tests use small authored OPDS fixtures and Ktor MockEngine. An optional
one-page live check, separate from tests/build and without a graphical UI, is:

```sh
./gradlew :app:gutenbergSearchCheck --args="shakespeare"
```

Actual verification and environment limitations are recorded in [VERIFICATION.md](docs/VERIFICATION.md).

## Small starting structure

- `core`: pure Kotlin domain models and contracts in `commonMain`; JVM target for verification.
- `app`: shared Compose search UI/state and a desktop Gutenberg adapter/entry point; depends on `core`.
  [Documented migration](docs/adr/0008-platform-entrypoints.md) separates shared UI,
  desktop and Android entry points when Android is actually added.
- `docs`: architecture, source policy, cache design, roadmap and decision records.

Ktor is the HTTP client in the desktop source, outside `core`. SQLDelight will be added
when persistence needs it. Readium may be used only by an Android-specific reader
implementation. SQLDelight and Readium are not included.

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
