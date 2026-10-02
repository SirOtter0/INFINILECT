# Sources

A source obtains publications and resources; it does not choose how they are read.
The app routes by stable SourceId. Catalog-local IDs are only unique inside that
source. Resource keys are opaque and must be resolved by their owning source.

## First source: Project Gutenberg / OPDS

For v0.0.1, implement one trusted Gutenberg/OPDS adapter outside core using Ktor.
Before implementation, verify the official catalog/search endpoint, OPDS version,
acquisition links, supported representations and service usage rules against
[Project Gutenberg](https://www.gutenberg.org/) and its
[terms of use](https://www.gutenberg.org/policy/terms_of_use.html).
Do not assume an endpoint exists or introduce a third-party aggregator silently.
Use [OPDS specifications](https://opds.io/) to interpret the verified feed version.

The first vertical slice must produce real search results and acquire a legally
available readable representation (TEXT is a reasonable first reader candidate).
Verify the source's actual offerings before choosing it. EPUB, PDF and page-image
readers remain later work. There are no fake results or network calls in this foundation.

Respect source rate limits and attribution, use HTTPS, bounded requests, safe
redirect policies and cancellable I/O. Treat metadata and publication content as
untrusted input. Preserve rights statements and source links when available;
Project Gutenberg's US public-domain status is not a global rights guarantee.
INFINILECT does not host or redistribute a publication catalog.

## Future declarative definitions

An external definition may describe a source ID, catalog URLs, approved engine
kind and engine-supported mappings/options. A versioned schema and validation
must precede importing external definitions. Trusted engines implement protocol
handling and enforce URL, redirect, size and parsing limits. Definitions must not
contain scripts, bytecode, shell commands, dynamic libraries or unrestricted
expression evaluation. Installing a definition cannot install executable code.

The first adapter can be built in. Do not build a plugin marketplace, universal
scraper or broad engine configuration language for v0.0.1. Authentication and
additional sources should be designed only when a concrete legal source needs them.
