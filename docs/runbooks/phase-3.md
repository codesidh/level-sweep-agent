# Phase 3 Runbook — Execution Service (paper)

**Scope**: `execution-service` running in the Azure `dev` AKS cluster, plus the continued `market-data-service` from Phase 1 and the live indicator distribution introduced by the Phase 3 precursor PR (#82). Phase 3 ships saga step 4 (entry-order placement via Alpaca paper), saga steps 5-6 (Alpaca trade-updates fill listener + per-trade FSM hand-off to decision-engine), Stop Watcher (S4, §9 trigger), Trailing Manager (S5, §10 ratchet/exit), EOD Flatten (S6, 15:55 ET force-close), and the execution replay-parity harness (S7) for the §21.1 row 3 acceptance gate.

This runbook covers the alerts the operator will need during the Phase 3 paper-trading soak (5+ RTH sessions on live Alpaca paper), known-issue / accepted-risk list, and how to recover from common failure modes.

## Quick reference

| Item | Value / command |
|---|---|
| Service URL (in-cluster) | `http://execution-service.execution-service:8083` |
| Liveness probe | `GET /q/health/live` |
| Readiness probe | `GET /q/health/ready` |
| Prometheus metrics | `GET /q/metrics` |
| Alpaca paper REST base | `https://paper-api.alpaca.markets` |
| Alpaca paper trade-updates WS | `wss://paper-api.alpaca.markets/stream` |
| Connection FSM dependency name | `alpaca-rest`, `alpaca-trade-updates-ws` |
| Logs (App Insights) | `traces \| where cloud_RoleName == "execution-service"` |
| Custom metrics (App Insights) | `customMetrics \| where cloud_RoleName == "execution-service"` |
| Container image registry | ACR — `${ACR_LOGIN_SERVER}/execution-service:<git-sha>` |
| KV-backed secrets | `alpaca-api-key`, `alpaca-secret-key`, `applicationinsights-connection-string`, `mssql-password` |
| Audit tables (MS SQL) | `eod_flatten_attempts`, `stop_breach_audit`, `trail_audit` |
| Kafka topics consumed | `tenant.commands` (TradeProposed in), `market.bars.2m`, `market.indicators.2m` |
| Kafka topics produced | `tenant.fills` (TradeFilled out) |

## Common operations

### Restart the pod

```bash
kubectl -n execution-service rollout restart deployment/execution-service
```

Phase 3 is single-replica. The in-flight trade cache (`InFlightTradeCache`) is in-memory; on restart the cache is empty until new fills arrive. Stop Watcher / Trail Manager state is reconstructed from `tenant.fills` Kafka replay (since `auto.offset.reset: latest`, only fills that arrive AFTER restart will register watchers — open positions from before the restart are **orphaned from the watcher's perspective and will only exit at EOD flatten 15:55 ET**). This is a Phase 3 accepted risk; Phase 7 hardens with persisted FSM state per architecture-spec §10.

### Tail live logs

```bash
kubectl -n execution-service logs -f deployment/execution-service
```

JSON-encoded; MDC keys: `tenant_id`, `service`, `trace_id`, `trade_id`, `client_order_id`. Filter the order-placement path with `grep "OrderPlacingTradeRouter"`, the fill path with `grep "FillListenerService"`, the EOD path with `grep "EodFlattenScheduler"`, the stop path with `grep "StopWatcherService"`, the trail path with `grep "TrailManagerService"`.

### Inspect Connection FSM state

App Insights → Logs:
```kql
customMetrics
| where name == "connection_state"
| where customDimensions.dependency in ("alpaca-rest", "alpaca-trade-updates-ws")
| top 100 by timestamp desc
| project timestamp, dependency=tostring(customDimensions.dependency), value
```
Values: `0=HEALTHY`, `1=DEGRADED`, `2=UNHEALTHY`, `3=RECOVERING`. **Both must be HEALTHY before placing an entry order**; on Risk FSM HALT in either, no new entries are submitted.

### Read the audit tables

```bash
kubectl -n execution-service exec deployment/execution-service -- \
  /bin/sh -c 'sqlcmd -S "$MSSQL_HOST" -d "$MSSQL_DB" -U "$MSSQL_USER" -P "$MSSQL_PASSWORD" \
    -Q "SELECT TOP 20 * FROM stop_breach_audit ORDER BY triggered_at DESC"'
```

Substitute `trail_audit` (RATCHET / EXIT events) or `eod_flatten_attempts` (FLATTENED / FAILED rows) as needed.

### Manually flatten an in-flight trade (operator emergency)

```bash
# Get the open positions on the broker side
curl -sH "APCA-API-KEY-ID: ${ALPACA_API_KEY}" \
     -H "APCA-API-SECRET-KEY: ${ALPACA_SECRET_KEY}" \
     https://paper-api.alpaca.markets/v2/positions

# Manually close one specific contract (paper)
curl -X DELETE \
     -H "APCA-API-KEY-ID: ${ALPACA_API_KEY}" \
     -H "APCA-API-SECRET-KEY: ${ALPACA_SECRET_KEY}" \
     "https://paper-api.alpaca.markets/v2/positions/${CONTRACT_SYMBOL}"
```

Direct broker action bypasses our FSM and audit tables. Document in the daily soak log.

### Verify the EOD flatten cron landed

The EodFlattenScheduler fires at 15:55:00 America/New_York via Quartz. Verify:

```bash
kubectl -n execution-service logs deployment/execution-service --since=10m | grep "eod flatten"
```

Expected log lines on a session with at least one open trade:
```
eod flatten: starting sessionDate=YYYY-MM-DD tradeCount=N
eod flatten: trade flattened tenantId=OWNER tradeId=... contractSymbol=... clientOrderId=eod:OWNER:... alpacaOrderId=...
eod flatten: complete sessionDate=YYYY-MM-DD flattened=N failed=0 total=N
```

Empty cache (no in-flight trades) prints: `eod flatten: no in-flight trades; skipping`.

### Force a TradeProposed for end-to-end smoke test (dev only)

When Strimzi is not yet deployed (Phase 6 lands Kafka), the in-memory connector means no producer is wired. To smoke the order-placement path in dev:

```bash
# Produce a synthetic TradeProposed via the Quarkus dev UI or by bouncing the
# decision-engine pod with a test fixture mounted. See the integration test
# fixture at services/execution-service/src/test/.../replay/ExecutionScenarios.java
# for the exact JSON shape — replicate via @QuarkusTest harness or curl against
# the in-memory producer endpoint.
```

For full end-to-end, run the replay harness's happyPathLong scenario locally first:

```bash
./gradlew :services:execution-service:test --tests ExecutionReplayHarnessTest.happyPathLongCapturesProposedAndFill
```

### Read App Insights connection string from KV

```bash
az keyvault secret show \
  --vault-name "${KEY_VAULT_NAME}" \
  --name applicationinsights-connection-string \
  --query value -o tsv
```

### Rotate Alpaca credentials

```bash
az keyvault secret set --vault-name "${KEY_VAULT_NAME}" --name alpaca-api-key --value "<new-key>"
az keyvault secret set --vault-name "${KEY_VAULT_NAME}" --name alpaca-secret-key --value "<new-secret>"
kubectl -n execution-service rollout restart deployment/execution-service
kubectl -n market-data rollout restart deployment/market-data-service
```

Both services must restart; the CSI driver re-reads on pod start. There is no live-rotation in Phase 3.

### Verify replay-parity harness still green on main

```bash
./gradlew :services:execution-service:test --tests ExecutionReplayHarnessTest
```

The harness's three hand-labeled scenarios (happyPathLong, stopHitLong, orderRejected) must produce byte-identical captures across runs. This is the §21.1 row 3 deterministic basis.

## Common diagnoses

### Symptom: "Alpaca CB UNHEALTHY" alert fires

Connection FSM for `alpaca-rest` OR `alpaca-trade-updates-ws` has hit UNHEALTHY (≥ 5 errors in the configured window). Risk FSM auto-HALTs new entries (CLAUDE.md guardrail #3 — fail-closed). Existing positions continue under deterministic stop-loss / trailing rules.

Triage:
1. **Is Alpaca up?** https://status.alpaca.markets — wait if there's an open incident.
2. **Egress IP still on the Alpaca allowlist?**
   ```bash
   az network public-ip show \
     --resource-group rg-${PROJECT}-${ENV}-net \
     --name pip-${PROJECT}-${ENV}-nat \
     --query ipAddress -o tsv
   ```
3. **Credentials rotated server-side?** Probe from the pod:
   ```bash
   kubectl -n execution-service exec deployment/execution-service -- sh -c '
     wget -qO- --header "APCA-API-KEY-ID: ${ALPACA_API_KEY}" \
              --header "APCA-API-SECRET-KEY: ${ALPACA_SECRET_KEY}" \
              https://paper-api.alpaca.markets/v2/account'
   ```
   If `401`, rotate per "Rotate Alpaca credentials" above.
4. **WS frame format change?**
   ```bash
   kubectl -n execution-service logs deployment/execution-service | grep -i "decode\|malformed"
   ```
   `AlpacaTradeUpdatesDecoder` increments a malformed counter and drops bad frames silently. If the rate is non-zero, file a P1 with a captured frame.

After Alpaca recovers and the FSM transitions HEALTHY, **manually re-arm the Risk FSM**:
```bash
# (Phase 3 has no admin endpoint yet — Phase 7 adds one. For now, restart the
# decision-engine pod which re-reads the latest persisted Risk FSM state from
# MS SQL daily_state. If the state is HALTED, manually flip it via SQL.)
kubectl -n decision-engine rollout restart deployment/decision-engine
```

### Symptom: "Order fill timeout" alert fires

A `TradeOrderSubmitted` was emitted but no matching `TradeFilled` arrived within the saga's 30-second wait. Triage:

1. **Is the order accepted by Alpaca?**
   ```bash
   curl -sH "APCA-API-KEY-ID: ${ALPACA_API_KEY}" \
        -H "APCA-API-SECRET-KEY: ${ALPACA_SECRET_KEY}" \
        "https://paper-api.alpaca.markets/v2/orders?status=all&limit=10"
   ```
   Look for the order with our deterministic `client_order_id = "<tenantId>:<tradeId>"`. If status is `accepted` or `pending_new`, the broker has it; we're awaiting fill.
2. **Is the trade-updates WS connected?** Inspect Connection FSM (above). UNHEALTHY → triage as "Alpaca CB UNHEALTHY".
3. **Is the contract liquid enough?** 0DTE options on rapid moves can show wide spreads. Cancel and re-evaluate: `curl -X DELETE ... /v2/orders/{order_id}`. Saga step 5 compensation publishes `commands.cancel_entry`.
4. **Have we lost a fill event?** Compare the orders list to `tenant.fills` Kafka topic content. If fills are visible on Alpaca's side but not in our Kafka topic, the fill listener has a bug — file a P1.

### Symptom: "EOD flatten failure" alert fires

The 15:55 ET cron tried to flatten an open trade but the broker rejected. Triage:

1. **Read the audit row:**
   ```kql
   traces
   | where cloud_RoleName == "execution-service"
   | where message contains "eod flatten: trade FAILED"
   | top 5 by timestamp desc
   ```
2. **Common failure: 422 duplicate `client_order_id`.** Means a previous EOD attempt for this trade already submitted (likely the saga restarted mid-fire and the deterministic clientOrderId `eod:<tenantId>:<tradeId>` collided). Verify the broker side has a closed position for the contract; if so, the FAILED audit row is benign — log a soak-day note.
3. **Common failure: 403 / 401.** Credentials issue — see "Rotate Alpaca credentials".
4. **Common failure: Alpaca rejected as outside trading hours.** EOD scheduler fires at 15:55:00 ET; Alpaca closes options at 16:15 ET; we have a 20-minute cushion. If the alert fires repeatedly, verify the cron timezone (`@Scheduled(cron = "0 55 15 * * ?", timeZone = "America/New_York")`).
5. **Manually flatten** via the broker direct-call (above).

If any open position survives 15:55 ET without a FAILED audit row AND without a FLATTENED row → file a **P0** (the cron didn't fire at all; 0DTE auto-exercise risk at 16:00 ET is real money even on paper).

### Symptom: "Trail manager NBBO snapshot stale" alert fires

`TrailPollScheduler` polls `/v1beta1/options/snapshots/{contractSymbol}` every 1s; consecutive failures or stale timestamps trigger this alert. Triage:

1. **Is the Alpaca options data API up?** Same status page as above.
2. **Is the contract still trading?** 0DTE options can be delisted post-15:00 ET in some weeks. Check Alpaca's `/v2/options/contracts?symbols=${UNDERLYING}&status=active` for the contract.
3. **Is our NBBO request rate-limited?** Alpaca's free options data has a quota (~200 req/min). 1-second polling = 60 req/min per held trade — well under, but if multiple trades are held simultaneously we could hit the cap. Check log for `429` responses from `AlpacaQuotesClient`.
4. **Manual NBBO probe:**
   ```bash
   kubectl -n execution-service exec deployment/execution-service -- sh -c '
     wget -qO- --header "APCA-API-KEY-ID: ${ALPACA_API_KEY}" \
              --header "APCA-API-SECRET-KEY: ${ALPACA_SECRET_KEY}" \
              "https://paper-api.alpaca.markets/v1beta1/options/snapshots/${CONTRACT}"'
   ```

A persistent stale NBBO does NOT exit the trade (fail-closed for the exit path means *don't fire spuriously*); the trade remains open until Stop Watcher fires or EOD flatten triggers.

### Symptom: Readiness probe fails (503)

1. Hit the endpoint directly:
   ```bash
   kubectl -n execution-service exec deployment/execution-service -- \
     wget -qO- http://localhost:8083/q/health/ready
   ```
2. Check for KV CSI mount failures:
   ```bash
   kubectl -n execution-service logs deployment/execution-service --tail 200
   ```
   Common modes: secret name typo, workload-identity client ID mismatch, KV network ACL blocking the CSI request, federated identity binding missing for `system:serviceaccount:execution-service:execution-service`.
3. Datasource health is intentionally disabled (`datasource.health.enabled: false`) so transient MS SQL outages don't flap liveness — readiness reflects FSM state.

### Symptom: Indicator skew ("stop.watcher.indicator_skew" metric incrementing)

`BarIndicatorJoiner` couldn't pair a `market.bars.2m` bar with a `market.indicators.2m` snapshot within 5 seconds. The bar is dropped from stop-trigger evaluation; the next bar gets a fresh chance. Triage:

1. **Is market-data-service publishing indicators?** Check the precursor PR's `IndicatorSnapshotEmitter` log:
   ```bash
   kubectl -n market-data logs deployment/market-data-service | grep "indicators-2m\|IndicatorSnapshot"
   ```
2. **Is the dev cluster running Kafka?** Phase 3 dev uses in-memory connector. If Strimzi isn't deployed (Phase 6), the indicator topic flows in-memory inside the same JVM — but execution-service and market-data-service run in separate pods, so the in-memory bus does NOT bridge them. **Phase 3 dev expectation: indicator skew always non-zero in this configuration; the stop watcher cannot fire on bars in dev.** Real fix lands at Phase 6 with Strimzi. Verified-by-test: ExecutionReplayHarnessTest's stopHitLong scenario.

## Escalation

| Severity | Action |
|---|---|
| P0 / P1 | Page codesidh — see issue #38 for the on-call rotation. EOD flatten failures with NO audit row OR overnight 0DTE auto-exercise events are P0. |
| P2 | File a GitHub issue with labels `phase-3` + `type:bug` + `p2:normal`. |
| P3 | Add to weekly triage; usually a known issue. |

## Phase 3 known issues / accepted risks

1. **Single-instance deployment.** No HA. Pod restart loses the in-memory `InFlightTradeCache` — open positions survive in the broker but become invisible to Stop Watcher / Trail Manager until next fill. EOD flatten 15:55 ET is the safety net. Phase 7 persists FSM state per architecture-spec §10.
2. **Phase 3 dev has no Kafka broker.** Strimzi lands at Phase 6. The execution-service's `tenant.commands` incoming + `tenant.fills` outgoing channels swap to `smallrye-in-memory` in the dev cluster — meaning **inter-service Kafka traffic doesn't actually flow between pods in dev.** Soak validation requires acknowledging this gap; live signal-to-order flow is exercised end-to-end only in tests (replay harness) until Phase 6.
3. **Stop watcher + Trail manager don't fire in dev** for the same reason (#2) — `market.indicators.2m` and `market.bars.2m` topics need a real broker to bridge market-data-service ↔ execution-service. Replay harness validates the determinism contract in tests.
4. **Trail Manager polls REST at 1s.** Per ADR-0004, OPRA WS upgrade is deferred. If soak surfaces missed ratchets, evaluate the WS upgrade.
5. **`OrderSubmitter.submit()` is single-attempt.** No retry on rejected entry orders (CLAUDE.md guardrail #3 + architecture-spec §17.4). The signal is time-sensitive; a retry would re-submit at worse slippage. Both broker rejects and transport failures end the trade's entry path.
6. **EOD audit DataSource Instance.** `EodFlattenAuditRepository`, `StopAuditRepository`, and `TrailAuditRepository` use `Instance<DataSource>` so the `%test` profile (no devservices DataSource) doesn't flap SmokeTest. In `%prod` / `%dev` an MS SQL DataSource binds normally.
7. **Single-tenant operation.** Per CLAUDE.md guardrail #1 — Phase A only. Multi-tenant code paths exist (every entity carries `tenantId`) but Phase B is gated on legal review.
8. **Replay parity is byte-equal across runs** (§21.1 row 3 acceptance) but only against the in-test recording pipeline. Promoting the harness stubs to real production-router assertions is a Phase 3 follow-up (deferred per S7 PR's body).
9. **No Twilio / SMS paging.** Action group is email-only.
10. **No deploy gate on alert state.** Helm rollout does not check that all Phase 3 alerts are healthy before completing. Manual operator confirmation at end of soak.
11. **Cluster sized at 2 × Standard_D2s_v4** (4 vCPU, 16 GB total) to host both market-data-service + execution-service. Phase 7 promotes to a per-workload nodepool layout per architecture-spec §16.2.

## Soak progress

Tracked in `docs/soak/phase-3.md` (or a GitHub issue per Phase 1 convention). Sign-off requires:

1. ≥ 5 RTH paper-trading sessions
2. Orders placed correctly (verified against Alpaca order log)
3. Stops fire correctly (validated against §9 trigger via app insights traces)
4. Trail ratchets observed (§10 worked example reproducible)
5. EOD flatten reliable (no FAILED rows without manual recovery)
6. No fills mismatched (compare `tenant.fills` to Alpaca server-side order history)
7. Replay parity within ±2% (re-run replay harness against captured live data)
8. No P0 / P1 incident during soak

## Related docs

- Architecture spec: `architecture-spec.md` §9.4 (execution-service) + §11 (Trade Saga) + §17.4 (no-retry policy) + §21.1 (production-readiness gate)
- ADR-0004: Alpaca as single market-data + execution provider (REST polling cadence rationale)
- ADR-0005: Stop watcher + trail manager design
- Phase 1 runbook: `docs/runbooks/phase-1.md`
- Phase 3 alerts: `infra/modules/observability/alerts.tf` (Phase 3 row 3 alerts)
- Helm chart: `deploy/helm/execution-service/`
- Deploy workflow: `.github/workflows/deploy-dev.yml`
- Replay harness: `services/execution-service/src/test/java/com/levelsweep/execution/replay/`
