# ADR 0006: Sequential resource handles and bounded small-resource reads

Date: 2026-10-02 · Status: Accepted · Refines ADRs 0002 and 0004

## Context

Both source and loader returned ByteArray, forcing whole-resource allocation at
their fundamental boundary. That is convenient for TXT but unsuitable as a default
for PDFs, EPUBs, comic archives or large scans. No readers or adapters exist yet,
so there is no published consumer compatibility to preserve.

## Decision

Return ResourceContent from both existing methods. It is a Kotlin-only interface
with nullable total size, suspend reads into a caller-provided byte buffer and
non-suspending, idempotent close. Reads can be partial; -1 means EOF; positive-length
requests cannot return 0. Bounds, single-consumer ownership and closed-handle
behavior are explicit contracts. Each open returns an independent handle.

The caller owns closing in finally, including cancellation and partial reads.
Opening failure must release acquired I/O; adapters must propagate read cancellation.
Close must release/cancel without suspending or waiting for an ongoing transfer.
A partial stream must not publish a complete cache entry. Size must never become
an unchecked allocation request.

Provide one small-resource convenience, readBytes(maxBytes), with a mandatory
limit, early rejection of oversized known resources, actual-byte checks for unknown
or understated sizes, and at most one overflow-probe byte. It always closes and
preserves a read/cancellation error if close also fails. Chunk accumulation handles
short reads without a separate allocation per read.

## Alternatives and consequences

Keeping ByteArray would require a later breaking replacement or a second parallel
API. Change the return types now; there are no reader implementations to migrate.
TEXT can still opt into bounded ByteArray materialization. Larger consumers read
incrementally without changing source/loader signatures. No Ktor, JVM InputStream,
flow/channel framework, added dependency, seek abstraction or engine implementation
is introduced. Platform adapters may spool to a file for future random-access reader
engines; optional seek/range contracts require a concrete need later.

Tests cover bounded consumption, partial reads, unknown sizes, EOF, limit violations,
cleanup and propagation of failure/cancellation signals. Real transport suspension,
cancellation and prompt close require future adapter integration tests.
