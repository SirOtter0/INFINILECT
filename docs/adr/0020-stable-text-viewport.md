# ADR 0020: Stable TEXT viewport geometry and bounded window coordination

Status: Proposed — Draft PR #14
Date: 2026-10-04

## Context

Physical Android testing found unusually fast backward scrolling in large TEXT.
ADR 0017's indexed document and stable full lazy list were sound, but each lazy
item initially rendered one loading line while its suspend window read completed.
Reverse measurement used those collapsed heights to traverse previously visited
multi-line windows. The issue was geometry, not transient list indexing or fling
velocity. Placeholder-only layouts could also produce a false bottom/EOF report.

## Decision

Preserve the disk-backed document/index, 16 MiB ceiling, 2,048-point windows and
existing eight-window FileWindows LRU. Use global start code points as item keys,
scoped to a document generation. Retain visited measured slot heights in one
primitive array for the current width/font/density, including across text eviction.
A cold visited slot reserves its measured height; unseen pending slots reserve a
viewport estimate. No pixels enter persistence and no velocity adjustment is made.

One UI-owned worker handles composed windows and two neighbors on either side,
with a conflated wakeup, eight-window decoded LRU and request generations. Ready
composed windows survive LRU eviction until disposal. Changing a range or closing
rejects late results/failures, including non-cooperative completion. No per-window
IO coroutine or whole-document prefetch/index rebuild. FileWindows uses a
cancellable read Mutex and a short non-IO cache/close guard; post-read checks prevent
cancelled/closed publication and close releases cached references immediately.

Initial progress restoration waits for a real window layout and scrolls once;
user scrolling then becomes enabled. Later viewport reports use actual layouts
only; EOF requires the real final window at the bottom. No viewport/progress/
programmatic-scroll feedback loop. The persistent identity/locator/schema,
source security, revision=null behavior and user-store ownership remain unchanged.

## Consequences

Per-document geometry is <=64 KiB; two eight-window LRUs have a conservative
combined <=128 KiB character payload, often shared, plus composed viewport/layout
windows. Queued work is one conflated signal, and only one read is in flight in
this coordinator. Per-change processing depends on composed slots/four neighbors,
not document length. IO stays off UI; close is idempotent and nonblocking.

Visited-region geometry is preserved exactly within one layout configuration.
Unseen pending geometry remains approximate, so extreme flings can still show
loading. Visual grapheme/paragraph continuity across artificial window boundaries
is unchanged. Host tests simulate measurement and reader ownership; physical
Android gesture confirmation and graphical Desktop smoke remain required.
The niri/Wayland window-size observation and EPUB rendering remain out of scope.

This refines ADR 0017's lazy presentation without rewriting its history.
[Detailed evidence/invariants](../TEXT_READER.md), [verification](../VERIFICATION.md).
