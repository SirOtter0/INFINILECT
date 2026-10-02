# ADR 0005: Narrow first release and deferred integrations

Date: 2026-10-02 · Status: Accepted

## Context

A universal reader can expand indefinitely before demonstrating useful reading.

## Decision

v0.0.1 is one path: open, search a book, receive real Gutenberg/OPDS results, open
and read one verified representation. Ktor is the chosen transport when that
adapter is implemented. Add SQLDelight only when needed; defer Readium until an
Android reader requires it. Distribute original project code under GPL-3.0-or-later.

## Consequences

The foundational commit is a scaffold, not v0.0.1. Translation, synchronization,
dozens of sources and a full plugin system are excluded. Review licenses before
adding dependencies and preserve upstream notices in distributions.
