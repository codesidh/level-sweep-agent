# Phase 4 Runbook — AI Agent Service (Narrator + Daily Reviewer)

**Scope**: `ai-agent-service` running in the Azure `dev` AKS cluster, alongside the Phase 1 `market-data-service` and the Phase 3 `execution-service`. Phase 4 ships the AI Agent Service skeleton (hand-rolled Anthropic HTTP client + per-tenant per-role daily cost cap + AI audit log per ADR-0006), the Trade Narrator (Sonnet 4.6 — observes `TradeFilled` fills via the `tenant.fills` Kafka topic and writes 1-3 sentence narratives to Mongo `trade_narratives`), and the Daily Reviewer (Opus 4.7 — Quarkus `@Scheduled` cron at 16:30 America/New_York; reads session journal + signal history + prior 5 days of reports and writes a structured EOD report to Mongo `daily_reports`). Phase 4 also lands four new alerts (10-13 in `infra/modules/observability/alerts.tf`).

This runbook covers the alerts the operator will need during the Phase 4 paper-trading + AI soak (5+ RTH sessions on live Anthropic), the known-issue / accepted-risk list, and how to recover from common failure modes.

## Quick reference

| Item | Value / command |
|---|---|
| Service URL (in-cluster) | `http://ai-agent-service.ai-agent:8084` |
| Liveness probe | `GET /q/health/live` |
| Readiness probe | `GET /q/health/ready` |
| Prometheus metrics | `GET /q/metrics` |
| Anthropic Messages API | `https://api.anthropic.com/v1/messages` |
| Anthropic models (per role) | `claude-sonnet-4-6` (narrator), `claude-opus-4-7` (reviewer); Sentinel/Assistant land at Phase 5 |
| Per-(tenant, role, day) cost cap | $1.00 narrator, $2.00 reviewer (architecture-spec §4.8); fail-closed pre-flight check before the HTTP call |
| Logs (App Insights) | `traces \| where cloud_RoleName == "ai-agent-service"` |
| Custom metrics (App Insights) | `customMetrics \| where cloud_RoleName == "ai-agent-service"` |
| Container image registry | ACR — `${ACR_LOGIN_SERVER}/ai-agent-service:<git-sha>` |
| KV-backed secrets | `anthropic-api-key`, `applicationinsights-connection-string` (mongo URL is plain-text in `values.yaml` for Phase 4 — see known issue #2) |
| Mongo collections (audit) | `audit_log.ai_calls`, `audit_log.ai_prompts`, `audit_log.daily_cost` |
| Mongo collections (output) | `trade_narratives`, `daily_reports` |
| Mongo collections (input, Phase 5+ producers) | `signal_history` (empty until Sentinel wires it) |
| Kafka topics consumed | `tenant.fills` (TradeFilled in — produced by execution-service Phase 3 saga step 5/6) |
| Kafka topics produced | (none — Phase 4's only outputs are Mongo writes) |

## Common operations

### Restart the pod

```bash
kubectl -n ai-agent rollout restart deployment/ai-agent-service
```

Phase 4 is single-replica. The in-memory `DailyCostTracker` is rebuilt on first call by replaying the day's `daily_cost` Mongo rows for the `(tenant, role)` pair, so a restart inside the trading day does NOT reset the cap. The Quarkus Quartz cron rearms automatically — but a restart will NOT cause the 16:30 ET reviewer to re-fire if the original 16:30 already passed (cron is daily, not catch-up). See known issue #1.

### Tail live logs

```bash
kubectl -n ai-agent logs -f deployment/ai-agent-service
```

JSON-encoded; MDC keys: `tenant_id`, `service`, `trace_id`, `ai_role`, `signal_id`. Filter the narrator path with `grep "TradeEventNarratorListener\|TradeNarrator"`, the reviewer path with `grep "DailyReviewer\|DailyReviewerScheduler"`, the Anthropic transport with `grep "AnthropicClient"`, the cost cap with `grep "DailyCostTracker\|CostCapBreach"`.

### Inspect daily cost in Mongo

```bash
# Open a mongosh against the cluster's Mongo (Phase 4 dev: in-cluster mongo)
kubectl -n ai-agent exec -it deployment/ai-agent-service -- /bin/sh
mongosh "${MONGO_URL}/${MONGO_DB}" --eval '
  db.daily_cost.find({tenant_id: "OWNER", date: "2026-05-02"}).sort({timestamp: -1}).limit(20)'
```

`daily_cost` rows are append-only — one row per Anthropic call with `cost_usd`, `role`, `tenant_id`, `date`, and `timestamp`. The cost-tracker sums on the fly; there is no rollup row. To see totals:

```javascript
db.daily_cost.aggregate([
  {$match: {tenant_id: "OWNER", date: "2026-05-02"}},
  {$group: {_id: "$role", total_usd: {$sum: "$cost_usd"}, calls: {$sum: 1}}}
])
```

### Read recent narratives

```javascript
db.trade_narratives.find({tenant_id: "OWNER", session_date: "2026-05-02"})
  .sort({generated_at: -1}).limit(20)
```

Each row carries the `trade_id`, `narrative` (1-3 sentences from Sonnet 4.6), the source fill snapshot, and the `generated_at` timestamp. Indexed by `(tenant_id, trade_id, generated_at desc)`.

### Read latest daily report

```javascript
db.daily_reports.find({tenant_id: "OWNER"}).sort({session_date: -1}).limit(1)
```

The Daily Reviewer writes one row per cron fire. On Anthropic outage / cost-cap skip, the scheduler still writes a stub report with `status: "SKIPPED_COST_CAP"` (or similar) so dashboards can distinguish "reviewer didn't run" from "reviewer ran and emitted no findings". Indexed by `(tenant_id, session_date desc)`.

### Inspect AI call audit

```javascript
// Recent narrator calls
db.ai_calls.find({tenant_id: "OWNER", role: "narrator"}).sort({occurred_at: -1}).limit(20)

// Recent reviewer calls
db.ai_calls.find({tenant_id: "OWNER", role: "reviewer"}).sort({occurred_at: -1}).limit(5)
```

Each `ai_calls` row carries the prompt SHA-256 (`prompt_hash`), input/output token counts, computed `cost_usd`, latency, model name, and outcome. The full prompt + response blob lives in `ai_prompts` keyed by hash — join on `prompt_hash` to dereference. This split is the §4.10 + ADR-0006 audit shape: per-call summary row + cold prompt blob.

### Read App Insights cost gauge

```kql
customMetrics
| where cloud_RoleName == "ai-agent-service"
| where name == "ai_cost_daily_total_usd"
| top 50 by timestamp desc
| project timestamp, tenant=tostring(customDimensions.tenant_id), role=tostring(customDimensions.role), value
```

Same gauge that alert 10 reads. Per-(tenant, role) max over the 1-hour window. Phase A is owner-tenant only — expect a single tenant_id in the dimension.

### Read narrator skip ratio over 1h

Same KQL as alert 12, simplified for ad-hoc inspection:

```kql
let skipped = customMetrics
  | where name == "ai_narrator_skipped" and cloud_RoleName == "ai-agent-service"
  | summarize skipped_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
let fired = customMetrics
  | where name == "ai_narrator_fired" and cloud_RoleName == "ai-agent-service"
  | summarize fired_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
skipped
| join kind=fullouter (fired) on tenant_id
| extend skipped_count = coalesce(skipped_count, 0L), fired_count = coalesce(fired_count, 0L)
| extend total = skipped_count + fired_count, skip_ratio = todouble(skipped_count) / todouble(total + 1)
| project tenant_id, skipped_count, fired_count, skip_ratio
```

Drill into the skip reason via the `customDimensions.reason` dimension on `ai_narrator_skipped` (`cost_cap`, `anthropic_failure`, `empty_response`).

### Trigger DailyReviewer manually (off-schedule)

**There is no admin endpoint in Phase 4.** The Quarkus `@Scheduled` cron `"0 30 16 * * ?"` (timezone `America/New_York`) is the only trigger. If the operator misses a 16:30 ET fire (pod crash, KV mount failure, etc.) the only recourse is:

1. Wait for tomorrow's 16:30 ET fire and accept the gap (the missed day's `daily_reports` row stays absent).
2. Accept that a pod restart will NOT cause a catch-up fire — Quartz fires forward only.

Phase 5 may add `/q/admin/reviewer/run?tenantId=…&sessionDate=…` as part of the Sentinel admin surface; until then, document the gap in the daily soak log.

### Rotate Anthropic credentials

```bash
az keyvault secret set \
  --vault-name "${KEY_VAULT_NAME}" \
  --name anthropic-api-key \
  --value "<new-key>"
kubectl -n ai-agent rollout restart deployment/ai-agent-service
```

Workload identity + the KV CSI driver re-read on pod start. There is no live-rotation in Phase 4. Phase B per-tenant key rotation requires a different flow (per-tenant KV secret name, scoped reload) — not in Phase 4 scope.

### Read App Insights Connection FSM state for Anthropic

**NOT WIRED in Phase 4** — placeholder KQL for when Phase 5 (Sentinel) adds the FSM:

```kql
customMetrics
| where name == "connection_state"
| where customDimensions.dependency == "anthropic"
| where cloud_RoleName == "ai-agent-service"
| top 100 by timestamp desc
| project timestamp, value
```

Today this query returns zero rows. Sentinel needs CB visibility for fail-open ALLOW behavior, which is the right place to add the FSM. Alert 11 ships disabled — see alert 11 triage below and known issue #3.

### Verify replay-parity / determinism for Narrator + Reviewer

Replay parity for AI calls is enforced by `temperature=0` + `tool_choice` forcing on the Anthropic request shape, and by deterministic prompt builders. Per-component tests assert the prompt strings are byte-stable across runs:

```bash
./gradlew :services:ai-agent-service:test --tests *NarrationPromptBuilderTest*
./gradlew :services:ai-agent-service:test --tests *ReviewerPromptBuilderTest*
```

A formal end-to-end AI replay harness comparable to execution-service's S7 is deferred to Phase 5 alongside Sentinel — see known issue #8.

## Common diagnoses

### Symptom: "AI cost cap approached (>= 90%)" alert fires (alert 10)

Per-(tenant, role) gauge `ai_cost_daily_total_usd` crossed $0.90 within a 1-hour window. The cap is a hard pre-flight check that runs BEFORE the HTTP call; once the cap is breached the role degrades to no-op (Sentinel: ALLOW; Narrator: skip + log; Reviewer: skip; Assistant: error) per architecture-spec §4.9. No money is wasted on the breached call itself.

Triage:

1. **Identify the breaching tenant + role:**
   ```javascript
   db.daily_cost.aggregate([
     {$match: {date: "2026-05-02"}},
     {$group: {_id: {tenant: "$tenant_id", role: "$role"},
               total_usd: {$sum: "$cost_usd"}, calls: {$sum: 1}}},
     {$sort: {total_usd: -1}}
   ])
   ```
2. **Common modes:**
   - **Prompt regression that explodes input tokens.** Look for an outlier `tokens_in` row in `ai_calls` for the role. Recent prompt-builder change is the #1 suspect.
   - **Runaway loop in narrator listener** — the `tenant.fills` consumer fires repeatedly for the same trade. Compare distinct `trade_id` count vs. row count in `db.ai_calls.find({role: "narrator"})`.
   - **Mongo down causing cost-tracker to lose accumulated state** and re-fetch — but Phase 4 cost-tracker is write-through (every call writes to `daily_cost` BEFORE the HTTP call), so the in-memory state is rebuilt from Mongo on restart. If Mongo writes are failing, look for `WARN` lines from `DailyCostMongoRepository` and triage Mongo connectivity first.
3. **Mitigation:**
   - Confirm the cap resets at 00:00 ET (the date dimension rolls over; today's accumulated cost no longer counts tomorrow).
   - If the day's breach is benign (rare burst of fills), accept it — the role degrades cleanly.
   - For a hot-fix on a known prompt regression, raise the cap in `application.yml` (`anthropic.cost-cap-usd-per-tenant-per-day.<role>`) and `kubectl rollout restart` the deployment. Revert the cap once the prompt is fixed.

### Symptom: "Anthropic API CB UNHEALTHY" alert fires (alert 11)

**This alert ships DISABLED in Phase 4.** Connection FSM for Anthropic is not wired until Phase 5 (Sentinel needs CB visibility for fail-open ALLOW). Operator can ignore alert 11 noise during Phase 4 soak.

When Phase 5 lands and the alert is enabled, triage as:

1. **Anthropic status?** https://status.anthropic.com — wait if there's an open incident.
2. **Key rotation server-side?** Probe from the pod:
   ```bash
   kubectl -n ai-agent exec deployment/ai-agent-service -- sh -c '
     wget -qO- --header "x-api-key: ${ANTHROPIC_API_KEY}" \
              --header "anthropic-version: 2023-06-01" \
              --header "content-type: application/json" \
              --post-data "{\"model\":\"claude-haiku-4-5\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}" \
              https://api.anthropic.com/v1/messages'
   ```
   If `401`, rotate per "Rotate Anthropic credentials" above.
3. **Egress IP allowlist?** Anthropic does NOT operate an IP allowlist on inbound calls — public API. Skip this step (it's the inverse of the Alpaca posture).
4. **429 / 529 rate limiting?** Anthropic burst limits are tightest at 09:30 ET when Sentinel kicks in (Phase 5+). For Phase 4 (Narrator + Reviewer only), this is rare — only the EOD reviewer at 16:30 ET and the per-fill narrator are firing. Look for `WARN` lines from `AnthropicClient` with status `429` or `529`. Per architecture-spec §4.9: Sentinel falls back to ALLOW on 429, Narrator/Reviewer queue with exponential backoff.

### Symptom: "Narrator skip rate > 50% (1h)" alert fires (alert 12)

Skip ratio = `ai_narrator_skipped` / (`ai_narrator_skipped` + `ai_narrator_fired`) over a 1-hour window with at least 4 total events. Phase A is owner-tenant — burst is rare; sustained skip is a real bug.

Triage by skip reason dimension:

```kql
customMetrics
| where name == "ai_narrator_skipped" and cloud_RoleName == "ai-agent-service"
| summarize count() by reason = tostring(customDimensions.reason), bin(timestamp, 5m)
| order by timestamp desc
```

Reason values:

- **`cost_cap`** — see alert 10 triage. Until the cap resets at 00:00 ET, every fill skips.
- **`anthropic_failure`** — Anthropic 429 / 529 / transport error. Check `AnthropicClient` logs. If sustained, file a P2 and let the role degrade quietly through end of day.
- **`empty_response`** — the model returned a blank message body. Almost always a prompt regression (the model interpreted the prompt as "say nothing"). Compare the latest deploy's prompt builder against the previous build via `git log services/ai-agent-service/.../narrator/NarrationPromptBuilder.java`.

### Symptom: "Daily Reviewer run missing (>26h)" alert fires (alert 13)

`ai_reviewer_run_complete` counter has not incremented in the last 26 hours for the tenant. The 16:30 ET cron fires unconditionally (the scheduler emits the metric whether the review succeeded, was skipped via cost cap, or hit an Anthropic failure), so a missing increment means **the cron itself didn't fire**.

Triage:

1. **Pod missing or crashed?**
   ```bash
   kubectl -n ai-agent get pods
   kubectl -n ai-agent describe deployment/ai-agent-service
   kubectl -n ai-agent logs deployment/ai-agent-service --since=24h | grep -i "schedul\|reviewer"
   ```
2. **JVM scheduler disabled?** Check the `quarkus.scheduler.enabled` config. The `%test` profile sets it to `false` to keep `@QuarkusTest` deterministic — verify the prod profile is active (`QUARKUS_PROFILE=prod` env in `values.yaml`).
3. **DST transition consuming the 16:30 ET hour?** Quarkus / Quartz uses the JVM tzdata which handles DST transparently, but DST transitions can in rare cases shift the fire window by an hour. Re-check the scheduler log line `next fire = …` against expected.
4. **Operator action**: per the "Trigger DailyReviewer manually" entry above, there is no catch-up fire in Phase 4. Accept the gap, document in the soak log, and verify tomorrow's 16:30 ET fires cleanly.

### Symptom: Readiness probe fails (503)

1. Hit the endpoint directly:
   ```bash
   kubectl -n ai-agent exec deployment/ai-agent-service -- \
     wget -qO- http://localhost:8084/q/health/ready
   ```
2. Check for KV CSI mount failures:
   ```bash
   kubectl -n ai-agent logs deployment/ai-agent-service --tail 200 | grep -i "secret\|csi\|workload"
   ```
   Common modes: `anthropic-api-key` typo in KV, workload-identity client ID mismatch on the SA, KV network ACL blocking the CSI request, federated identity binding missing for `system:serviceaccount:ai-agent:ai-agent-service`.
3. The Anthropic readiness check ALSO fails closed if `ANTHROPIC_API_KEY` is the empty string (Phase 4's `%test` profile sets it blank intentionally; production must have a real value mirrored from the GitHub `dev` env — see known issue #2).

### Symptom: Mongo writes failing (WARN-level only — no alert fires in Phase 4)

The narrative + daily-report + cost-row + audit writes all catch their own exceptions and log a `WARN`. The HTTP call still completes; only the persistence layer drops. **This means the cost cap can fail silently** if `daily_cost` writes are dropping — the in-memory tracker keeps incrementing, but on restart the rebuild from Mongo will under-count, potentially permitting more spend than configured. Triage:

1. Tail logs for `DailyCostMongoRepository\|TradeNarrativeRepository\|DailyReportRepository\|AiCallAuditWriter`.
2. Confirm `MONGO_URL` is reachable from the pod:
   ```bash
   kubectl -n ai-agent exec deployment/ai-agent-service -- /bin/sh -c \
     'wget -qO- --tries=1 --timeout=2 ${MONGO_URL%/*}/ || echo "mongo unreachable"'
   ```
3. Phase 7 wires managed Mongo (Cosmos / Atlas) via KV-mounted `mongo-url`. Until then, plain-text URL in `values.yaml` points at the in-cluster mongo.

## Escalation

| Severity | Action |
|---|---|
| P0 / P1 | Page codesidh — see issue #38 for the on-call rotation. Phase 4 has no P0 paths today (no order placement) but a missing Daily Reviewer for 3+ consecutive days during soak escalates to P1 (audit gap). |
| P2 | File a GitHub issue with labels `phase-4` + `type:bug` + `p2:normal`. AI cost cap breach + Daily Reviewer missing land here. |
| P3 | Add to weekly triage; usually a known issue. Narrator skip rate elevated lands here unless the underlying root cause is P2. |

## Phase 4 known issues / accepted risks

1. **Single-instance deployment.** No HA. Pod restart loses the in-memory `DailyCostTracker` cache; the rebuild from `daily_cost` Mongo rows happens on the first call after restart. If Mongo is down at restart time, the tracker rebuilds empty — the cap is permissive until Mongo recovers and the next call seeds the rebuild. Phase 7 multi-replica + leader election fixes this.
2. **`ANTHROPIC_API_KEY` must be MIRRORED from GitHub `dev` env into Azure Key Vault.** The chart mounts `anthropic-api-key` from KV via the CSI driver — the GitHub Actions workflow does NOT auto-sync. Operator must run `az keyvault secret set --vault-name "${KEY_VAULT_NAME}" --name anthropic-api-key --value "<key>"` once before the chart deploys cleanly, and again on every key rotation. See `values.yaml` keyVault.objects[anthropic-api-key].
3. **Connection FSM for Anthropic is NOT wired in Phase 4.** Alert 11 (`ai-cost-cap-warn`'s sibling) ships `enabled = false`. Phase 5 (Sentinel) is the right place to add it — Sentinel needs CB visibility for fail-open ALLOW, and adding an FSM only for Narrator/Reviewer would be redundant.
4. **Daily Reviewer's `signal_history` collection has no producer in Phase 4.** `SessionJournalAggregator` queries `db.signal_history` best-effort and treats an empty result as "not wired yet". Phase 5 (Sentinel) wires signal evaluations through Mongo as part of its veto audit; until then, Daily Reviewer reports lack the "signals seen vs signals taken" comparison.
5. **Daily Reviewer's `regime_context` (VIX / SPX) feed not wired.** `SessionJournalAggregator.aggregate()` returns `Optional.empty()` for the regime block. Phase 5/6 follow-up (likely co-deployed with the dashboard's regime widget).
6. **Cost cap is a hard pre-flight check** — the cap check runs BEFORE the HTTP call so a breach wastes zero Anthropic spend. Restart-resilient via Mongo write-through (every call writes a `daily_cost` row BEFORE returning to the caller). Phase 4 accepts the silent-fail-on-Mongo-down posture documented in known issue #1.
7. **Cross-service trade events (`TradeOrderRejected`, `TradeStopTriggered`, `TradeTrailBreached`, `TradeEodFlattened`) fire CDI-only inside execution-service's JVM** and are not received by ai-agent-service. Phase 4 Narrator only narrates fills (`TradeFilled` via `tenant.fills` Kafka). Phase 5/6 adds `tenant.events.exit_*` topics so the Narrator can also observe exit events.
8. **AI replay parity** (deterministic across runs via `temperature=0` + `tool_choice` forcing) is enforced for Narrator + Reviewer prompt builders and tested via per-component tests (`*PromptBuilderTest*`). A formal AI replay harness comparable to execution-service's S7 (`ExecutionReplayHarnessTest`) is deferred to Phase 5 alongside Sentinel.
9. **Phase 4's per-phase production-readiness gate** (architecture-spec §21.1 row 4) requires 5+ RTH soak sessions on Azure dev with real Anthropic calls, cost tracked + reviewed, narratives reviewed for advice-vs-execution framing, Reviewer reports landing nightly. This is operator-only work — no code lands in CI to gate it.
10. **Narrator + Reviewer share the single `phase1` action group** with the rest of Phase 1 + Phase 3 alerts. Phase 7 splits onto Twilio for higher-severity escalation per the gap documented in `phase-1.md`. Until then, all four Phase 4 alerts are email-only via `var.alert_email`.
11. **Phase 4 dev has no Kafka broker.** Strimzi lands at Phase 6. The `tenant.fills` incoming channel swaps to `smallrye-in-memory` under `%prod` — meaning **inter-service Kafka traffic doesn't actually flow between pods in dev.** The Trade Narrator listener still wires up via the in-memory bus inside the same JVM as a future producer would publish to, but in Phase 4 dev there is no in-process producer so the narrator effectively never fires. End-to-end live narration validation requires Phase 6's Strimzi + cross-pod topics. Replay harness validates the determinism contract in tests.
12. **Mongo URL is plain-text in `values.yaml`** rather than KV-mounted. Phase 7 provisions managed Mongo (Cosmos / Atlas) and seeds `mongo-url` into KV; the chart already has the optional mount commented in `values.yaml` keyVault.objects so the cutover is a one-line change.
13. **Cluster sized at 2 × Standard_D2s_v4** (4 vCPU, 16 GB total) hosting market-data-service + execution-service + ai-agent-service at 250m / 768 MiB requests each. Phase 7 promotes to a per-workload nodepool layout per architecture-spec §16.2.

## Soak progress

Tracked in `docs/soak/phase-4.md` (or a GitHub issue per Phase 1 / Phase 3 convention). Sign-off requires:

1. ≥ 5 RTH paper-trading sessions
2. Real Anthropic calls observed (per `ai_calls` audit log + cost gauge)
3. Per-tenant per-day cost tracked and never silently exceeds the cap
4. Narratives reviewed for advice-vs-execution framing (CLAUDE.md guardrail #2 — the AI never directs a trade; narratives are post-hoc explanation only)
5. Daily Reviewer reports landing nightly at 16:30 ET (no `reviewer_run_missing` alert during soak)
6. Narrator skip ratio < 50% sustained (no `narrator_skip_rate` alert during soak)
7. No P0 / P1 incident during soak

## Related docs

- Architecture spec: `architecture-spec.md` §4 (AI Agent Layer — agent roles, cost model, failure modes, observability) + §21.1 row 4 (Phase 4 soak gate + alerts to ship)
- ADR-0006: AI Agent — Anthropic integration (hand-rolled HTTP client, cost cap, audit log shape)
- Phase 1 runbook: `docs/runbooks/phase-1.md`
- Phase 3 runbook: `docs/runbooks/phase-3.md`
- Phase 4 alerts (10-13): `infra/modules/observability/alerts.tf`
- Helm chart: `deploy/helm/ai-agent-service/`
- Deploy workflow: `.github/workflows/deploy-dev.yml` (ai-agent-service job)
- AI Agent service code: `services/ai-agent-service/src/main/java/com/levelsweep/aiagent/`
- Prompt-builder tests (replay-parity basis): `services/ai-agent-service/src/test/java/com/levelsweep/aiagent/{narrator,reviewer}/`
