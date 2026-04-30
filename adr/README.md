# Architecture Decision Records

We record significant architectural decisions as ADRs.

## Format

Each ADR is a numbered markdown file: `adr/NNNN-short-title.md`.

## Lifecycle

- **proposed** → initial draft, under discussion
- **accepted** → ratified; implementation may follow
- **deprecated** → superseded by a later ADR; kept for history
- **superseded by ADR-NNNN** → another ADR replaces this

## When to write an ADR

- Choice between viable alternatives where the rejected options had meaningful tradeoffs
- A constraint that future engineers might want to re-litigate without context
- A trade-off that exposed real risk (latency, cost, compliance, scope)
- A reversal of a prior decision

Trivial choices (variable names, file layout) do not need ADRs.

## Template

See [`0000-template.md`](0000-template.md).
