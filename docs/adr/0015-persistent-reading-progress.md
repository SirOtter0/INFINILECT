# ADR 0015: Persistent logical reading progress

- Status: accepted for the first TEXT progress slice
- Date: 2026-10-03
- Refines [ADR 0004](0004-resource-lifetimes.md), [0012](0012-bounded-text-reading.md),
  [0013](0013-first-android-application.md); preserves [0014](0014-persistent-resource-cache.md)

## Context

The shared Desktop/Android reader consumes real bounded UTF-8 text, but all reading
position disappears on Back/restart. A cached resource is disposable infrastructure;
user state must survive cache removal and must not bypass source acquisition rules.
Future formats require logical locations rather than Compose pixel offsets.

## Decision

Add pure ReadingProgressId/ReadingLocator/ReadingProgress and ReadingProgressStore
to core. Identity is PublicationId + resource key + format, excluding title/URL/MIME/
revision. Implement only the typed TEXT code-point offset + observed length locator;
normalized progression supports approximate restoration when document length changes.
Add future typed EPUB/PDF/PAGES locators with their readers, not their engines.

Keep the four modules. Shared app maps UTF-16 layout positions to code points with
sparse checkpoints, throttles updates in a per-reader generation, and translates
saved locations to current layout lines. Pixels never enter persistence. Load saved
progress only after normal acquisition/full decoding; current Archive null revisions
still cannot reuse persistent cache bytes. No core source/cache contracts are changed.

Use separate app-private persistent files, not cache storage: Android filesDir,
Desktop per-user data directories. Versioned bounded checksummed records use digest
names and atomic replacement. Quotas refuse new writes without user-state eviction;
corrupt/unavailable storage is safe absence/failure. Standard platform APIs suffice.

ApplicationSources owns a finite background writer; reader/session close captures
pending state before cancellation/disposal. Fixed 2s windows save during continuous
scrolling; Back/onStop flush; late callbacks are invalidated. Desktop close waits
up to 3s; Android close is nonblocking. Abrupt death can lose the latest window.
No Activity is retained. Store operations are serialized on IO with short OS locks.

## Consequences

The first reader resumes approximately at the same region after valid reacquisition,
including restart. Same-length content edits cannot be matched precisely without a
future stronger locator; resource key changes start fresh. Whole percentages express
that approximation. No completion/history/library/bookmarks/settings/sync or content
persistence is introduced. No dependencies, permission or source-policy changes.
No atomic-rename support means save failure with the previous committed state intact.
Real Android/layout/process-death behavior still requires physical smoke testing.
Exact format, bounds, paths and lifecycle are in [PROGRESS.md](../PROGRESS.md).
