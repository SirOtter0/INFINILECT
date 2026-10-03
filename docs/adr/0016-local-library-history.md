# ADR 0016: Persistent local library and reading history

- Status: Accepted for implementation; physical-device verification pending
- Date: 2026-10-03

## Context

The first reader, disk cache and persistent logical progress already exist. Users
need a small saved-metadata list and successful-open history across app restarts.
Those are user metadata, not source authority, cached content or reading position.
PR #7 demonstrated why same-process RAM restoration is insufficient evidence of
durability. Its fixed progress file store must remain independent.

## Decision

Introduce SQLDelight 2.4.0 (Apache-2.0) only in app for two metadata collections,
with normalized ordered author/language child tables and composite source/local
identity. Core adds bounded pure metadata models/repository result contracts.
Schema v1 stores neither resources nor bytes nor progress. Future changes require
ordered migrations, a historical baseline and replay verification rather than
destructive recreation. Initial v1 has no historical migrations; definition checks
and real fresh/reopen tests run now, with replay enabled at the first schema change.

Desktop uses a lazy JDBC driver in the existing per-user persistent data base;
Android uses the official Android driver with applicationContext/private databases
storage. Initialization, transactions and closing run on IO. Connection-local
foreign keys/durability are applied to every Desktop connection. Android corrupt
metadata is preserved instead of invoking the default deleting handler.

ApplicationCollections is a named application-owned component, separate from cache,
progress and source implementations. Small shared controllers own list/membership
state. ApplicationSession preserves Search while a temporary reader resolves a
saved ID through its current owning PublicationSource and existing opener. Stored
URLs/rights never authorize acquisition. Ready captures fresh history metadata;
a bounded actor commits it and barriers order history reads/removal/clear.
Mutations update durable UI membership only after commit. Fixed storage failures
remain visible; no optimistic RAM persistence. Clearing/removing one collection
cannot affect other user state.

Use the existing shared Compose UI with Search/Library/History buttons and a reader
Add/Remove action. Reader Back returns to its origin. No navigation library,
local bytes, source expansion, ReadingProgress migration or reader redesign.

## Consequences

SQLDelight is justified now by normalized metadata, transactional multi-row updates,
deduplication/ordering and an evolvable schema. Its runtime/Android/Desktop drivers
and native JDBC notices are documented separately; core remains dependency-free.
At most 1,000 library and 500 history entries, 64 MiB database pages, bounded fields
and finite busy timeout keep work/storage bounded. Storage failures do not block
reading; saved entries are retained if their source disappears. Committed data can
restore with entirely new repositories, independently of cache/progress.

No device/emulator was used for implementation verification. Android driver, UI
layout and termination/cache-only A–J smoke tests require human verification.
Abrupt process death may interrupt a queued history save; success means a committed
transaction, not merely capturing the event. Recovery/export/sync are deferred.
Details and storage/migration policy: [LIBRARY_HISTORY](../LIBRARY_HISTORY.md).
Historical ADRs 0014/0015 remain unchanged.
