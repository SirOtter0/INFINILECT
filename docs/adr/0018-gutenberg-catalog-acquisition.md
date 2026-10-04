# ADR 0018: Experimental Gutenberg OPDS2 catalog; acquisition deferred

Status: Proposed in open PR #11, corrected before merge on 2026-10-04.
Supersedes only the unmerged PR11 RDF acquisition proposal, not historical ADRs.

## Context and evidence provenance

**Direct email:** Gutenberg supplied `https://opds-test.pglaf.org/opds/`, expected a
production preview soon, and explicitly discouraged unmaintained OPDS0.9. It did
not answer INFINILECT's individual-acquisition mechanism question.

**Official public documentation:** offline catalogs documents RDF as metadata and
OPDS2 testing; automated-access docs describe harvest/mirroring; terms describe
OPDS client identification/volume and stable informational links. None establishes
RDF → `/files` as the interactive mechanism requested in our email.

**Current live observation:** the supplied development endpoint exposes OPDS2 JSON
search, page links and item self links. Inspected item84 advertises an open-access
EPUB3 link/length, no TEXT. No linked production endpoint or trustworthy byte
revision was found. Previous HEAD's direct TEXT downloads were technically
successful, but that is not an endorsement or current OPDS contract.

[Exact categorized sources/evidence](../GUTENBERG.md).

## Decision — INFINILECT policy

Use the narrow experimental OPDS2 catalog for explicit search and metadata-only
Library, with IA as initial/default reading source. Mark Gutenberg experimental;
keep TEXT opening disabled and `loadResource` explicitly unsupported.
No obsolete XML search, RDF/file acquisition or hidden fallback remains executable.

Retain existing pure core, PublicationSource, source-scoped numeric IDs, canonical
informational URL, ResourceLoader/reader/cache/progress/collections contracts.
The shared JSON parser reuses existing serialization-json; legacy platform XML
adapters/test kXML are removed, no new dependency.

**2026-10-04 pre-merge refinement:** the 08:31 root/search200 check failed because
the parser incorrectly required `length` on ebook10414's HTML acquisition link.
Readium's Link Object defines optional integer `size`; `length` is an undefined
extension, not mandatory bytes. Use empty resources for all Gutenberg catalog
publications, replacing the previous descriptive EPUB refs. Delivery hrefs and
size/length extensions remain inert bounded metadata, never trusted sizes,
resources or authority. Required identity/self/string structure still fails
closed. This keeps catalog metadata useful if delivery URLs change, and avoids
misleading future consumers while acquisition is unsupported. Library snapshots
already exclude resources, so no schema/user-state migration is needed.
[Official standards and exact field evidence](../GUTENBERG.md).

First user Search resolves/validates the root search template, then one result
page; continuation is explicit and query-bound. All fetched metadata is bounded,
strictly parsed, cancellation-aware and host/route scoped. No redirects, inferred
production URLs, mirrors or execution of remote content.

`getPublication` re-resolves via the advertised development self-link pattern;
its availability is a preview observation, not a production stability promise.
Saved Library item opening fails safely without removing user state or recording
History. Any future acquisition must refresh current authority through the source.

## Consequences

Gutenberg search is useful but experimental; Gutenberg reading is deferred.
IA remains the verified TEXT path. No source revisions, schema, progress migration,
new format/reader, downloads or private user information are introduced.
Root adds one bounded discovery request on first explicit search per source lifetime.
Strict schema changes produce safe errors rather than silent permissive fallback.

Future Gutenberg production/TEXT evidence can replace this adapter internally.
Current code still makes architectural sense if legacy search disappears tomorrow.
[Verification and limitations](../VERIFICATION.md). No device success is claimed.
