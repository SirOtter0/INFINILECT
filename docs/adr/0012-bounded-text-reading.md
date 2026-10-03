# ADR 0012: Minimal bounded text reading slice

- Status: accepted for the first Desktop reading slice
- Date: 2026-10-03
- Builds on [ADR 0011](0011-verified-source-acquisition.md) and
  [ADR 0006](0006-bounded-resource-access.md); does not broaden source permissions

## Context

Internet Archive already provides verified acquisition behind pure core contracts.
Gutenberg remains search-only pending acquisition guidance. A reader consumer is
needed to demonstrate a real user path without coupling display to either source,
adding a cache or introducing a navigation framework.

## Decision

Keep core and all source policies unchanged. Add shared Compose-free bounded
loading and OpenPublicationController in app/commonMain. Fetch details using
PublicationSource, verify identity, and reuse the neutral resource selector for
TEXT only. Acquire through ResourceLoader, close one single-consumer ResourceContent
in finally, and pass only TextDocument(ID/title/text) to Compose TextReader.

Set **MAX_TEXT_DOCUMENT_BYTES = 512 * 1024**: sufficient for short books/documents,
while bounding full decoding and the initial plain-text layout. This reader cap
is independent of the adapter's 64 MiB resource cap. Require known positive stable
size, allocate one payload buffer of size+1, verify actual exact EOF/length, and
probe at most one extra byte. Unknown sizes are deliberately unsupported here.
No unbounded utility call or growing chunk-list/concatenated payload copies.

Decode explicitly and strictly as UTF-8 on Dispatchers.Default after closing I/O.
Remove exactly one leading BOM, preserve interior BOMs; reject invalid sequences,
empty/whitespace/NUL-bearing documents. Other encodings/formats are deferred.
The final bounded String and decoder working storage are necessary; no text is
logged by live checks. Bound the entire open operation to 60s.

Reuse SearchController in a small ReadingSession for one source. Desktop injects
two fixed choices, Gutenberg search-only and Archive public-CC0 TEXT reading.
No registry, cross-source resolution, simultaneous search, persistence or automatic
request. A source switch cancels/discards the old session and resets UI collectors.
Back from Loading/Reader/Error preserves current source/query/results and clears
opening state. No reading position is saved. A cancelled operation generation
cannot publish a stale Ready; duplicate busy actions are ignored. Session actions
run on the UI thread; session scope and desktop disposal own cancellation/closure.

Open state is sufficient navigation: Idle shows Search, Loading/Error show the
opening page, Ready shows TextReader. TextReader knows only the document and Back
callback; title/plain text/vertical scroll, no source/HTTP/live handle knowledge.
Errors are safe fixed messages, and retries are explicit.

## Consequences

Demonstrates real full-text reading through the same production UI/controller
path with an opt-in small-document live check; normal tests remain offline.
No new dependencies/modules. Core, Gutenberg acquisition and Archive CC0/host/
revision policies remain intact. Memory is bounded but this first plain-text
layout needs graphical smoke testing before broader documents are claimed.
Reopening fetches again; a later cache belongs behind ResourceLoader, with progress
independent. No EPUB/PDF reader, settings, downloads, history, mobile, lending/login
or release-completion claim.
