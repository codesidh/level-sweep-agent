# ADR-0007: Pre-Trade Sentinel veto protocol

**Status**: accepted
**Date**: 2026-05-04
**Deciders**: owner
**Supersedes**: detail-fills ADR-0002 §"Pre-Trade Sentinel (advisory veto)" and `architecture-spec.md` §4.4. Lands the contract that Phase 5 implements; Phase 4's Narrator/Reviewer ADR-0006 is unaffected.

## Context

Phase 5 introduces the **Pre-Trade Sentinel** — Claude Haiku 4.5 — as the AI veto channel into the deterministic trade saga. ADR-0002 established the architectural posture (hybrid deterministic core with AI advisory overlay; Sentinel is the only AI write into the saga; default on uncertainty/timeout = ALLOW). What ADR-0002 left open was the **wire-level contract** between the saga and the Sentinel:

- Where in the saga does the Sentinel sit?
- What input does it receive?
- What output does it return?
- How does the saga interpret confidence + outcome?
- What is the failure semantics (Anthropic outage, timeout, cost cap, ambiguous response)?
- How does this stay determinism-preserving for replay parity?

Phase 4 already shipped the substrate (AnthropicClient + cost cap + audit log; ADR-0006). Sentinel reuses that substrate; this ADR specifies the saga-level integration and runtime contract.

Constraints from `CLAUDE.md` and the spec:

- **Guardrail #2**: AI cannot place orders. The veto channel can only ABORT the saga; it cannot modify the proposed trade.
- **Guardrail #3**: Fail-closed on the order path. But for Sentinel specifically, ADR-0002 inverts this: fail-OPEN on Sentinel (default ALLOW) because Sentinel uncertainty must not silently halt entries — the deterministic Risk FSM HALT path is the "fail-closed" mechanism, not Sentinel.
- **Guardrail #5**: Determinism / replay-parity. A given (signal_id, indicator_snapshot, recent_trades_window) must yield an identical Sentinel decision in the replay harness.
- **Guardrail #11 cost-cap-as-code**: the existing pre-flight check applies; on cost cap breach, Sentinel returns ALLOW.

## Decision

The Sentinel sits between **Saga step 3 (RiskGate accepted)** and **Saga step 4 (StrikeSelector resolves OCC symbol)** as a **synchronous, blocking, sub-second** gate.

### 1. Wire contract

**Input** (`SentinelDecisionRequest`):
- `tenant_id`
- `trade_id` (saga correlation key)
- `signal_id` (from Decision Engine signal evaluator)
- `direction` (LONG_CALL | LONG_PUT)
- `level_swept` (PDH | PDL | PMH | PML)
- `indicator_snapshot` — EMA(13/48/200), ATR(14), RSI(2), regime, last 12 2-min bars (close + volume) from `IndicatorSnapshotHolder`
- `recent_trades_window` — last 5 trades for the tenant (outcome + R-multiple + ts)
- `vix_close_prev` — yesterday's VIX close (regime context)
- `now_utc` — trading-clock anchor (replay-stable)

The request is built deterministically from the saga's correlated state. The **prompt is constructed via a templated builder** that produces byte-identical output for identical input — replay parity is preserved by hashing the prompt bytes, not the LLM response (the response goes through a parser, see §3 below).

**Output** (`SentinelDecisionResponse`):
- `decision` ∈ {`ALLOW`, `VETO`}
- `confidence` ∈ [0.0, 1.0] (decimal, two places)
- `reason_code` ∈ {`STRUCTURE_MATCH`, `STRUCTURE_DIVERGENCE`, `REGIME_MISALIGNED`, `RECENT_LOSSES`, `LOW_LIQUIDITY_PROFILE`, `OTHER`}
- `reason_text` (≤ 280 chars, audit-only — never re-fed to a downstream prompt)

`SentinelDecisionResponse` is a sealed record set; same shape pattern as ADR-0006 §1's `AnthropicResponse`.

### 2. Saga interpretation

```
if outcome == VETO and confidence >= 0.85:
    saga.compensate(reason = "sentinel_veto")
    increment counter ai.sentinel.veto_applied{tenant_id, level_swept}
    write audit row with full request + response
else:
    saga.proceed_to_strike_selector()
    increment counter ai.sentinel.allow{tenant_id, level_swept, decision_path}
        where decision_path ∈ {explicit_allow, low_confidence_veto_overridden, fallback_allow}
```

`confidence ≥ 0.85` is the published threshold from ADR-0002 §"Sentinel Decision". Lower confidence vetoes are logged but not honored — they appear in the audit log as `decision_path = low_confidence_veto_overridden`.

### 3. Failure semantics — fail-OPEN on Sentinel itself

| Failure mode | Outcome | Counter |
|---|---|---|
| Anthropic 5xx after retry budget exhausted | ALLOW (`decision_path = fallback_allow`) | `ai.sentinel.fallback{reason="transport"}` |
| Anthropic 429 rate-limited | ALLOW (`decision_path = fallback_allow`) | `ai.sentinel.fallback{reason="rate_limit"}` |
| Cost cap pre-flight breach | ALLOW (`decision_path = fallback_allow`) | `ai.sentinel.fallback{reason="cost_cap"}` |
| Response parse failure (malformed JSON, missing fields) | ALLOW (`decision_path = fallback_allow`) | `ai.sentinel.fallback{reason="parse"}` |
| Wall-clock timeout > 750ms | ALLOW (`decision_path = fallback_allow`) | `ai.sentinel.fallback{reason="timeout"}` |
| Connection FSM `UNHEALTHY` (Phase 5 wires this) | ALLOW pre-emptively without an HTTP call | `ai.sentinel.fallback{reason="cb_open"}` |

Fail-OPEN is intentional: Sentinel is **advisory** (ADR-0002). Halting entries is the deterministic Risk FSM's job. A Sentinel outage must not silently stop the system from trading.

### 4. Latency budget

The Sentinel call sits on the saga's hot path. The budget is:

| Step | Budget |
|---|---|
| Build request from correlated state | 5ms |
| Cost cap pre-flight | 1ms |
| HTTP call (Haiku 4.5, P50 reported ~500ms) | 750ms hard timeout (fail-OPEN past this) |
| Response parse + validate | 5ms |
| Audit row Mongo insert | 25ms (async; doesn't block the saga decision) |
| **End-to-end on saga thread** | **≤ 760ms** |

Saga step 4 (StrikeSelector) cannot start until Sentinel completes; the user-visible signal-to-order latency therefore includes this ~760ms floor when Sentinel ALLOWs. Architecture-spec §3 budgets 1.5s end-to-end signal-to-order — this leaves ~700ms for steps 4-7.

### 5. Determinism + replay parity

Replay harness behavior:

- The replay seed records the exact `(tenant_id, signal_id, indicator_snapshot, recent_trades_window, vix_close_prev, now_utc)` tuple plus the full Sentinel response.
- The replay runner injects a **fixture-backed `Fetcher`** (same test-seam as ADR-0006 §1) that returns the recorded response for the exact tuple hash. No HTTP call is made.
- Replay parity test passes IFF the saga decision (`compensate` vs `proceed_to_strike_selector`) is identical to the recorded one for every recorded tuple.
- A change to the prompt template, model id, or threshold breaks replay parity by design — those are intentional signal changes that warrant a new replay corpus capture.

### 6. Cost + cap

- Per-(tenant, role=`SENTINEL`, day) cost cap: $0.50 (architecture-spec §4.8). Haiku 4.5 is the cheapest tier; this absorbs ~5,000 calls/day at typical input/output token sizes — well above expected usage of ≤ 5 trades/day per tenant.
- Cap breach → ALLOW + `ai.sentinel.fallback{reason="cost_cap"}` increment + cost-cap-breached log line. Reset at 00:00 ET via the existing `DailyCostTracker` in ai-agent-service.

### 7. Phase A vs Phase B

- **Phase A (single-tenant OWNER)**: feature flag `levelsweep.sentinel.enabled` defaults `true` once Phase 4 soak (5+ RTH sessions on real Anthropic) clears. Until then the flag is `false` and the saga skips the Sentinel call (counter `ai.sentinel.skipped{reason="flag_off"}`).
- **Phase B (multi-tenant)**: per-tenant overrides land in `tenant_config` (Phase 6 user-config-service). Default tenant value mirrors the Phase A flag. The OAuth-vended Anthropic key per tenant (CLAUDE.md memory `project_anthropic_api_key`) carries through.

## Consequences

### Positive

- Wire contract is fixed before code lands → fewer Phase 5 review cycles.
- Fail-OPEN posture is explicit and auditable; no implicit halt-on-AI-outage surprises.
- Replay parity is preserved via the prompt-hash fixture seam (no LLM call in the harness).
- Latency budget is in writing → Phase 5 PR with Sentinel that exceeds it visibly fails the spec.

### Negative

- A 750ms Sentinel budget on the hot path is non-trivial; if Haiku 4.5 P99 grows, the timeout fallbacks become observable. Phase 7 may consider warming a connection pool or moving Sentinel off-thread (with the deterministic saga able to pause on a Future) — out of scope for this ADR.
- Fail-OPEN means a sustained Anthropic outage results in 100% pass-through → user pays Anthropic for vetoes that don't happen. Mitigation: alert #11 `anthropic_cb_unhealthy` (currently DISABLED in alerts.tf — flip enabled = true alongside the Phase 5 deploy).

### Neutral

- Sentinel is one of four AI roles. The ADR does not constrain Narrator (post-trade), Assistant (chat), or Reviewer (EOD batch) — those have their own latency / determinism profiles documented in ADR-0006.

## Implementation references

Status changed from `proposed` to `accepted` after Phase 5 implementation landed on `staging/phase-5`:

- PR #114 — S2 SentinelDecisionRequest/Response + SentinelPromptBuilder + SentinelResponseParser + SENTINEL cost cap default
- PR #116 — S1 Anthropic Connection FSM + AnthropicClient short-circuit on UNHEALTHY (emits `circuit_breaker_open`)
- PR #117 — S6 Conversational Assistant (out-of-scope for Sentinel proper but lands the broader Phase 5)
- PR #118 — S3 SentinelService orchestrator (cost-cap pre-flight + 750ms timeout + counter taxonomy + audit)
- PR #120 — S4 Replay parity (11 fixtures, ≥99% target, fixture-backed Fetcher seam)
- PR #121 — S5 Saga integration: SentinelResource (REST) in ai-agent-service + SentinelClient + SentinelGate + TradeSaga wiring in decision-engine + helm `LEVELSWEEP_SENTINEL_ENABLED` env

`staging/phase-5` rebases onto `main` once Phase 3 + Phase 4 soaks clear (operator gate per CLAUDE.md guardrail #8).

## References

- ADR-0002 §"Pre-Trade Sentinel (advisory veto)"
- ADR-0006 (AI Agent Service Anthropic integration)
- `architecture-spec.md` §4.4 (Sentinel role), §4.8 (cost cap), §4.10 (audit log)
- `requirements.md` §17 (AI veto threshold)
- `CLAUDE.md` guardrails #2, #3, #5, #11
