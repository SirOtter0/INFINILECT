# ADR 0017: Bounded indexed disk-backed TEXT documents

Status: Accepted for PR #10
Date: 2026-10-04

## Context

ADR 0012 intentionally used a 512 KiB full String to prove reading. Real Android
use now encounters legitimate TEXT beyond that ceiling. Raising a materialization
constant would couple publication size to retained heap and Compose layout cost.
ADR 0015's persisted logical code-point locators must remain compatible.

## Decision

Keep core/source/cache/progress contracts unchanged. App preparation streams and
strictly decodes a known-size TEXT through ResourceLoader into private temporary
UTF-8 storage while building primitive byte/code-point window indexes. One leading
BOM is omitted logically, not from the acquisition byte count. Require exact EOF.

Support at most 16 MiB TEXT; retain the source's separate 64 MiB acquisition bound.
Read in at-most-8-KiB requests; display windows end at newline after 1,024 points
or at 2,048 points. Cap the index at 16,385 entries and the decoded LRU at eight
windows. Shared Compose uses a lazy list with suspend local window access.
Document APIs carry no filesystem, transport or source policy into the UI/core.

ApplicationSources owns the preparer. Android uses cacheDir; Desktop uses safe
per-user cache conventions, never cwd. UUID paths and an owner lock permit safe
best-effort stale cleanup without deleting other active owners/unrelated files.
The platform base is trusted/canonicalized, including Android's OS path aliases;
the owned namespace cannot be a symlink. File IO runs off the UI thread.

An opener owns a prepared document until Ready handoff and closes it on all other
terminal paths. Back/application disposal close the document and release local
data. This infrastructure is independent of ResourceCache and Downloads; it never
becomes persistently reusable authority. Null-revision IA still fresh-acquires.

Preserve ReadingProgressId and ReadingLocator.Text exactly. Restore by global
code point, find the indexed window, then its current layout line. Local UTF-16
indices never enter persistence. Library/History schema and acquisition rules do
not change. No new dependency or format.

## Consequences

Working memory is bounded buffers + a bounded sparse index + visible/cached
windows, rather than a whole book String. Preparation still scans/acquires the
complete document before reading and costs up to 16 MiB disk per open. Long
paragraphs may wrap at window boundaries. Exact pixels are not restored. Cache
removal requires a new preparation but cannot remove progress/library/history.
Progressive display, reader settings, Downloads and new formats remain deferred.

This supersedes only ADR 0012's full-materialization/512 KiB representation;
historical ADR text remains intact. [Detailed policy](../TEXT_READER.md).
