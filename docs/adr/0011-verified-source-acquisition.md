# ADR 0011: First verified source-specific acquisition

- Status: accepted for this narrow experimental slice
- Date: 2026-10-02
- Partially supersedes [ADR 0010](0010-oapen-verification-gate.md), retained as
  historical REST evidence, not a universal OAPEN prohibition

## Evidence

OAPEN supplies official CC0 exports with download links and OAI-PMH. GetRecord
succeeded and supplied a PDF URL; PDF HEAD blocked, Ktor alternate probe timed
out once. REST403 does not imply all metadata mechanisms are inaccessible.

Internet Archive documents search, item/files metadata and download permalinks.
A public Dutch government text with CC0/no restrictions/small TXT passed real
source acquisition; a restricted/private CC0 candidate was rejected.
See [comparison](../ACQUISITION_COMPARISON.md).

## Decision

Add only InternetArchiveSource in desktopMain, behind PublicationSource. No core
change/module/universal engine. Conservative public CC0 texts, TEXT/PDF references,
opaque pagination, fresh permission checks. Preserve rights/license separately
internally and the existing verbatim optional rights field; revisions null.

Use official file permalink, validated IDs/basenames, at most two redirects and
the exact observed storage host. Unverified access/rights/hosts fail closed.
Bound JSON/HTTP. Add only desktop kotlinx.serialization-json 1.11.0 (already
resolved serialization-core version, Apache-2.0) to avoid a handwritten parser;
no serialization compiler plugin.

Bridge public Ktor scoped streaming to ResourceContent using an owned producer
until close/cancellation. Lifecycle stays in the adapter, no HTTP/JVM stream in
core. A neutral DirectResourceLoader/format selector drives the CLI prefix demo,
owns/closes its handle; later readers open fresh handles. Keep UI/Gutenberg unchanged.

## Consequences

Proves multiple source implementations and real bounded acquisition without
reader/cache/persistent downloads; not v0.0.1 completion. Much of Archive is
deliberately excluded; review before broadening host/rights rules or offering
general acquisition UI. TEXT prefix integrated; PDF acquisition/validation and
full-file hash verification deferred. OAPEN remains viable with transfer/discovery
questions, without requiring restoration of REST if another official route suffices.
