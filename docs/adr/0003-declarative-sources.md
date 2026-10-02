# ADR 0003: Trusted engines and declarative external sources

Date: 2026-10-02 · Status: Accepted

## Context

Future external sources should be extensible without executing arbitrary code.

## Decision

Use validated, versioned data definitions interpreted by trusted built-in engines.
Do not load external scripts, bytecode or native plugins. Start with one built-in
Gutenberg/OPDS adapter; postpone a generalized definition schema and engine registry.

## Consequences

Engines own protocol parsing and network limits; source definitions cannot provide
new executable behavior. Adding a new engine requires reviewed application code.
The foundation documents this policy without implementing a plugin subsystem.
