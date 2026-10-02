# Contributing

INFINILECT is at the foundation stage. Keep changes small and tied to the
[v0.0.1 roadmap](docs/ROADMAP.md). Discuss substantial architecture changes in an
issue and record accepted decisions in an ADR before expanding the module graph.

Use JDK 21 and the checked-in wrapper. Run `./gradlew build` before submitting a
pull request, report the commands and results, and state any checks you could not
perform. For the desktop UI, also run `./gradlew :app:run` in a graphical session.
Use Kotlin's official style. Add meaningful tests for domain invariants and new
behavior. Keep README.md and README.es.md aligned when changing user-facing status
or setup instructions. English is the shared language for code and architecture;
Spanish contributions and discussions are welcome.

Keep core free of UI, HTTP clients, platform APIs and reader engines. Review a new
dependency's version, license and transitive dependencies before adding it; update
THIRD_PARTY_NOTICES.md and retain upstream notices for redistribution. Do not add
libraries for features we have not implemented. Never commit credentials, fetched
publications, caches or copyrighted fixtures without permission.

Contributions are provided under GPL-3.0-only, the project's license. Add SPDX
headers to original Kotlin source. Preserve existing attribution. No copyright
assignment is required.

Future source definitions are data interpreted by trusted engines. Do not submit
arbitrary executable plugins, scripts, DRM bypasses or source integrations that
violate the source's terms. Test parsing and routing with small legal fixtures;
keep network integration tests explicit and separate from offline unit tests.
