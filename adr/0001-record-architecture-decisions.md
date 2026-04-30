# ADR-0001: We will record architecture decisions

**Status**: accepted
**Date**: 2026-04-30
**Deciders**: owner

## Context

The LevelSweepAgent system has multiple architectural decisions with downstream blast radius (deterministic core vs AI overlay; CP vs AP per domain; Quarkus vs Spring; AKS vs ACA; etc.). Without a written record, future engineers will re-litigate these without context, or worse, silently reverse them.

## Decision

We adopt Architecture Decision Records (ADRs) per Michael Nygard's format. Significant architectural decisions are recorded as numbered markdown files in `adr/`.

## Consequences

- **Positive**: clear lineage of decisions; cheap to write; easy to discover via the file tree.
- **Negative**: requires discipline to actually write them; risk of ADRs becoming stale (mitigated by `superseded by` linking).

## Alternatives Considered

- **Wiki / Confluence**: too easy to lose; not version-controlled with code; access is not universal.
- **Inline code comments**: don't capture rejected alternatives; rot when files move.
- **No record**: the default failure mode we're avoiding.

## References

- Nygard, M. "Documenting Architecture Decisions" (2011)
