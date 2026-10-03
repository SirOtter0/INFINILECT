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

Initial 2026-10-02 decision; the delivery/lifecycle follow-up below amends its
fixed-node restriction without changing its public-CC0 scope.

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

## Delivery/lifecycle follow-up — 2026-10-03

The fixed observed-host constant cannot support items served by other legitimate
nodes. Official item documentation says download permalinks may redirect, and
the MDAPI record documents root `server`, `workable_servers` and `dir`. The test
item's official response also includes `alternate_locations.workable` host/dir
pairs; this is observed API evidence, not an exhaustive documented schema.
See [current policy and official references](../INTERNET_ARCHIVE.md).

Always start with the official permalink. Follow only same-item/file redirects
whose exact storage host and directory occur in fresh MDAPI location fields,
additionally constrained to structurally parsed ASCII `archive.org` DNS labels,
HTTPS/default or 443 port and canonical matching paths. No wildcard permission,
editable item-metadata URLs, unannounced nodes or direct-storage fallback.
Keep the local two-hop/loop bound and fail closed on unsupported locations.
The single follow-up live check used a different announced primary node.

Refresh permissions/locations after acquiring the source mutex, immediately
before opening. Include the official `nodownload`/`is_collection` flags and fail
closed on ambiguous restriction values. Close is atomic/idempotent; a 60s
post-handoff handle lifetime prevents abandoned consumers from stranding the
serialized source. Reads permit only one known-size overflow probe byte. The demo
must reject incomplete UTF-8 at actual EOF. No core/dependency change; revisions
remain null, CC0-only and no reader/cache/lending/login scope changes.
