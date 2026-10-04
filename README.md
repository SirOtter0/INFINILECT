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

Very early development. Desktop and the first Android debug app share the same UI.
Internet Archive (public CC0 subset) is the initial source for real TEXT reading.
**Project Gutenberg (experimental)** searches the OPDS2 development catalog supplied
by Gutenberg, with authors/languages and metadata-only Library saves. Gutenberg's
unmaintained OPDS0.9 feed and the unverified RDF acquisition path are removed;
**Gutenberg reading is disabled**. [Evidence and external gates](docs/GUTENBERG.md).
Search and Next page require explicit actions; no automatic searches or prefetch.

Select **Internet Archive**, press **Open text**, and read real strictly valid UTF-8
with known size≤**16MiB**. Back retains source/query/results. Text is prepared in
private temporary storage and displayed through bounded lazy windows;
[reader policy](docs/TEXT_READER.md).

This is a conservative first reading path, with local approximate TEXT progress across restarts, without reader
settings, EPUB/PDF reader or persistent downloads. Automatic bounded disk caching
only reuses resources with trustworthy revisions; current Archive resources have
no revision and reopening still acquires again. See [cache policy](docs/CACHE.md).
Library saves publication metadata locally; History records successful opens.
Both survive restarts and cache deletion. Saved entries reopen through their source,
with normal acquisition and reading-progress restoration. History Clear requires
confirmation and keeps Library/progress. [Local storage policy](docs/LIBRARY_HISTORY.md).
**v0.0.1 is not complete**. [Acquisition scope](docs/INTERNET_ARCHIVE.md).

OAPEN's official alternate metadata interface is accessible and supplies download
links; REST rejects this environment with HTTP 403 and PDF transfer remains
blocked/unverified. See [OAPEN](docs/OAPEN.md) and the
[comparison](docs/ACQUISITION_COMPARISON.md). Gutenberg remains experimental catalog-only; no bulk crawling. No OAPEN source/UI or PDF reader is claimed.

The deliberately small v0.0.1 goal is: open INFINILECT → search for a book → get
real results → open one → read it. Project Gutenberg/OPDS is the first functional search source.
Desktop and Android are executable targets. Android requires API 26+; iOS remains
future work. APK compilation is verified. The reviewer reports PRs #8–#10 Android persistence
and large-TEXT smoke success; PR #11 corrected experimental-catalog device verification is pending.

## Build and run

Install JDK 21 and the Android SDK (platform 37, build-tools 36.0.0). Set
`ANDROID_HOME` or an untracked `local.properties` SDK path. The checked-in Gradle wrapper downloads Gradle on first use;
Internet access is needed for dependencies.

```sh
./gradlew :core:jvmTest :app:desktopTest
./gradlew build
./gradlew :desktopApp:run
```

On Windows, use `gradlew.bat`. Running requires a graphical desktop and an
Internet connection for search. Native
installers are not included. Build/install the standard debug APK with:

```sh
./gradlew :app:testAndroidHostTest :core:testAndroidHostTest :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Android uses the same source selection, search and TextReader. System Back from
Loading/Reader/Error returns to retained results; Back at Search follows Android.
Activity recreation/process death starts a new session: query/results/document/
pixel scroll state are not saved. Logical reading progress is saved separately
and restores after a new explicit, valid acquisition. There is no tracking or extra device-data access.
The only requested platform capability is INTERNET; AndroidX also declares an
internal app-scoped signature permission for non-exported receiver protection.
The icon is original provisional geometry, not the final logo.

For Gutenberg catalog examples, search `Frankenstein` or `shakespeare`; you can
save metadata to Library, but EPUB/TEXT opening is unavailable for this source.
For an Archive reading example, select Internet Archive and search
`identifier:gmb-2015-93040`, then press **Open text**. This is a public CC0 Dutch
government document. Results are not automatically enriched or acquired. Changing
source cancels the old session and starts with an empty query/results; returning
from the reader preserves the current session, with the logical reading position saved locally.

Versions: Kotlin/Compose compiler 2.4.20, Compose Multiplatform 1.12.1,
Gradle 9.7.1, AGP 9.3.1, compileSdk 37 / targetSdk 37 / minSdk 26. See [toolchain evidence](docs/TOOLCHAIN.md) for official compatibility
references and [third-party notices](THIRD_PARTY_NOTICES.md) for license information.

Gutenberg transport uses Ktor 3.6.0 and existing serialization-json outside core.
Desktop/Android share one hardened JSON parser. [Endpoints/limits](docs/SOURCES.md).
Offline tests use small authored OPDS2 JSON fixtures and Ktor MockEngine. An opt-in
development-service check fetches root, one page and one detail (no acquisition):

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
host/access policy. JSON parsing uses shared JVM/Android kotlinx.serialization-json 1.11.0
(Apache-2.0); core remains Kotlin-only.

The new full-document check uses the same search/open/session logic as the UI,
verifies strict UTF-8 and Back, and logs only counts, never publication text:

```sh
./gradlew :app:internetArchiveTextReadingCheck
```

It is opt-in, never runs in tests/build, and reads one small verified document
under the 16 MiB reader limit. This CLI check is not a graphical UI smoke test.

Actual verification and environment limitations are recorded in [VERIFICATION.md](docs/VERIFICATION.md).

## Small starting structure

- `core`: pure Kotlin models/contracts in `commonMain`, JVM and Android library targets.
- `app`: shared Compose UI, session/controllers/reader; `jvmSharedMain` shares source
  policies/mapping. Desktop uses Java HTTP; Android uses Android HTTP; Gutenberg JSON policy is shared.
- `desktopApp`: Desktop launcher, OS runtime and packaging, depending on `app`.
- `androidApp`: Activity, Back/insets, manifest and APK, depending on `app`.
  [Android decision](docs/adr/0013-first-android-application.md) refines ADR 0008.
- `docs`: architecture, source policy, cache design, roadmap and decision records.

Ktor is the HTTP client in platform source adapters, outside `core`. SQLDelight
2.4.0 stores local library/history metadata in app; progress remains its independent
file store. Readium is not included and may only be used by an Android-specific reader.

## Documentation and contribution

- [Architecture](docs/ARCHITECTURE.md)
- [Sources](docs/SOURCES.md)
- [Cache and downloads](docs/CACHE.md)
- [Persistent reading progress](docs/PROGRESS.md)
- [Local library and reading history](docs/LIBRARY_HISTORY.md)
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
