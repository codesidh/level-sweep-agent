# Phase 1 Runbook — Market Data Service

**Scope**: `market-data-service` running in the Azure `dev` AKS cluster. Phase 1 ships SPY tick ingest from Alpaca, bar aggregation (1m / 2m / 15m / daily), the indicator engine (EMA13/48/200, ATR), and session-boundary level computation.

This runbook covers the alerts wired in `infra/modules/observability/alerts.tf`, the operations the on-call operator will need during the Phase 1 soak (5 RTH sessions), and the known-issue / accepted-risk list.

## Quick reference

| Item | Value / command |
|---|---|
| Service URL (in-cluster) | `http://market-data-service.market-data:8081` |
| Liveness probe | `GET /q/health/live` |
| Readiness probe | `GET /q/health/ready` |
| Prometheus metrics | `GET /q/metrics` |
| Connection FSM dependency name | `alpaca-ws` |
| Logs (App Insights) | `traces \| where cloud_RoleName == "market-data-service"` |
| Custom metrics (App Insights) | `customMetrics \| where cloud_RoleName == "market-data-service"` |
| Container image registry | ACR — `${ACR_LOGIN_SERVER}/market-data-service:<git-sha>` |
| KV-backed secrets | `alpaca-api-key`, `alpaca-secret-key`, `applicationinsights-connection-string` (optional), `mssql-password` (optional) |

## Common operations

### Restart the pod
```bash
kubectl -n market-data rollout restart deployment/market-data-service
```

Phase 1 is a single replica, so a restart drops up to 5 minutes of in-flight bars (TickRingBuffer is in-memory). The drainer drains on `ShutdownEvent` with a 5-second grace, but bars not yet emitted are lost.

### Tail live logs
```bash
kubectl -n market-data logs -f deployment/market-data-service
```

The Quarkus JSON encoder includes `tenant_id`, `service`, and (when available) `trace_id` MDC keys per architecture-spec §18.3.

### Inspect Connection FSM state
App Insights → Logs:
```kql
customMetrics
| where name == "connection_state"
| where customDimensions.dependency == "alpaca-ws"
| top 50 by timestamp desc
| project timestamp, value
```
Values: `0=HEALTHY`, `1=DEGRADED`, `2=UNHEALTHY`, `3=RECOVERING`.

### Force re-pre-warm of EMA200
Restart the pod. `LivePipeline.start(StartupEvent)` re-runs the REST historical fetch and re-seeds the EMA13/48/200 windows from up to 200 2-min bars over the last 14 hours.

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
kubectl -n market-data rollout restart deployment/market-data-service
```
The CSI driver re-reads on pod restart; there is no live-rotation path in Phase 1.

## Common diagnoses

### Symptom: Readiness probe fails (503)
1. Hit the endpoint directly:
   ```bash
   kubectl -n market-data exec deployment/market-data-service -- \
     wget -qO- http://localhost:8081/q/health/ready
   ```
2. If the response body shows `connectionState == UNHEALTHY` for `alpaca-ws`, see "Alpaca WS CB open" below.
3. If the pod hasn't reached `Ready` yet at all, check the Quarkus startup log for KV CSI mount failures:
   ```bash
   kubectl -n market-data logs deployment/market-data-service --tail 200
   ```
   Common modes: secret name typo, workload-identity client ID mismatch, KV network ACL blocking the CSI request.

### Symptom: "Alpaca WS CB open" alert fires
The Connection FSM has hit UNHEALTHY (>= 5 errors in a 30s window) and the circuit breaker is open. Triage:
1. **Is Alpaca up?** Check https://status.alpaca.markets — wait for green if there's an open incident.
2. **Is our egress IP still on Alpaca's allowlist?**
   ```bash
   az network public-ip show \
     --resource-group rg-${PROJECT}-${ENV}-net \
     --name pip-${PROJECT}-${ENV}-nat \
     --query ipAddress -o tsv
   ```
   The dev egress IP must be on the Alpaca account's IP allowlist for Algo Trader Plus.
3. **Have credentials been rotated server-side?** Quick probe from the pod:
   ```bash
   kubectl -n market-data exec deployment/market-data-service -- sh -c '
     wget -qO- --header "APCA-API-KEY-ID: ${ALPACA_API_KEY}" \
              --header "APCA-API-SECRET-KEY: ${ALPACA_SECRET_KEY}" \
              https://paper-api.alpaca.markets/v2/account'
   ```
   If `401`, rotate per "Rotate Alpaca credentials" above.
4. **Malformed frames?**
   ```bash
   kubectl -n market-data logs deployment/market-data-service | grep -i "decode error"
   ```
   If the WS frame format has changed, bump the Alpaca decoder; file a P1 issue with a frame sample attached.

### Symptom: "Bars stalled during RTH" alert fires
1. **Confirm it is actually a trading day.** US market holidays do not generate bars; the alert KQL doesn't model the holiday calendar. See https://www.nyse.com/markets/hours-calendars for the schedule.
2. **Is the WS connected?** Inspect the Connection FSM (above). If UNHEALTHY → triage as "Alpaca WS CB open".
3. **FSM HEALTHY but no bars?** The drainer or the aggregator is stuck. Check:
   ```bash
   kubectl -n market-data logs deployment/market-data-service | grep -i "drainer\|BarAggregator"
   ```
4. **Mongo down?** Bars still publish to Kafka regardless of Mongo, and `bar.emitted.total` increments before the persistence sink runs, so a Mongo outage alone does NOT stall bars. If the alert fires AND Mongo is down, the symptom is unrelated — keep digging.

### Symptom: "TickRingBuffer drop rate > 1000/min" alert fires
The drainer is being outpaced by the WS. Triage in this order:
1. **Drainer thread blocked?**
   ```bash
   kubectl -n market-data logs deployment/market-data-service | grep -i "drainer\|drained"
   ```
   Look for a stack trace from the virtual thread named `market-data-drainer`.
2. **Mongo blocking the persistence sink?** `PersistenceWiring` writes synchronously inside the bar fan-out. Check Mongo reachability:
   ```bash
   kubectl -n market-data exec deployment/market-data-service -- \
     wget -qO- http://mongo:27017
   ```
3. **CPU saturated?**
   ```bash
   kubectl top pod -n market-data
   ```
   If the pod is at request limit, bump `resources.requests.cpu` in `deploy/helm/market-data-service/values.yaml` and roll out.

### Symptom: "Bar emit P99 > 500ms" alert fires
Phase 1 hot path is dominated by the indicator engine (cheap) + Kafka publish (sub-ms when broker is local) + Mongo write (variable). Most likely:
1. **Mongo write latency.** Look at the Atlas / pod-side Mongo performance metrics; check connection pool exhaustion.
2. **Kafka broker reachability.** Phase 1 does NOT deploy a broker (Phase 6 introduces Strimzi); the chart's default is `kafka:9092` which fails fast. Verify the bar emitter is logging at WARN, not blocking.
3. **GC pressure.** Increase pod memory limit if `kubectl top` shows steady creep.

### Symptom: "Mongo dependency failure rate elevated" (P3)
1. Check Mongo pod / Atlas dashboard.
2. Confirm the connection string hasn't drifted: read `MONGO_URL` env from the pod and compare against the expected service DNS.
3. Phase 1 accepted risk: Mongo down does not stall bars (Kafka persistence is independent). This alert is informational; investigate during business hours.

## Escalation

| Severity | Action |
|---|---|
| P0 / P1 | Page codesidh — see issue #38 for the on-call rotation. Manually trigger an investigation message. |
| P2 | File a GitHub issue with labels `phase-1` + `type:bug` + `p2:normal`. |
| P3 | Add to weekly triage; usually a known issue. |

## Phase 1 known issues / accepted risks

1. **Single-instance deployment.** No HA. Pod restart loses up to 5 minutes of in-flight bars (TickRingBuffer is in-memory). Phase 7 adds the SQLite-on-disk ring buffer per architecture-spec §9.1.
2. **Trail manager NBBO** is not yet wired (Phase 3 task). Phase 1 only consumes the WS quote stream as a counter.
3. **Network policies** are permissive — egress to internet is allowed broadly so Alpaca / Anthropic / Trading Economics can be reached. Phase 7 tightens to FQDN-pinned egress.
4. **Trivy scan** runs at HIGH/CRITICAL severity but doesn't fail the build (`exit-code: 0` in `main.yml`). Phase 7 flips this to `1`.
5. **Single Connection FSM** (`alpaca-ws`). Mongo, Kafka, and MS SQL do not yet have FSMs; alert #4 reads App Insights `dependencies` rows directly. Phase 7 adds per-dependency FSMs.
6. **Holiday calendar not modeled in alert KQL.** The "Bars stalled during RTH" alert will fire on US market holidays. Operator must confirm trading day before escalating.
7. **App Insights agent overhead.** The agent adds ~50 MB JVM heap and ~5% CPU. Acceptable for Phase 1; revisit at Phase 7 if pod resource budget tightens.
8. **No Twilio / SMS paging.** Action group ships email-only. Phase 7 wires SMS via Twilio webhook.
9. **No deploy gate on alert state.** Helm rollout does not check that all 5 alerts are healthy before completing. Manual operator confirmation at end of soak.

## Soak progress

Tracked in [issue #38](https://github.com/codesidh/LevelSweepAgent/issues/38). Sign-off in [issue #39](https://github.com/codesidh/LevelSweepAgent/issues/39).

## Related docs

- Architecture spec: `architecture-spec.md`
- Phase 1 alerts: `infra/modules/observability/alerts.tf`
- Helm chart: `deploy/helm/market-data-service/`
- Deploy workflow: `.github/workflows/deploy-dev.yml`
- Connection FSM: `services/market-data-service/src/main/java/com/levelsweep/marketdata/connection/`
