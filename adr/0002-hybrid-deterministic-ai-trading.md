# ADR-0002: Hybrid deterministic core with AI advisory overlay

**Status**: accepted
**Date**: 2026-04-30
**Deciders**: owner

## Context

The system is positioned to users as "an AI agent that trades for them." Three architectural models were considered:

1. **Pure-LLM trader**: LLM reads market data + decides + sends orders.
2. **Pure deterministic**: rules engine; no AI.
3. **Hybrid**: deterministic core for the order path; AI overlay for veto, narration, conversation, review.

Trading requires:
- Sub-second hot-path latency
- Deterministic, replayable decisions
- Auditable order placement
- Compliance-defensible posture

LLMs add 1–3s latency, non-determinism, hallucination risk, $/call, and regulatory complexity if they appear to be giving investment advice.

## Decision

We adopt the **hybrid model** (option 3):

- The deterministic FSM/saga engine is the only writer of orders and FSM transitions.
- The AI provides four roles in Phase A: **Pre-Trade Sentinel** (advisory veto), **Trade Narrator** (post-trade explanation), **Conversational Assistant** (user chat), **Daily Reviewer** (EOD batch).
- The Sentinel's only write into the trading saga is the veto channel, gated by `confidence ≥ 0.85`. Default on uncertainty/timeout = ALLOW.
- Stop-loss and trailing-stop paths are pure deterministic. No AI in them.

## Consequences

- **Positive**: hot-path latency preserved; replayability preserved; AI hallucination cannot place orders; compliance posture is "strategy executor + explainer" rather than "investment advisor."
- **Negative**: we forgo the agility of having an LLM adapt strategy in real time. Strategy parameters are user-configured rules.

## Alternatives Considered

- **Pure LLM**: rejected — latency, determinism, hallucination, regulatory.
- **Pure deterministic**: rejected — no user-facing intelligence layer; "AI agent" framing not honored.
- **AI proposes, deterministic validates**: rejected as premature; revisit in Phase B post-soak if needed.

## References

- `requirements.md` v1.0
- `architecture-spec.md` v2.1 §4 (AI Agent Layer)
