# Phase 5 Runbook — Pre-Trade Sentinel + Conversational Assistant

**Scope**: Phase 5 lands the Pre-Trade Sentinel (`claude-haiku-4-5` — synchronous AI veto on the entry saga) and the Conversational Assistant (`claude-sonnet-4-6` — operator chat). Both ship in `staging/phase-5` until Phase 3 + Phase 4 soaks clear (operator gate per CLAUDE.md guardrail #8). Phase 5 also lands four alerts: #11 (Anthropic CB UNHEALTHY) flipped enabled, plus #19/#20/#21 (Sentinel veto rate, Sentinel fallback rate, Assistant failure rate).

This runbook covers operator playbook for the Phase 5 paper-trading soak: how to enable, how to monitor, how to roll back, how to capture replay fixtures, and known-issue / accepted-risk list.

## Quick reference

| Item | Value |
|---|---|
| Sentinel model | `claude-haiku-4-5` (cheapest tier) |
| Sentinel role | `SENTINEL` |
| Sentinel cost cap | `$0.50/day` per tenant per ADR-0007 §6 |
| Sentinel saga slot | between RiskGate accepted and StrikeSelector resolves OCC symbol |
| Sentinel hard timeout | 750ms wall-clock (ADR-0007 §4) |
| Sentinel confidence threshold | 0.85 — vetoes < 0.85 demoted to ALLOW with `decision_path=low_confidence_veto_overridden` |
| Sentinel fail-OPEN posture | every failure mode (transport / rate_limit / cost_cap / parse / timeout / cb_open) → ALLOW with `decision_path=fallback_allow` |
| Assistant model | `claude-sonnet-4-6` |
| Assistant role | `ASSISTANT` |
| Assistant cost cap | `$5/day` per tenant |
| Feature flag | `levelsweep.sentinel.enabled` (default `false` until soak clears; consulted on BOTH ai-agent-service AND decision-engine — defense in depth) |
| Mongo audit | `audit_log.ai_calls` (existing); per-call row with `role: "SENTINEL"` |
| Connection FSM | `connection_state{dependency=anthropic}` — alert #11 enabled |
| Replay corpus | 11 fixtures in `services/ai-agent-service/src/test/resources/sentinel/replay/` |
| Counter taxonomy | ADR-0007 §3 + §6 |

## Common operations

### Enable the Sentinel

The flag is consulted on BOTH services. Flip both:

```bash
# decision-engine: set the env on the Helm chart, redeploy
helm upgrade --install decision-engine deploy/helm/decision-engine \
  --namespace decision-engine \
  --set env.LEVELSWEEP_SENTINEL_ENABLED=true \
  ...

# ai-agent-service: same env, separate chart
helm upgrade --install ai-agent-service deploy/helm/ai-agent-service \
  --namespace ai-agent \
  --set env.LEVELSWEEP_SENTINEL_ENABLED=true \
  ...
```

Verify via `kubectl logs -f` — first call after flip emits an INFO line from `SentinelService.evaluate` and `SentinelGate` on each service respectively.

### Disable the Sentinel (rollback)

Flip the flag OFF on EITHER service. Defense in depth: decision-engine skips the call entirely on its end; ai-agent-service returns Allow on every call. Either alone is sufficient.

```bash
helm upgrade --install decision-engine ... \
  --set env.LEVELSWEEP_SENTINEL_ENABLED=false
```

### Inspect today's Sentinel decisions

App Insights KQL:

```kql
customMetrics
| where cloud_RoleName == "ai-agent-service"
| where name startswith "ai_sentinel"
| where timestamp > ago(1d)
| summarize count() by name, tostring(customDimensions.decision_path), tostring(customDimensions.reason)
```

For per-trade detail:

```kql
traces
| where cloud_RoleName == "ai-agent-service"
| where message startswith "sentinel"
| project timestamp, message, customDimensions
| order by timestamp desc
```

### Inspect a specific trade's Sentinel verdict

```javascript
// mongosh against the cluster's Mongo
db.audit_log.ai_calls.findOne({
  tenant_id: "OWNER",
  role: "SENTINEL",
  trade_id: "trade-XYZ"
})
```

The audit row contains the prompt hash, the full response variant, the decision path, the elapsed latency, and the cost. Use this to capture a regression fixture (next section).

### Capture a new replay fixture

If the Sentinel made a decision the operator disagrees with, capture it as a regression fixture so future strategy/prompt changes don't drift:

1. Read the audit row in Mongo: `db.audit_log.ai_calls.findOne({tenant_id, trade_id})`.
2. Copy the prompt + response into a new JSON file in `services/ai-agent-service/src/test/resources/sentinel/replay/<descriptive_name>.json` per the schema in that folder's `README.md`.
3. Run `:services:ai-agent-service:test` — the new fixture now participates in `SentinelReplayParityTest`.
4. Commit + push as a small PR to `staging/phase-5` (or main once soak clears).

### Use the Conversational Assistant

```bash
# via the BFF (bypass-auth in dev):
curl -X POST http://api-gateway-bff.api-gateway-bff:8090/api/v1/assistant/chat \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "OWNER", "userMessage": "what was today R-multiple?"}'
# → {"conversationId": "<uuid>", "turn": {"role": "assistant", "content": "..."}}
```

Continue a conversation by echoing the returned `conversationId`:

```bash
curl -X POST http://api-gateway-bff.api-gateway-bff:8090/api/v1/assistant/chat \
  -d '{"tenantId": "OWNER", "conversationId": "<uuid>", "userMessage": "and the win rate?"}'
```

### List recent conversations

```bash
curl http://api-gateway-bff.api-gateway-bff:8090/api/v1/assistant/conversations?tenantId=OWNER&limit=20
```

## Alerts (cumulative, Phase 1+3+4+5+6)

Phase 5 alerts:

| # | Severity | Source | What it means | Operator action |
|---|---|---|---|---|
| 11 | P2 | `customMetrics`, `connection_state{dependency=anthropic} >= 2` for 3+ consecutive 1-min samples | Anthropic Connection FSM is UNHEALTHY | Existing positions continue. Sentinel auto-fallbacks to ALLOW. Investigate Anthropic status page. Risk FSM HALT (deterministic) is the actual fail-closed entry path |
| 19 | P3 | `ai.sentinel.veto_applied / (allow + veto_applied)` > 30% over 1h with ≥10 attempts | Sentinel vetoing too much — strategy drift or unusual regime | Capture replay fixtures of vetoed signals. Compare against historical regime distribution |
| 20 | P2 | `ai.sentinel.fallback / total` > 20% over 1h with ≥10 events | AI veto layer silently degraded — every fallback is an ALLOW without model opinion | Investigate Anthropic outage (#11), cost cap (#10), Sentinel system prompt regression (parse failures) |
| 21 | P3 | `ai.assistant.failed / (failed + fired)` > 50% over 1h with ≥4 events | Operator chat UX degraded | Investigate cost cap (#10), Mongo connectivity, prompt regression. Trading is unaffected |

All four reuse the Phase 1 action group. Phase 7 splits high-severity escalation onto a Twilio SMS-backed group.

## Known issues / accepted risk

**1. Sentinel adds ~760ms to the saga thread on the entry path.** Architecture-spec §3 budgets 1.5s end-to-end signal-to-order; ~700ms remains for steps 4-7. Phase 7 may move Sentinel off-thread once observed P99 latency confirms there's room.

**2. Anthropic Haiku 4.5 cost cap is $0.50/day/tenant.** At ~$0.001/call this absorbs ~5,000 calls/day, well above expected ≤ 5 trades/day per tenant. Cap breach → fail-OPEN with `fallback_allow` decision_path.

**3. Replay parity captured 11 fixtures.** ≥99% target met but corpus is small. Phase 7 expands to 30+ fixtures from real production samples. Operator: capture a fixture every time the Sentinel makes a decision worth regression-testing.

**4. The flag is consulted on BOTH services (defense in depth).** If you flip it on ai-agent-service alone, decision-engine still skips the call. Both must agree to be effective. This is by design — single-service flip is for emergency rollback.

**5. BFF auth bypass is still ON in dev (Phase A).** The Assistant chat endpoint is exposed as-is. Phase 10 wires Auth0 OIDC. Do NOT promote this chart to a public-internet `prod` environment without flipping the bypass off.

**6. Confidence < 0.85 vetoes are silently demoted to ALLOW** (`decision_path=low_confidence_veto_overridden`). The audit row records what the model said; the saga proceeds. This is by design (ADR-0007 §2) — Sentinel is advisory, the deterministic Risk FSM is authoritative. If you're seeing many low-confidence vetoes, that's a strategy/prompt signal, not a bug.

**7. Sentinel REST is in the saga's hot path.** A network blip between decision-engine and ai-agent-service triggers the SentinelClient's 750ms timeout → fail-OPEN → ALLOW. Network reliability inside the cluster is assumed; Phase 7 adds Istio mTLS + retries if observed flakiness justifies.

**8. iac.yml apply still blocked on state backend** (Phase 7 fix). Manual `az` CLI is the workaround for cluster + KV operations. Until then the Phase 5 alerts (#11/#19/#20/#21 in `infra/modules/observability/alerts.tf`) live in the Terraform code but won't deploy to Azure Monitor until the state migration completes.

## Recovery cookbook

**Sentinel pod stuck in CrashLoopBackOff** — most likely the Anthropic API key is missing or invalid. Check Key Vault:

```bash
az keyvault secret show --vault-name kv-levelsweep-dev-egjqdl --name anthropic-api-key
```

**Sentinel latency exceeds 760ms budget consistently** — check the connection FSM (alert #11) and the cost-cap warn (alert #10). Look at `customMetrics` `ai_sentinel_fallback{reason="timeout"}` to see how often the timeout fires. If chronic, raise the timeout in `application.yml` for ai-agent-service AND `decision-engine` (both must agree); document why.

**Sentinel says VETO and operator disagrees** — capture a replay fixture (see "Common operations" above). The audit row is in Mongo; copy + paste into a JSON file under `services/ai-agent-service/src/test/resources/sentinel/replay/`. Future strategy/prompt changes will be regression-checked against your fixture.

**Cost cap breached** — alert #10 fires. The Sentinel auto-fallbacks to ALLOW for the rest of the day (cap resets at 00:00 ET). If unexpected, investigate whether something is calling the Sentinel in a loop. Check `ai.cost.daily_total_usd{role=sentinel}` gauge.

**Conversational Assistant returns "I can't respond right now"** — transient failure on the orchestrator's path. Look at the audit row to see which variant fired (cost cap / Anthropic 429 / parse failure). Synthetic error turns are NOT persisted to Mongo, so the conversation thread stays clean.

**Connection FSM stuck UNHEALTHY** — Anthropic outage. Existing positions continue under deterministic exit rules. Sentinel ALLOWs every entry. Wait for Anthropic recovery; the FSM auto-probes every 15s after going UNHEALTHY.

## Soak gate

Phase 5 is **scope-failing** per CLAUDE.md guardrail #8 (modifies execution-service Helm chart + decision-engine saga, both in active Phase 3 soak). Therefore lands on `staging/phase-5` long-lived branch with full CI but no main merge.

Operator gate: **Phase 3 + Phase 4 soaks must clear** before `staging/phase-5` rebases onto main and merges. Then Phase 5 starts its own ≥5 RTH paper soak with `LEVELSWEEP_SENTINEL_ENABLED=true` against real Anthropic.

P0/P1 incidents during Phase 5 soak reset the counter. Phase 8 (paper→live) cannot start until all three (Phase 3, 4, 5) soaks clear.

## References

- ADR-0007 (Pre-Trade Sentinel veto protocol)
- ADR-0006 (AI Agent Service Anthropic integration)
- ADR-0002 (Hybrid deterministic + AI advisory)
- `architecture-spec.md` §4.4 / §4.5 / §4.8 / §4.10
- `CLAUDE.md` guardrails #2, #3, #5, #8, #11
- Phase 4 runbook (`docs/runbooks/phase-4.md`) — substrate (AnthropicClient, cost cap, audit log)
- `services/ai-agent-service/src/test/resources/sentinel/replay/README.md` — replay fixture capture playbook
