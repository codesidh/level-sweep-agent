# Phase 6 Runbook — Cold-path services (Journal / Calendar / User-Config / Notification / Projection / BFF)

**Scope**: the six cold-path services introduced in Phase 6 (architecture-spec §9 cold-path component map). All run as Spring Boot 3.x apps in the Azure `dev` AKS cluster alongside the Phase 1 `market-data-service`, the Phase 3 `execution-service`, and the Phase 4 `ai-agent-service`. Phase 6 also lands four alerts (14-17 in `infra/modules/observability/alerts.tf`).

This runbook covers the alerts the operator will need during the Phase 6 paper-trading soak, the known-issue / accepted-risk list, and how to recover from common failure modes per service.

## Quick reference (per service)

| Service | Port | K8s namespace | Primary store | Liveness | Readiness | Phase A status |
|---|---|---|---|---|---|---|
| `journal-service` | 8085 | `journal-service` | Mongo (`level_sweep.journal_*`) | `/actuator/health/liveness` | `/actuator/health/readiness` | Running 1/1 |
| `calendar-service` | 8088 | `calendar-service` | NYSE holidays (in-memory) + FOMC dates (config) | `/actuator/health/liveness` | `/actuator/health/readiness` | Running 1/1 |
| `user-config-service` | 8086 | `user-config-service` | MS SQL (`tenant_config` via Flyway) | `/actuator/health/liveness` | `/actuator/health/readiness` | **Scaled to 0** — blocked on Phase 7 Azure SQL provisioning (PR #109) |
| `notification-service` | 8087 | `notification-service` | Mongo (`notifications.outbox`) + SMTP (deferred) | `/actuator/health/liveness` | `/actuator/health/readiness` | Running 1/1 |
| `projection-service` | 8089 | `projection-service` | None (stateless Monte Carlo) | `/actuator/health/liveness` | `/actuator/health/readiness` | Running 1/1 |
| `api-gateway-bff` | 8090 | `api-gateway-bff` | None (proxy + bucket4j rate limit) | `/actuator/health/liveness` | `/actuator/health/readiness` | Running 1/1 |

| Item | Value / command |
|---|---|
| Container image registry | ACR — `${ACR_LOGIN_SERVER}/<service>:latest` (path-filter rebuild — see PR #108) |
| KV-backed secrets | `applicationinsights-connection-string`, `mssql-password`, `smtp-username`, `smtp-password`, `email-to`, `mongo-url` (per-service mounted via CSI) |
| Logs (App Insights) | `traces \| where cloud_RoleName == "<service-name>"` |
| Custom metrics (App Insights) | `customMetrics \| where cloud_RoleName == "<service-name>"` |
| Kafka topics consumed | `tenant.fills`, `tenant.events.entry_complete`, `tenant.events.exit_complete`, `tenant.events.signal_evaluated` (journal); `notifications` (notification) |
| Kafka topics produced | None — all Phase 6 services are sinks or stateless query endpoints |

## Common operations (cross-cutting)

### Restart any pod

```bash
kubectl -n <namespace> rollout restart deployment/<service-name>
```

All Phase 6 services are single-replica (architecture-spec §16). Restart is non-disruptive in Phase A — none holds session state on disk; all reconstruct on boot.

### Tail live logs

```bash
kubectl -n <namespace> logs -f deployment/<service-name>
```

JSON-encoded; MDC keys: `tenant_id`, `service`, `trace_id`. The Spring Kafka container is chatty during partition rebalance — the application.yml clamps `org.springframework.kafka.listener` and `org.apache.kafka.clients.consumer` to WARN.

### Force a fresh image pull (path-filter mismatch)

PR #108 set `image.tag=latest` + `imagePullPolicy=Always`. To force a redeploy without bumping the tag:

```bash
kubectl -n <namespace> rollout restart deployment/<service-name>
```

To force a full container rebuild from source (e.g., when only YAML changed):

```bash
gh workflow run main.yml --ref main
```

### Verify all 8 active pods are Running 1/1

```bash
kubectl get pods -A | grep -vE "kube-system|gatekeeper|csi-secrets"
```

Expected (Phase 6, May 2026): `ai-agent`, `api-gateway-bff`, `calendar-service`, `execution-service`, `journal-service`, `market-data`, `notification-service`, `projection-service` all 1/1. `user-config-service` is intentionally scaled to 0 — see known issue #1.

## Per-service details

### journal-service

The audit-of-record (architecture-spec §6: CP for write, AP for query). Subscribes to `tenant.fills` (live; producer = execution-service Phase 3) and `tenant.events.*` (gap-stubbed; producers ship in Phase 5/6 follow-up). Writes to Mongo `journal_*` collections.

Common ops:

```javascript
// Last 20 audit rows for OWNER tenant
db.journal_fills.find({tenant_id: "OWNER"}).sort({timestamp: -1}).limit(20)

// Count rows received today (verifies ingest is alive)
db.journal_fills.countDocuments({tenant_id: "OWNER", session_date: "2026-05-02"})
```

Failure modes:
- **CrashLoopBackOff with `No resolvable bootstrap urls`** → see known issue #2.
- **Mongo writes failing** → alert #15 fires. Check the cluster's Mongo deployment health; the journal stays up (reads continue) but writes silently drop. Phase 7's 17a-4 WORM retention escalates this to P1.

### calendar-service

NYSE holidays (hard-coded 2026-2030) + FOMC meeting dates (from `application.yml`). Stateless GET endpoints.

```bash
# Is today an RTH session?
curl http://calendar-service.calendar-service:8088/api/v1/calendar/is-trading-day?date=2026-05-04
# Returns: {"date": "2026-05-04", "is_trading_day": true, "session_type": "RTH"}

# Next FOMC meeting
curl http://calendar-service.calendar-service:8088/api/v1/calendar/next-fomc
```

Known calendar update cadence: holiday list is committed to source; bump every December for the following year. FOMC dates come from `application.yml` — operator updates the YAML and bumps the chart.

### user-config-service

**Phase 6 dev: scaled to 0.** Flyway can't migrate without MS SQL, which is not deployed in dev (Phase 7 provisions Azure SQL per architecture-spec §13.1). Once SQL is live:

```bash
# Restore to 1 replica
kubectl -n user-config-service scale deploy/user-config-service --replicas=1

# Or revert via helm:
# Edit deploy/helm/user-config-service/values.yaml: replicas: 1
```

CRUD endpoints over `tenant_config` table; bootstraps an `OWNER` row on startup. Phase 5 Sentinel will read flags + risk caps from this table.

### notification-service

Subscribes to `notifications` Kafka topic; fans out to email (SMTP via `JavaMailSender`) + SMS (Phase 7 Twilio). Mongo `notifications.outbox` is the audit log (one row per delivery attempt with status SENT/SKIPPED/FAILED).

Common ops:

```javascript
// Failed deliveries today — manual replay candidates
db.notifications_outbox.find({tenant_id: "OWNER", status: "FAILED", session_date: "2026-05-02"})

// Skip ratio (config drift detector)
db.notifications_outbox.aggregate([
  {$match: {tenant_id: "OWNER", session_date: "2026-05-02"}},
  {$group: {_id: "$status", n: {$sum: 1}}}
])
```

Failure modes:
- **SMTP host unset** → `EmailDispatcher` logs-only and writes a `SKIPPED` outbox row. Operator fix: pre-mirror SMTP creds into Key Vault (`smtp-username`, `smtp-password`, `email-to`) and set `SMTP_HOST` via the chart's env override.
- **Notification dispatch failed > 5/hour** → alert #16 fires. P3 in Phase 6; escalates to P1 in Phase 7 once Twilio SMS for trade-fill alerts lands (a missed P1 trade alert is real-money risk).

### projection-service

Stateless Monte Carlo engine. POST a starting balance + risk params, returns 5/50/95th-percentile equity-curve projections.

```bash
curl -X POST http://projection-service.projection-service:8089/api/v1/projection/monte-carlo \
  -H "Content-Type: application/json" \
  -d '{"starting_balance": 50000, "win_rate": 0.45, "avg_win_pct": 0.05, "avg_loss_pct": -0.025, "trades": 500, "iterations": 10000}'
```

No persistence; per-request CPU bound. The Phase A throughput is "operator clicks Project" — a single replica handles it. Phase 7 may add a CDN-backed cache for repeat queries.

### api-gateway-bff

Edge proxy + bucket4j rate limit. All UI calls funnel through here. Phase 6 ships with **bypass auth ON** (`bff.security.bypass-auth: true` in dev) — Phase 10 wires Auth0 OIDC behind the same Phase B feature flag (default OFF).

Known endpoints:
- `/api/v1/journal/...` → `journal-service`
- `/api/v1/calendar/...` → `calendar-service`
- `/api/v1/projection/...` → `projection-service`
- `/api/v1/config/...` → `user-config-service` (returns 503 while user-config is scaled to 0)
- `/actuator/health` (BFF own health, not proxied)

Failure modes:
- **5xx error rate > 5% over 15min** → alert #14 fires. P2. Most common cause: a downstream service has crashed. Check `kubectl get pods -A` first.

## Alerts (cumulative, Phase 1+3+4+6)

Phase 6 lands these new alerts in `infra/modules/observability/alerts.tf`:

| # | Severity | Source | What it means | Operator action |
|---|---|---|---|---|
| 14 | P2 | `requests` table, `cloud_RoleName == "api-gateway-bff"` | BFF returning 5xx > 5% over 15min | Check downstream services for CrashLoopBackOff. Recently-merged service config? Auth provider outage? |
| 15 | P2 (DISABLED) | `dependencies`, `cloud_RoleName == "journal-service"` | Mongo write fail rate > 30% over 15min | Disabled until in-cluster Mongo deploys. Re-enable alongside the Mongo Helm release |
| 16 | P3 | `traces`, log-pattern "notification dispatch failed" | > 5 failures per hour | Check SMTP relay availability; manual replay from `notifications.outbox` rows with `status: "FAILED"` |
| 17 | P2 | `customEvents`, `name == "ContainerStarted"` | Pod restarted > 3 times in 30min | Read the failing pod's `kubectl logs --previous`; correlate with PRs landed in the last hour |

All four reuse the Phase 1 action group (`ag-${project}-${environment}-phase1`) which emails the operator. Phase 7 splits high-severity escalation onto a Twilio SMS-backed group.

`iac.yml` apply is currently broken (no remote state backend — see CLAUDE.md memory `feedback_iac_state_backend_drift`). Until Phase 7 migrates to a remote backend, these resources will land in `main` but will not deploy until manually applied via `az` CLI or after the Phase 7 state migration.

## Known issues / accepted risk

**1. user-config-service scaled to 0 in dev** (PR #109). MS SQL isn't deployed in the Phase 6 dev cluster; Flyway crashes the pod on boot trying to acquire a JDBC connection. Phase 7 provisions Azure SQL per architecture-spec §13.1; flip `replicas: 1` in `deploy/helm/user-config-service/values.yaml` once the database is live.

**2. Kafka bootstrap-servers points at `localhost:9092` in dev** (PR #109). Strimzi is on a staging branch; the `kafka:9092` service name doesn't resolve in the dev cluster, so we use `localhost:9092` (resolves to 127.0.0.1, validates, NetworkClient retries harmlessly under the spring-kafka backoff). Custom `ConcurrentKafkaListenerContainerFactory` beans in `KafkaConfig.java` don't honor `spring.kafka.listener.auto-startup` — that property only applies to the default factory. Once Strimzi lands, set `KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"` in the chart values for journal-service and notification-service.

**3. Mongo isn't deployed in dev cluster.** Both journal-service and notification-service connect to `mongo:27017` (UnknownHostException). MongoTemplate construction succeeds; the Mongo health indicator returns DOWN but is excluded from readiness checks for notification-service (see `application.yml` `management.health.mongo.enabled: false` for notification — journal still has it ON because the audit log is the service's whole reason for existing). Phase 6 follow-up deploys an in-cluster Mongo Helm release; Phase 7 swaps in managed Mongo (Cosmos / Atlas) per architecture-spec §13.2.

**4. SMTP not configured in dev.** `EmailDispatcher` logs-only and writes a SKIPPED outbox row when `SMTP_HOST` is empty. Production sets these via the chart's KV-mounted env vars; pre-mirror `smtp-username` / `smtp-password` / `email-to` into Key Vault before flipping `SMTP_HOST`.

**5. BFF auth bypass is ON in dev.** `bff.security.bypass-auth: true` skips the auth filter. Phase 10 wires Auth0 OIDC behind a Phase B feature flag (default OFF). Do NOT promote this chart to a public-internet `prod` environment without flipping the bypass off and the feature flag on.

**6. iac.yml apply blocked on state backend.** Manual `az` CLI is the workaround for cluster + KV operations. Phase 7 must `terraform import` all manually-applied resources before re-enabling auto-apply.

## Recovery cookbook

**Pod stuck in CrashLoopBackOff after a deploy-dev run** — read `kubectl logs --previous`. Recurring Phase 6 offenders:

- `No resolvable bootstrap urls given in bootstrap.servers` → known issue #2; verify the chart values.yaml has `KAFKA_BOOTSTRAP_SERVERS: "localhost:9092"` (not `kafka:9092`).
- `Connection refused: mssql-host` → known issue #1; verify the service is scaled to 0 in dev or that Phase 7's Azure SQL is reachable from the cluster.
- `failed to get objectType:secret, objectName:<X>` → the KV CSI mount can't find the secret. Pre-mirror it: `az keyvault secret set --vault-name <kv> --name <X> --value <Y>`.
- `Failed to start bean 'org.springframework.kafka.config.internalKafkaListenerEndpointRegistry'` → custom listener factory + unresolvable bootstrap; see known issue #2.

**Pod stuck Pending** — the dev cluster has 2 nodes (Phase 1+3+4+6 pods compete for them); see CLAUDE.md memory for the Recreate strategy used by Phase 6 charts. Confirm `kubectl describe pod` doesn't show `Insufficient cpu` / `Insufficient memory` and that `kubectl get nodes` reports both Ready.

**Pod Running 0/1 (readiness failing)** — read the readiness endpoint directly: `kubectl -n <ns> exec -it <pod> -- wget -O- http://localhost:<port>/actuator/health/readiness`. The health response shows which indicator is DOWN (mongo / kafka / SMTP / disk).

**Suspect a regression after a recent merge** — `git log --oneline main -10` and pair against the failing service's container image SHA: `kubectl -n <ns> get deploy <service> -o jsonpath='{.spec.template.spec.containers[0].image}'`.

**Need to manually replay a failed notification** — the row is in `notifications.outbox` with `status: "FAILED"`. Phase 7 adds a CLI; until then, write to a sister `notifications` Kafka topic with a fresh idempotency key and let the consumer dedupe.
