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

An independent desktop Internet Archive adapter demonstrates real acquisition of
a public CC0 text through PublicationSource/ResourceContent, with an opt-in CLI
check and bounded UTF-8 prefix. This is a conservative subset, not a second source
in the current UI. No cache, persistent downloads or reader is implemented;
**v0.0.1 is not complete**. [Acquisition scope](docs/INTERNET_ARCHIVE.md).

OAPEN's official alternate metadata interface is accessible and supplies download
links; REST rejects this environment with HTTP 403 and PDF transfer remains
blocked/unverified. See [OAPEN](docs/OAPEN.md) and the
[comparison](docs/ACQUISITION_COMPARISON.md). Gutenberg acquisition remains deferred
pending official guidance. No OAPEN source/UI or PDF reader is claimed.

The deliberately small v0.0.1 goal is: open INFINILECT → search for a book → get
real results → open one → read it. Project Gutenberg/OPDS is the first functional search source.
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

A separate, one-request OAPEN access diagnostic (no publication mapping or download)
is available as `./gradlew :app:oapenApiAccessCheck --args=water`. It is opt-in and
fails on denied access; HTTP 200 alone would not verify acquisition.

Two additional opt-in checks never run during tests/build:

```sh
./gradlew :app:oapenAlternateAccessCheck
./gradlew :app:internetArchiveAcquisitionCheck
```

The first requests one OAI-PMH record and HEAD only; the second searches a verified
CC0 government document and consumes at most 512 bytes through ResourceLoader.
No publication text is logged/saved. See source docs for limits and the narrow
host/access policy. JSON parsing uses desktop kotlinx.serialization-json 1.11.0
(Apache-2.0); core remains Kotlin-only.

Actual verification and environment limitations are recorded in [VERIFICATION.md](docs/VERIFICATION.md).

## Small starting structure

- `core`: pure Kotlin domain models and contracts in `commonMain`; JVM target for verification.
- `app`: shared Compose search UI/state, neutral acquisition demo, desktop adapters and entry point; depends on `core`.
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

Original INFINILECT code is licensed under `GPL-3.0-or-later`, see [LICENSE](LICENSE).

INFINILECT is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.

The project license does not relicense publications obtained from sources.

Copyright © 2026 SirOtter0 and INFINILECT contributors.
