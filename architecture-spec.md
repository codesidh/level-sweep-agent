# LevelSweepAgent — Architecture Specification

**Version**: 2.4
**Companion to**: `requirements.md` v1.0
**Status**: Locked for Phase A (single-tenant); Phase B gated on legal review

**v2.4 changelog**: Alpaca is now the single market-data + execution provider (per ADR-0004). Polygon removed from the codebase. WS endpoint changes to `wss://stream.data.alpaca.markets/v2/sip` for stocks; options chain via REST `/v1beta1/options/snapshots/{underlying}`. Trail manager (Phase 3) uses 1s REST polling as a default; OPRA WS is a deferred upgrade if soak shows ratchet misses. Cost projection updated: ~$99/mo Alpaca Algo Trader Plus replaces ~$199/mo Polygon Stocks Advanced.

**v2.3 changelog**: per-phase production-readiness gate (§21.1) — every phase ships to Azure dev, soaks ≥5 RTH sessions against live external deps, ships observability + alerts + runbook before the next phase can merge. Stage environment dropped from Phase A active CI matrix (kept in IaC skeleton for Phase B). Phase 8/9 simplified — most operational lift now happens in earlier phases.

**v2.2 changelog**: time-zone discipline (§5 #10, §10.1), latency-budget header clarified (§14), per-table retention column (§13.1), Anthropic 4xx/5xx/529 explicit failure modes (§4.9), JIT warm-up scope clarified (§12.3), CLAUDE.md glossary expansion.

---

## 1. Mission

Build a fully automated **AI-agentic trading system** for the four-level liquidity sweep strategy. The system presents to users as an "AI agent that trades for them"; internally it is a **deterministic execution core with an AI intelligence overlay**. Multi-tenant-shaped from day 1, operated single-tenant in Phase A. Phase B (paying customers) is **gated on completion of legal review**.

---

## 2. Phasing Strategy

| Phase | Status | Scope | Tenants |
|---|---|---|---|
| **A** | Active | Personal-use agent for owner; full strategy automation; private deployment; no public UI | 1 (owner) |
| **B** | **Blocked on legal counsel** | Multi-tenant SaaS; per-user Alpaca OAuth; subscription/billing; public UI; SLA | N |

### Phase A → Phase B unlock criteria
1. Legal counsel sign-off on RIA / broker-dealer posture
2. Phase A live-trading soak ≥ 60 sessions with documented edge
3. SOC 2 Type I controls baseline (or equivalent for the regulator)
4. Privacy / KYC / AML processes defined
5. Terms of Service, Privacy Policy, Risk Disclosure published
6. Custodial model confirmed: **users custody their own funds** via Alpaca OAuth — we never touch user money

Until criteria are met, Phase B code paths exist but are **feature-flagged off**.

---

## 3. Multi-Tenant Posture (code-level rules from day 1)

| Rule | Why |
|---|---|
| Every persistent entity carries `tenant_id` (NOT NULL, indexed) | Painless multi-tenant unlock |
| Every Kafka topic is keyed by `tenant_id` (or `(tenant_id, symbol)`) | Partition isolation; no noisy-neighbor on hot tenants |
| Every API request resolves a `TenantContext` at the gateway, propagated via JWT claim + `X-Tenant-Id` header | Single source of truth |
| No global mutable state in services; state always scoped to tenant | Horizontal scale, blast-radius containment |
| Per-tenant rate limits and quotas at APIM and at each service | Thundering-herd defense |
| Per-tenant kill switch (HALT flag in Risk FSM) | Stop a tenant without affecting others |
| Per-tenant secrets (Alpaca tokens) encrypted with tenant-specific key in Key Vault | Defense in depth |
| Per-tenant AI cost cap and tool scoping | LLM blast-radius containment |

**Phase A operating mode**: a single bootstrap tenant (`tenant_id = OWNER`) is provisioned at deploy time. All multi-tenant infrastructure runs unchanged.

---

## 4. AI Agent Layer

### 4.1 Conceptual model

To users, the system is **an AI agent that trades for them**. Internally:

| Layer | Role | Always in path? |
|---|---|---|
| **Deterministic core** (FSMs, saga, rules engine) | The agent's *codified discipline* and *muscle memory* | Yes — all order decisions |
| **AI overlay** (Claude-powered) | The agent's *mind* — reasons, narrates, supervises, converses | No — async, advisory, off the hot stop-loss path |

**Hard rule**: the AI **cannot place orders or mutate financial state**. Only the deterministic core does that. The AI's writes are limited to (a) one narrow VETO channel for entries, (b) post-trade narratives, (c) suggestions that require user approval.

### 4.2 Why hybrid (not pure-LLM)

| Concern | Pure-LLM | Hybrid |
|---|---|---|
| Latency | 1–3s per call | <250ms hot path (LLM async) |
| Determinism | Non-deterministic | Order path replayable bar-for-bar |
| Hallucination → wrong-priced order | Real risk | Impossible (LLM has no order tool) |
| Cost per trade | $0.10–$1.00 | <$0.05 |
| Auditability | Hard | FSM transitions + LLM call log = full audit |
| Compliance posture | Investment-advice-like | Strategy executor + explainer |

### 4.3 Agent roles

#### 4.3.1 Pre-Trade Sentinel (advisory veto) — Phase A enabled

| Aspect | Spec |
|---|---|
| **When invoked** | At signal-emit time, after Risk Manager approval, before Strike Selector |
| **Inputs** | Signal, FSM state, indicators, levels, recent fills, news headlines (last 30min), market regime (VIX, SPX trend) |
| **Output (JSON)** | `{decision: ALLOW \| VETO, confidence: 0.0–1.0, reasoning: string}` |
| **Veto threshold** | `confidence ≥ 0.85` |
| **Default on uncertainty/timeout** | ALLOW (fail-open for AI; deterministic engine remains conservative on its own) |
| **Latency budget** | 30s (15-min candle close has minutes of slack before next decision point) |
| **Model** | Claude Haiku 4.5 |
| **Cost cap** | $1 / tenant / day |

The Sentinel is the only AI role with a write channel into the trading saga, and that channel is hard-rate-limited and confidence-gated.

#### 4.3.2 Trade Narrator (post-trade explanation) — Phase A enabled

| Aspect | Spec |
|---|---|
| **When** | After every entry, exit, halt, or skip |
| **Output** | 1–3 sentence narrative for dashboard + journal |
| **Latency** | Async, no SLO |
| **Model** | Claude Sonnet 4.6 |
| **Cost cap** | $1 / tenant / day |

#### 4.3.3 Conversational Assistant (user chat) — Phase A read-only

| Aspect | Spec |
|---|---|
| **When** | User opens chat in dashboard |
| **Tools (Phase A)** | `get_state`, `get_levels`, `get_signals`, `get_indicators`, `get_projections`, `search_journal`, `get_news`, `get_market_context` (all read-only) |
| **Tools (Phase B)** | adds `propose_config_change`, `propose_skip_today`, `flag_for_review` (all require user approval to apply) |
| **Latency** | <3s P99 to first streamed token |
| **Model** | Claude Sonnet 4.6 |
| **Cost cap** | $2 / tenant / day |

#### 4.3.4 Daily Reviewer (EOD batch) — Phase A enabled

| Aspect | Spec |
|---|---|
| **When** | 16:30 ET (after EOD reconciliation) |
| **Inputs** | Full session journal, signals (taken + skipped), regime context, prior 5-day comparison |
| **Output** | Structured report: summary, anomalies, optional config-tweak proposals (user reviews) |
| **Latency** | 5min batch |
| **Model** | Claude Opus 4.7 |
| **Cost cap** | $1 / tenant / day |

#### 4.3.5 Operations Agent — Phase B only
On-call helper for ops team (alert triage, log search, runbook execution). Out of scope for Phase A.

### 4.4 Boundaries (the agent CANNOT)

- ❌ Place or cancel broker orders (only Execution Service)
- ❌ Mutate Trade FSM, Risk FSM, or Session FSM (only their owners)
- ❌ Modify tenant config without explicit user approval
- ❌ Access another tenant's data (per-tenant scoped tools enforce isolation)
- ❌ Override circuit breakers, halt flag, or EOD flatten
- ❌ Make external network calls beyond declared tool surface
- ❌ Persist arbitrary data — only via approved memory tools

### 4.5 Tool surface (Anthropic tool-use protocol)

Implemented as Java methods registered as Anthropic tools. Per-tenant scoping enforced at the tool boundary — every tool method takes implicit `tenant_id` from calling context.

#### Read-only tools (all roles)
| Tool | Returns |
|---|---|
| `get_current_state(tenant_id)` | Session FSM, Risk FSM, open positions, daily_state |
| `get_levels(tenant_id, date)` | PDH, PDL, PMH, PML |
| `get_signal_history(tenant_id, range)` | Signal evaluations with full context (taken + skipped) |
| `get_indicators(tenant_id, ts)` | EMA13/48/200 + ATR(14) snapshot |
| `get_projections(tenant_id, params)` | Monte Carlo for given win rate, days, position size |
| `get_news(date_range, importance)` | Calendar events + cached headlines |
| `get_market_context(ts)` | VIX, SPX regime, breadth, time-of-day flags |
| `search_journal(tenant_id, query)` | Free-text search of trade narratives + signal evaluations |
| `recall_memory(tenant_id, query)` | Per-tenant short/long-term agent memory |

#### Suggestion tools (Phase B; require user approval to apply)
| Tool | Purpose |
|---|---|
| `propose_config_change(tenant_id, change_spec)` | Drafts config update; user reviews in UI |
| `propose_skip_today(tenant_id, reason)` | Drafts one-day blackout |
| `flag_for_review(trade_id, note)` | Adds note to trade record (does not modify trade) |
| `commit_memory(tenant_id, summary)` | Adds entry to long-term memory |

#### Veto tool (Sentinel only)
| Tool | Purpose |
|---|---|
| `veto_signal(signal_id, confidence, reasoning)` | Sets `signal.vetoed=true` in saga; honored only if `confidence ≥ 0.85` |

### 4.6 Memory model

| Layer | Store | Scope | Retention |
|---|---|---|---|
| **Conversation working memory** | SQLite (per-pod) | Single chat session | Session lifetime |
| **Short-term memory** | Mongo `agent_memory.short_term` | Per tenant, last 30d | Sliding 30d |
| **Long-term memory** | Mongo `agent_memory.long_term` | Per tenant, summarized | Permanent w/ TTL on entries |
| **Cross-tenant patterns (Phase B)** | Mongo `agent_memory.global` | Anonymized, aggregated | Permanent |

Long-term entries authored by Daily Reviewer summarizing patterns (e.g., "this tenant frequently asks about EMA gap meaning").

### 4.7 Latency impact on hot path

| Hop | Without Sentinel | With Sentinel |
|---|---|---|
| Signal evaluated | t=0ms | t=0ms |
| Risk approved (in-process) | +20ms | +20ms |
| **Sentinel call (NEW)** | n/a | +500ms–30s budget |
| Strike picked | +5ms | +5ms |
| Entry order submitted | +5ms | +5ms |
| Alpaca fill | +100–200ms | +100–200ms |

Sentinel adds non-trivial latency, **but only on rare entry signals (1–2/day max)**. Entry timing is candle-close-driven with minutes of slack before the next decision point. **Sentinel is NEVER on the stop-loss or trailing-stop path** — those are pure deterministic.

### 4.8 Cost model (Phase A)

Daily ceiling per tenant: **$5 total** ($1 Sentinel + $1 Narrator + $1 Reviewer + $2 Assistant).

| Role | Calls/day (typical) | Tokens/call (in/out) | Daily cost |
|---|---|---|---|
| Sentinel | 0–2 | ~3000 / 200 | $0.05 |
| Narrator | 0–4 | ~1500 / 150 | $0.04 |
| Assistant | 0–20 | ~2000 / 500 | $0.50 |
| Reviewer | 1 | ~10000 / 1500 | $0.30 |

Prompt caching (system prompt + tool defs + recent context) reduces this by ~70% on repeated invocations. Cap enforced by service-level token counter; on breach the role degrades to no-op (Sentinel: ALLOW, Narrator: skip, Assistant: error, Reviewer: skip).

### 4.9 Failure modes

| Failure | Behavior |
|---|---|
| Anthropic API completely down (DNS/TCP) | Sentinel → ALLOW; Narrator → queue+retry; Assistant → graceful error |
| Anthropic `429` rate-limited | CB opens; Sentinel → ALLOW until recovered; SDK auto-retry **disabled** for Sentinel (fail-open faster), enabled for Narrator/Reviewer |
| Anthropic `529` overloaded (common at 09:30 ET burst) | Sentinel → ALLOW immediately; Narrator/Reviewer queue with exponential backoff |
| Anthropic `503` service-unavailable | Same as 529 |
| Anthropic `400 prompt_too_long` | Truncate via `recall_memory` summary path; if still too long → skip with alert |
| Anthropic `4xx invalid_request_error` (other) | No retry; alert (likely tool-definition or schema regression) |
| Sentinel returns malformed JSON / non-tool-call response | Default ALLOW; alert |
| Sentinel exceeds 30s budget | Default ALLOW; latency alert |
| Cost cap breached | Sentinel ALLOW, Narrator+Reviewer skip, Assistant disabled until 00:00 ET |
| Memory store down | Stateless mode (no personalization); chat warns user |
| Tool call returns error | Agent gets error in tool result; can retry once or report to user |
| Streaming response interrupted mid-stream | Reconcile partial `output_tokens` with budget; surface partial response to user with warning |

### 4.10 Observability for AI

Every AI call logged to Mongo `audit_log.ai_calls`:
- `trace_id` correlated with trade trace
- `tenant_id`, `role`, `model`
- Prompt hash (full prompt in cold blob storage)
- Tool calls + their results
- Response (full text)
- Tokens (prompt, completion, cached)
- Latency, cost, cache hit ratio

Surfaced in App Insights dashboards: cost-per-tenant, latency P50/P99, veto rate, narrator skip rate.

### 4.11 Compliance stance for AI

| Concern | Phase A | Phase B |
|---|---|---|
| Personalized investment advice | Avoided — agent explains user's pre-configured strategy only | ToS must clarify same |
| Explainability | Every AI decision logged with reasoning | Plus user-facing "why?" affordance |
| Bias / fairness | Owner-only (irrelevant) | Audit veto patterns by tenant cohort |
| Data leakage | Single-tenant | Per-tenant memory boundaries enforced at tool layer |
| Regulator scrutiny | N/A (private use) | Legal review must address FINRA AI bulletins, SEC Reg BI |

The agent is positioned as a **strategy executor and explainer**, not an advisor. The user defines the strategy parameters; the agent operates them.

---

## 5. Architecture Principles

1. **Fail-closed** on the order path: any uncertainty → halt new entries.
2. **Determinism**: same inputs → same outputs. Replayable from journal.
3. **Idempotency**: every external command tagged with deterministic client-id; retries safe.
4. **CAP per domain** (see §6): consistency where money moves, availability where it doesn't.
5. **State machines as code**: every lifecycle (session, trade, risk, order, position, connection) is an explicit FSM.
6. **Hot-path latency over service purity**: in-process composition where it preserves latency.
7. **Observability is a first-class feature**: every decision (and non-decision) is logged with full input context.
8. **Multi-tenant-ready from line 1**: no shortcut allowed.
9. **AI is advisory, not authoritative on order placement**: deterministic core remains the order-path source of truth.
10. **Time discipline**: all scheduling and FSM time gates use `ZoneId.of("America/New_York")` (not fixed-offset EST). Timestamps persisted as UTC `Instant` plus ET local time on every audit row. JVM `tzdata` kept current. Replay set must include both spring-forward and fall-back DST sessions.

---

## 6. CAP Profile per Domain

| Domain | Profile | Rationale |
|---|---|---|
| Order placement / position state / risk budget | **CP** (strict) | Single-writer-per-tenant, sync replication, fail-closed on partition |
| Audit journal | **CP** for write, **AP** for query | Writes must succeed; reads can lag |
| Market data ingest | **AP** | LWW on duplicates; eventual consistency on aggregates |
| Indicators (EMA/ATR) | **CP** in-process | Single owner per (tenant, symbol); deterministic recompute on restart |
| Dashboard / projection / reports | **AP** | Stale reads acceptable; reads from replica |
| Notification delivery | **AP** | Eventually delivered; deduplicated by consumer |
| AI agent calls | **AP** | Best-effort; fail-open on errors |

---

## 7. Component Map

```
┌───────────────────────── Tier 1: UI ────────────────────────────────────────────┐
│  Angular (latest) + Tailwind CSS                                                 │
│  · Dashboard  · Configuration  · Projection  · Trade Journal  · AI Chat          │
│  Deployed: Azure Static Web Apps (Phase A) | Vercel (alt)                        │
└──────────────────────────────────┬───────────────────────────────────────────────┘
                                   │ HTTPS + JWT (Auth0)
┌──────────────── Tier 2: Edge / API Gateway ──────────────────────────────────────┐
│  Azure API Management — JWT validation · per-tenant rate limit · WAF · routing   │
└──────────────────────────────────┬───────────────────────────────────────────────┘
                                   │
              ┌────────────────────┼─────────────────────┐
              ▼                    ▼                     ▼
   ┌──────────────────┐   ┌────────────────┐   ┌─────────────────────┐
   │ User & Config    │   │  Projection    │   │ Trade Journal API   │
   │ Service (cold)   │   │  Service (cold)│   │ (read replica)      │
   └────────┬─────────┘   └────────┬───────┘   └────────┬────────────┘
            │                      │                    │
            ▼                      ▼                    ▼
       ┌────────────────────────────────────────────────────┐
       │  MS SQL (HA) · Mongo (Cosmos) · SQLite per pod     │
       └────────────────────────────────────────────────────┘

┌──────────────── Tier 2 (HOT PATH): trading core on AKS ─────────────────────────┐
│                                                                                  │
│  ┌──────────────────┐   Strimzi Kafka topics    ┌──────────────────────────┐    │
│  │ Market Data Svc  │── market.bars.{tf} ──────▶│   Decision Engine         │   │
│  │ Alpaca ingest   │   (key=symbol)            │  · Indicator Engine       │   │
│  │ Bar aggregation  │                           │  · Signal Engine (FSM)    │   │
│  │ ATR/EMA stream   │                           │  · Risk Manager (FSM)     │   │
│  └──────────────────┘                           │  · Strike Selector        │   │
│                                                 │  · Trade Saga Orchestrator│   │
│                                                 └────┬───────────────┬──────┘   │
│                                          AI veto    │               │           │
│                                          (sync)     │               │           │
│                                                     ▼               │           │
│                                           ┌──────────────┐          │           │
│                                           │ AI Agent Svc │          │           │
│                                           │  Sentinel    │          │           │
│                                           │  (Haiku 4.5) │          │           │
│                                           └──────────────┘          │           │
│                                                                     │ commands  │
│                                                                     ▼           │
│                                          ┌──────────────────────────────────┐   │
│                                          │   Execution Service (Alpaca)     │   │
│                                          │   · entry/exit market orders     │   │
│                                          │   · stop watcher (2-min close)   │   │
│                                          │   · trailing manager             │   │
│                                          │   · 15:55 ET EOD flatten         │   │
│                                          │   · OAuth token refresh          │   │
│                                          └────────┬─────────────────────────┘   │
│                                                   │                             │
│                            tenant.events ─────────┼──────▶ AI Agent Svc:        │
│                            (entry/exit/halt)      │       Narrator (Sonnet)     │
│                                                   ▼                             │
│                              ┌────────────────────────────────────────────┐    │
│                              │   Journal & State Service                   │    │
│                              │   MS SQL (orders, positions, P&L, FSM)      │    │
│                              │   Mongo (audit, signal evals, ai_calls)     │    │
│                              └────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────────────┘

┌────────────── Tier 2 (cold path): support + AI ─────────────────────────────────┐
│  Calendar Svc  ·  Notification Svc  ·  ETL Pipeline  ·  AI Agent Svc            │
│   (news blackout) (Telegram/Email)   (historical→DW) (Assistant chat, Reviewer) │
└──────────────────────────────────────────────────────────────────────────────────┘

Cross-cutting:
  · Auth0 IDP    · Azure Key Vault       · OpenTelemetry → App Insights
  · Anthropic Claude API  · Resilience4j (CB+bulkhead+retry)
  · Per-(tenant, symbol) Kafka partitioning
  · GraalVM native images for hot-path; JIT warm-up at 09:00 ET
```

---

## 8. Service Catalog

| # | Service | Path | Tier | Tech | Storage |
|---|---|---|---|---|---|
| 1 | **Market Data Service** | hot | Tier 2 | Quarkus + Vert.x | Mongo (raw bars), SQLite (per-pod cache) |
| 2 | **Decision Engine** | hot | Tier 2 | Quarkus + Disruptor | SQLite (indicator state), MS SQL (FSM checkpoint) |
| 3 | **Execution Service** | hot | Tier 2 | Quarkus + Resilience4j | MS SQL (orders, positions, fills) |
| 4 | **AI Agent Service** | mixed | Tier 2 | Quarkus + Anthropic SDK | Mongo (memory, ai_calls) |
| 5 | **Journal & State Service** | warm | Tier 2 | Quarkus | MS SQL (record), Mongo (audit) |
| 6 | **User & Config Service** | cold | Tier 2 | Spring Boot | MS SQL |
| 7 | **Projection Service** | cold | Tier 2 | Spring Boot | Mongo (read model) |
| 8 | **Calendar Service** | cold | Tier 2 | Spring Boot | Mongo |
| 9 | **Notification Service** | cold | Tier 2 | Spring Boot | Mongo |
| 10 | **API Gateway / BFF** | edge | Tier 2 | APIM + Spring BFF | — |

**Frontend** (Angular) is Tier 1; databases + Kafka are Tier 3 / infra.

---

## 9. Service Specs (hot path)

### 9.1 Market Data Service
- **Inputs**: Alpaca WebSocket (trades, quotes), REST (historical), pre-market data feed
- **Responsibilities**: maintain WS with reconnect/backoff; build 1-min bars from ticks; aggregate to 2m/15m on aligned ET clock; compute ATR(14) on daily bars at 09:00 ET; publish bars to `market.bars.{tf}`
- **Topics published**: `market.bars.1m`, `market.bars.2m`, `market.bars.15m`, `market.bars.daily`, `market.atr.daily`
- **Partition key**: `symbol`
- **Critical SLO**: bar publication ≤ 100ms post-close
- **State**: stateless (replays from Alpaca REST on cold start)
- **Backpressure**: WS handler is non-blocking. Inbound ticks land in a 5-min SQLite ring buffer; Mongo `bars_raw` writes are async batched. If buffer fills (e.g., 09:29:30 ET FOMC burst at 50k+ ticks/sec), oldest non-canonical ticks are dropped with an alert; canonical 1-min OHLC reconstruction is available from Alpaca REST as a backstop.

### 9.2 Decision Engine
- **Hosts** (in-process): Indicator Engine + Signal Engine + Risk Manager + Strike Selector + Trade Saga Orchestrator
- **Inputs**: subscribes to `market.bars.{2m,15m}`, `market.atr.daily`, `tenant.config.changed`, `tenant.fills`, `tenant.veto.responses`
- **Outputs**: `tenant.commands.entry`, `tenant.commands.exit`, `tenant.events.signal_evaluated`, `tenant.veto.requests`
- **Indicator Engine**: rolling EMA(13/48/200) on 2m, ATR(14) on daily, pure functions
- **Signal Engine**: implements §6/§7/§8 of `requirements.md`; Session FSM owns
- **Risk Manager**: implements §11 of `requirements.md`; Risk FSM per tenant per day
- **Strike Selector**: pulls option chain via Market Data; first-ITM rule + liquidity gate
- **Trade Saga Orchestrator**: Trade FSM per active trade; coordinates with Sentinel + Execution
- **State**: FSM checkpoints in MS SQL; in-memory hot state with SQLite snapshot every 5s for crash recovery
- **Tenant scoping**: an instance owns one or more `(tenant_id, symbol)` shards via consumer-group assignment

### 9.3 AI Agent Service
- **Hosts**: Pre-Trade Sentinel + Trade Narrator + Conversational Assistant + Daily Reviewer
- **Inputs**:
  - Sentinel: synchronous call from Decision Engine via `tenant.veto.requests` (Kafka request-response with 30s timeout)
  - Narrator: subscribes to `tenant.events` (entry/exit/halt)
  - Assistant: HTTP from BFF
  - Reviewer: cron at 16:30 ET
- **Outputs**:
  - Sentinel: publishes to `tenant.veto.responses`
  - Narrator: writes to `journal.narratives`
  - Assistant: streaming HTTP response
  - Reviewer: writes report to `journal.daily_reports`
- **Tool layer**: per-tenant scoped Java methods invoked via Anthropic tool-use protocol
- **State**: per-pod conversation memory (SQLite); per-tenant short/long-term memory in Mongo

### 9.4 Execution Service
- **Inputs**: subscribes to `tenant.commands.entry`, `tenant.commands.exit`
- **Responsibilities**:
  - Resolve tenant Alpaca OAuth token (Phase A: owner token from Key Vault; Phase B: per-tenant OAuth flow)
  - Submit market orders with deterministic `client_order_id = sha256(tenant_id|trade_id|action)`
  - Subscribe to Alpaca trade-update stream; emit `tenant.fills`
  - Stop Watcher: subscribes to `market.bars.2m`; evaluates §9 stop trigger
  - Trailing Manager: subscribes to held option's NBBO; tracks trailing floor per §10 of req. **Trail evaluation uses last NBBO mid** (not bid or ask). Ratchet update requires a *sustained* move — exact "sustained" definition deferred to Phase 3 build (per §22 #2); default placeholder: NBBO mid above current floor for ≥ 3 consecutive 1-second snapshots
  - EOD Watcher: 15:55 ET force-flatten
- **Topics published**: `tenant.fills`, `tenant.events.exit_triggered`
- **Idempotency**: deterministic client-order-id; duplicates rejected by Alpaca
- **Critical SLO**: command-received → order-submitted ≤ 50ms P99

---

## 10. State Machines

All FSMs persisted (MS SQL) on every transition; transitions are append-only events that double as audit trail.

### 10.1 Session FSM (per tenant per trading day)

> All times below are **local to `America/New_York`** (per Architecture Principle #10). DST transitions are handled by JVM `tzdata`; replay set must include a spring-forward and a fall-back session.

```
PRE_OPEN
  └─[09:00 ET]─▶ ARMING (warmup, levels, equity, calendar)
                  └─[09:29:30 ET]─▶ ARMED (or BLACKOUT if news day)
                                     └─[09:30 ET]─▶ TRADING
                                                     ├─[14:30 ET]─▶ CUTOFF
                                                     │              └─[15:55 ET]─▶ EOD_FLATTEN
                                                     │                              └─▶ CLOSED
                                                     └─[risk halt]──▶ HALTED
                                                                       └─[15:55 ET]─▶ EOD_FLATTEN
```

### 10.2 Risk FSM (per tenant per trading day)
```
WITHIN_BUDGET ──[loss ≥ 0.7×budget]──▶ AT_RISK ──[loss ≥ budget]──▶ HALTED
```
HALTED is terminal for the day; reset at next session's PRE_OPEN.

### 10.3 Trade FSM (per active trade)
```
SIGNAL_EMITTED ─▶ APPROVED ─▶ SENTINEL_PENDING ─▶ STRIKE_PICKED ─▶ ENTRY_ORDERED ─▶ FILLED
                                  │                                                    │
                                  └─[VETO]─▶ ABORTED                                   ▼
                                                                                   MANAGING
                                            ┌──────────────────────┬─────────────────────┐
                                            ▼                      ▼                     ▼
                                        STOP_HIT             TRAIL_BREACHED          EOD_FLATTEN
                                            │                      │                     │
                                            └────────▶ EXIT_ORDERED ◀─────────────────────┘
                                                          │
                                                          ▼
                                                     EXIT_FILLED ─▶ CLOSED

Compensation paths:
   ENTRY_ORDERED stuck > 30s        ─▶ CANCEL_ENTRY ─▶ ABORTED
   FILLED but watcher arm fails     ─▶ EMERGENCY_FLATTEN ─▶ EXIT_ORDERED
   EXIT_FILLED reconciliation fails ─▶ ALERT + RETRY
   Sentinel timeout                 ─▶ default ALLOW; transition continues
```

### 10.4 Order FSM (per broker order, child of Trade FSM)
```
SUBMITTED ─▶ ACCEPTED ─▶ PARTIAL_FILLED ─▶ FILLED
                          │                  │
                          └─▶ CANCELED       └─▶ REJECTED (terminal)
```

### 10.5 Position FSM (per held option contract)
```
OPENING ─▶ OPEN ─▶ CLOSING ─▶ CLOSED
```

### 10.6 Connection FSM (per external dep: Alpaca WS, Alpaca REST, Auth0, MS SQL, Mongo, Kafka, Anthropic)
```
HEALTHY ─[3 consec failures]─▶ DEGRADED ─[CB open]─▶ UNHEALTHY ─[half-open probe ok]─▶ RECOVERING ─▶ HEALTHY
```
When any **hot-path** Connection FSM enters DEGRADED → warning. UNHEALTHY → Risk FSM auto-HALTS (fail-closed). Anthropic UNHEALTHY → Sentinel falls back to ALLOW (fail-open for AI).

---

## 11. Saga Orchestrator (Trade Saga)

The Trade Saga lives **inside the Decision Engine** and is the only writer of the Trade FSM.

### Steps and compensations

| # | Step | Forward command | Compensation if it fails |
|---|---|---|---|
| 1 | Risk approval | `RiskManager.approve(signal)` | None (synchronous in-process) |
| 2 | **Sentinel veto check** | publish `veto.requests`; await `veto.responses` (30s) | Timeout/error → default ALLOW; alert |
| 3 | Strike selection | `StrikeSelector.pick()` | None (synchronous) |
| 4 | Submit entry order | publish `commands.entry` | Idempotent — Execution Service handles |
| 5 | Wait for fill | listen `events.fill` (timeout 30s) | publish `commands.cancel_entry` if timeout |
| 6 | Register watchers | arm Stop Watcher + Trailing Manager | publish `commands.emergency_flatten` if arm fails |
| 7 | Manage trade | wait for stop / trail / EOD trigger | n/a |
| 8 | Submit exit order | publish `commands.exit` | retry up to 3× then alert |
| 9 | Wait for exit fill | listen `events.fill` (timeout 30s) | escalate alert + manual ops review |
| 10 | Settle P&L + update Risk FSM + Narrator | atomic MS SQL transaction; emit `events.entry_complete`/`events.exit_complete` | retry; failure halts new trades for the day |

The Trade Saga is **stateless across requests** but **stateful per saga instance**, with state persisted to MS SQL on every transition. Recovery on restart: load all in-flight Trade FSMs and resume from last persisted state.

---

## 12. Messaging Topology (Kafka via Strimzi on AKS)

### 12.1 Topic naming convention

| Topic | Key | Partitions | Retention | Notes |
|---|---|---|---|---|
| `market.bars.1m` | `symbol` | 8 | 7d | Raw bars |
| `market.bars.2m` | `symbol` | 8 | 7d | Aggregated |
| `market.bars.15m` | `symbol` | 4 | 30d | Aggregated |
| `market.bars.daily` | `symbol` | 4 | 365d | Daily history |
| `market.atr.daily` | `symbol` | 4 | 365d | Computed ATR |
| `tenant.signals` | `tenant_id` | 16 | 90d | All signal evaluations |
| `tenant.commands` | `tenant_id` | 16 | 7d | Decision Engine → Execution |
| `tenant.fills` | `tenant_id` | 16 | 365d | Alpaca → all listeners |
| `tenant.events` | `tenant_id` | 16 | 365d | All FSM transitions; basis for audit |
| `tenant.veto.requests` | `tenant_id` | 16 | 7d | Decision Engine → Sentinel |
| `tenant.veto.responses` | `tenant_id` | 16 | 7d | Sentinel → Decision Engine |
| `notifications` | `tenant_id` | 8 | 7d | Fan-out for alerts |
| `dlq.{topic}` | original key | varies | 30d | Dead-letter queue |

### 12.2 Consumer group strategy

- One consumer group per logical service (`decision-engine`, `execution-service`, `journal-service`, `ai-agent-service`)
- Consumer instances within a group divide partitions; partition stickiness preserves single-writer-per-tenant guarantees
- **Phase A**: 1 instance per service is sufficient
- **Phase B**: scale instances up to partition count

### 12.3 Consumer / producer config baseline

| Setting | Value | Reason |
|---|---|---|
| `partition.assignment.strategy` | `cooperative-sticky` | Prevents wholesale rebalance during Sentinel's 30s parked wait |
| `max.poll.interval.ms` | `120000` (2 min) | Accommodates worst-case Sentinel timeout + saga step |
| `enable.idempotence` (producer) | `true` | Per §17.3 |
| `acks` (producer) | `all` | Critical topics only (`tenant.commands`, `tenant.fills`, `tenant.events`) |
| `max.in.flight.requests.per.connection` | `5` | Compatible with idempotent producer |
| `session.timeout.ms` | `45000` | Tolerates GC pauses on JVM consumers |
| `auto.offset.reset` | `earliest` for audit consumers; `latest` for hot-path consumers | Replay vs liveness |
| `isolation.level` (consumer) | `read_committed` | Excludes aborted transactions on idempotent producers |

### 12.4 Thundering-herd defenses

- **Per-(tenant, symbol) partitioning** ensures one tenant's burst can't starve others
- **Per-tenant rate limit** at APIM (e.g., 100 req/min/tenant on dashboard)
- **Bulkhead pools** in each service per upstream tenant
- **Request collapsing** on dashboard reads (cache 5s)
- **Backpressure** at consumer: pause partition assignment when local queue exceeds threshold
- **JIT warm-up** at 09:00 ET applies to **JVM-mode services only** (AI Agent Service, warm/cold pools running Spring Boot). Hot-path services (Market Data, Decision Engine, Execution) ship as **GraalVM native images** and have no JIT to warm
- **AI cost cap**: per-tenant daily ceiling prevents runaway LLM cost

---

## 13. Data Architecture

### 13.1 MS SQL (system of record for financial state)

Retention sized to **SEC Rule 17a-4** (broker-dealer record-keeping: 6 years total, first 2 years readily accessible) so Phase A data is forward-compatible with Phase B compliance. Tables backed by **WORM-eligible storage tier** for the regulated subset.

| Table | Purpose | Retention | WORM |
|---|---|---|---|
| `tenants` | tenant master | active life + 7y | no |
| `users` | user accounts (Auth0 sub claim) | active life + 7y | no |
| `tenant_configs` | per-tenant strategy params | append-only history; 7y | no |
| `alpaca_credentials` | OAuth tokens (encrypted) | active token only | no |
| `daily_state` | per-tenant per-day equity, budget, levels | 7y | no |
| `trades` | one row per Trade FSM instance | **7y** | **yes** |
| `orders` | broker orders | **7y** | **yes** |
| `positions` | open position state per (tenant, symbol, contract) | **7y** | **yes** |
| `fills` | append-only execution fills | **7y** | **yes** |
| `risk_events` | append-only budget consumption | 7y | no |
| `fsm_transitions` | every FSM transition; carries `fsm_version` for replay compatibility | 7y | yes |
| `agent_decisions` | Sentinel decisions (decision, confidence, reasoning, latency, cost) | 7y | no |

**Why MS SQL**: ACID, sync replication, point-in-time recovery, native Azure HA. Financial data must reconcile with broker; no eventual consistency.

### 13.2 MongoDB (audit + read models + raw events)

| Collection | Purpose |
|---|---|
| `bars_raw` | Raw 1-min bars (TTL 30d) |
| `signal_evaluations` | Every 15-min decision with full input snapshot (TTL 365d) |
| `audit_log.transitions` | All FSM transitions with full context |
| `audit_log.ai_calls` | Every AI call (prompt hash, model, tokens, cost, response) |
| `news_calendar` | Cached economic calendar per session |
| `projection_inputs` | Pre-aggregated per-tenant performance for projection UI |
| `notifications_log` | Delivery status per alert |
| `journal.narratives` | Trade Narrator output |
| `journal.daily_reports` | Daily Reviewer output |
| `agent_memory.short_term` | Per-tenant 30d agent memory |
| `agent_memory.long_term` | Per-tenant summarized long-term memory |

**Why Mongo**: high-volume append, schema-flexible, time-series friendly. Used for read models and audit, not writes-of-record.

### 13.3 SQLite (per-pod ephemeral state)

| Use case | Notes |
|---|---|
| Decision Engine indicator cache | Last N 2-min bars + EMA/ATR snapshots; survives pod restart for warm-up; not source of truth |
| Market Data Service bar buffer | 5-min ring buffer to absorb downstream slowness |
| AI Agent conversation working memory | Per-chat session state |
| Per-pod metrics buffer | Batched flush to App Insights |

**Strict rule**: SQLite is never the source of truth for cross-service data.

### 13.4 Data flow per persistent event

```
FSM transition → emit Kafka event → Journal Service consumes →
   ├─ MS SQL: append to fsm_transitions (synchronous, in transaction with affected entity)
   └─ Mongo: append to audit_log (asynchronous, eventually consistent)
```

If MS SQL write fails → saga retries; persistent failure → Risk FSM halts.

---

## 14. Latency Budget

**SLO** (P99, decision-emitted → order-submitted-to-Alpaca; Alpaca's broker-side fill latency is not included since we do not control it):
- Without Sentinel: ≤ **250ms**
- With Sentinel: ≤ **1.5s**

Per-hop budgets below are **P50 / max budget** (ms). Hops are not strictly serial on the wire — async I/O and overlapping commits mean the totals are not arithmetic sums of per-hop P50s. Sentinel "P50 ≈ 800ms" is a Phase 1 measurement target, not a guarantee; we will record actuals during Phase 5.

| Hop | Without Sentinel (P50 / max) | With Sentinel (P50 / max) |
|---|---|---|
| 2-min bar close → published | 50 / 100 | 50 / 100 |
| Bar consumed by Decision Engine | 5 / 10 | 5 / 10 |
| Indicator + Signal + Risk + Strike (in-process) | 10 / 20 | 10 / 20 |
| **Sentinel call (entry only — virtual thread parked)** | n/a | 800 / 30 000 |
| `commands.entry` published | 2 / 5 | 2 / 5 |
| Execution Service consumes | 5 / 10 | 5 / 10 |
| Alpaca order submit (network only — excludes broker fill) | 80 / 200 | 80 / 200 |
| **Decision-to-order-submitted (P99 SLO)** | **≤ 250** | **≤ 1500** |

**Stop-loss path is unchanged (no Sentinel) → ≤ 250ms always.** Observed gaps wider than budget → alert; persistent breach → Risk FSM halts.

**Sentinel concurrency**: veto requests await on **virtual threads** (cheap to park 30s); platform threads are not blocked, so Decision Engine throughput is preserved during simultaneous signal evaluations.

### 14.1 Latency-driving choices

- **In-process Decision Engine** — avoids 4 Kafka hops on the inner decision
- **GraalVM native image** for hot-path services — eliminates JIT warm-up cost on first trade
- **ZGC** for sub-10ms GC pauses on hot-path JVM (when not native)
- **Disruptor** for in-process queue between Indicator → Signal → Risk → Strike → Saga
- **Kafka acks=all + idempotent producer** on critical topics
- **Pinned consumer threads** per partition to avoid scheduler jitter
- **Sentinel uses Claude Haiku** for sub-second response on the binary veto decision
- **Anthropic prompt caching** for repeated context (system prompt + tool defs)

---

## 15. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Java runtime | **JDK 21 LTS** | Virtual threads, ZGC |
| Hot-path framework | **Quarkus** | Native image first-class; smaller footprint |
| Cold-path framework | **Spring Boot 3.x** | Mature ecosystem for User/Config/Projection |
| Native image | **GraalVM 23+** | Decision Engine + Execution Service |
| Reactive | **Vert.x** | Where event loop suits (Market Data) |
| Resilience | **Resilience4j** | CB, bulkhead, retry, time limiter |
| Messaging | **Apache Kafka via Strimzi** on AKS | Self-hosted, MirrorMaker for DR |
| **AI provider** | **Anthropic Claude API (direct)** | Haiku 4.5, Sonnet 4.6, Opus 4.7 |
| **AI SDK** | **Anthropic Java SDK** | Tool use, prompt caching, streaming |
| Observability | **OpenTelemetry → App Insights + Azure Monitor** | Traces, metrics, logs |
| Logging | **Structured JSON → Log Analytics** | Correlation by trace_id |
| Secrets | **Azure Key Vault** + CSI driver | Per-tenant scoped keys |
| IDP | **Auth0** | JWT, MFA, social, custom rules |
| Frontend | **Angular (latest) + Tailwind CSS** | Standalone components, signals |
| Frontend deploy | **Azure Static Web Apps** (Phase A) | Vercel as alt |
| API gateway | **Azure API Management** | JWT validation, rate limit, WAF |
| Container orchestration | **AKS** | Always-on hot-path; node-pool autoscaler |
| IaC | **Terraform** | Modular per environment |
| CI/CD | **GitHub Actions** | OIDC to Azure (no long-lived creds) |
| Backtest | **Quarkus replay harness** + offline data loader | Same code path as live |

---

## 16. Deployment Topology

### 16.1 Azure regions
- **Primary**: `eastus` (Alpaca data centers in NJ → low latency from East US)
- **DR**: `eastus2` (Phase B; cold standby in Phase A)

### 16.2 AKS layout (Phase A)

```
AKS cluster (eastus)
├─ system pool (3× D4s_v5)
│   └─ kube-system, ingress, monitoring agents
├─ hot pool (3× D8s_v5, no autoscale, anti-affinity)
│   ├─ market-data (1 pod)
│   ├─ decision-engine (1 pod)
│   └─ execution-service (1 pod)
├─ ai pool (2× D4s_v5, autoscale 1–3)
│   └─ ai-agent-service
├─ warm pool (2× D4s_v5, autoscale 2–4)
│   └─ journal, calendar, notification, projection
├─ kafka pool (3× D4s_v5, dedicated)
│   └─ Strimzi-managed Kafka (3 brokers)
└─ ops pool (1× D2s_v5)
    └─ jobs, batch ETL
```

**Hot pods** pinned to dedicated pool, no scale-to-zero, anti-affinity. **AI pool** isolated to prevent thundering-herd from chat affecting hot path.

### 16.3 Networking
- AKS in private VNET; APIM in adjacent VNET via peering
- Outbound to Alpaca / Auth0 / Anthropic / Trading Economics via Azure NAT Gateway with deterministic egress IPs (Alpaca IP allowlist)
- Private Endpoints for MS SQL, Mongo (Cosmos Mongo API), Key Vault

### 16.4 Auth flow

```
User browser ─▶ Angular (SWA) ─▶ Auth0 Universal Login ─▶ JWT
                                                              │
                                                              ▼
                                                         APIM (validates JWT,
                                                          extracts tenant_id,
                                                          forwards X-Tenant-Id)
                                                              │
                                                              ▼
                                                         BFF / cold services / AI Assistant
```

**Phase B Alpaca OAuth**:
```
Dashboard "Connect Alpaca" ─▶ Alpaca authorize URL ─▶ user grants ─▶
   callback to BFF ─▶ exchange code for refresh token ─▶ encrypt with tenant key in Key Vault ─▶
   store in MS SQL alpaca_credentials.
```

---

## 17. Resilience Patterns

### 17.1 Circuit breaker (Resilience4j)

| External dep | CB threshold | Open behavior |
|---|---|---|
| Alpaca WS | 3 errors / 30s | Fall back to REST polling; persists > 5min → halt |
| Alpaca REST | 3 errors / 30s | Halt new orders; alert |
| Anthropic API | 5 errors / 60s | Sentinel default ALLOW; Narrator/Reviewer skip |
| Auth0 | 5 errors / 60s | Reject new logins; existing JWTs continue |
| MS SQL | 2 errors / 10s | Fail-closed: halt all trading |
| Mongo | 5 errors / 30s | Continue (audit not critical-path); replay buffer |
| Kafka | 3 errors / 15s | Fail-closed: halt |

### 17.2 Bulkhead (thread-pool isolation)
- **Per-tenant pools** in cold services
- **Per-downstream pools** in hot services: Alpaca-WS-bound ≠ Alpaca-REST-bound ≠ DB-bound ≠ Anthropic-bound

### 17.3 Idempotency
- Alpaca orders: `client_order_id = sha256(tenant_id|trade_id|action)`
- Kafka producers: `enable.idempotence=true`, `acks=all`
- DB writes: idempotency key in metadata; UPSERT semantics
- AI calls: deduplicated by `(role, signal_id, prompt_hash)` for 60s

### 17.4 Retry policy
- **Default**: exp backoff 200ms → 2s, max 3 attempts, 100ms jitter
- **Order placement**: NO retry on entry (signal time-sensitive); retry on exit up to 3×
- **Audit writes**: retry forever with bounded queue + DLQ
- **AI calls**: retry once on transient errors; no retry on cost-cap or content-policy errors

### 17.5 DLQ + replay
- Every consumer wraps message handling in try/catch → DLQ on poison
- Daily ops job inspects DLQ; replay tooling provided

---

## 18. Observability

### 18.1 Metrics (Micrometer → App Insights)
- Hot-path latency histograms per hop
- FSM transition counts per state per minute
- Risk budget consumption per tenant
- CB state per dependency
- Kafka consumer lag per topic
- Alpaca WS reconnect count
- Alpaca order success/failure rates
- **AI metrics**: cost per tenant, latency P50/P99, veto rate, cache hit ratio, cost-cap breach count

### 18.2 Tracing (OpenTelemetry → App Insights)
- One trace per session (`session_id`)
- One span per signal evaluation (includes Sentinel call sub-span)
- One span per saga step
- One span per AI call (with model, tokens, cost as attributes)
- Correlation: `trace_id` propagated through Kafka headers + Anthropic metadata

### 18.3 Logging
- Structured JSON, ET timestamps, `tenant_id` and `trace_id` on every line
- Levels: DEBUG (dev), INFO (prod hot-path summary), WARN (CB transitions), ERROR (human review needed)
- Append-only; ingested to Log Analytics with 90-day retention

### 18.4 Alerts (App Insights)

| Trigger | Severity | Channel |
|---|---|---|
| Risk FSM HALTED | P1 | SMS + email |
| Hot-path latency P99 > 500ms (excl. Sentinel) | P2 | Email |
| Alpaca WS CB open | P1 | SMS + email |
| Alpaca REST CB open | P0 | SMS + email |
| Anthropic CB open | P3 | email |
| Mongo CB open | P3 | email |
| Order fill timeout | P1 | SMS + email |
| EOD flatten failure | P0 | SMS + email |
| AI cost cap breached | P3 | email |
| Sentinel veto rate anomaly (>20% or =0% over 10 sessions) | P2 | email |

---

## 19. CI/CD (GitHub Actions)

### 19.1 Pipelines
1. **PR pipeline**: lint, build, unit tests, container scan, Terraform plan
2. **Main pipeline**: build native images, integration tests, push to ACR, deploy to dev AKS via ArgoCD
3. **Release pipeline**: tag-triggered, deploy to staging, replay parity tests, manual approval, deploy to prod
4. **IaC pipeline**: Terraform apply on `infra/` changes, OIDC-authenticated to Azure

### 19.2 Test layers
- **Unit**: per service, > 80% coverage on business logic
- **Replay**: Decision Engine ↔ Execution replayed against recorded session — must match expected fills bar-for-bar
- **AI replay**: deterministic-mode AI tests with mocked Anthropic responses (recorded fixtures)
- **Integration**: dockerized Kafka + Mongo + MS SQL + mocked Anthropic; saga end-to-end
- **Chaos**: scripted process kill mid-trade; verify clean resume
- **Soak**: 5-day continuous paper run; verify no leaks, no missed EOD flatten

### 19.3 Environments

Phase A operates **dev + prod** only. The `stage` environment skeleton stays in
`infra/environments/stage/` for Phase B activation; it is **not** in the active
CI matrix or `setup-github-environments.sh` until Phase B prep.

| Env | Purpose | Cluster | Data |
|---|---|---|---|
| `dev` | feature dev + per-phase soak | dedicated AKS small | Alpaca paper (data + execution) + live Anthropic (per phase rollout) |
| `prod` | live trading (Phase A: owner-only) | dedicated AKS small | Alpaca live (data + execution) + live Anthropic |
| `stage` *(Phase B only — dormant in Phase A)* | pre-prod | dedicated | Alpaca paper (data + execution) + live Anthropic |

---

## 20. Compliance Posture (CRITICAL)

| Concern | Phase A | Phase B |
|---|---|---|
| **Public access** | Off (private VPN / IP allowlist) | On (after legal sign-off) |
| **Customers** | None — owner only | Yes (legal sign-off required) |
| **Custody of customer funds** | N/A | **Never** — Alpaca custody via per-user OAuth |
| **RIA registration** | N/A | **Required investigation by legal** |
| **Broker-dealer** | N/A | **Required investigation** |
| **State Blue Sky** | N/A | **Required investigation** per state |
| **Privacy / KYC** | N/A | Required (Auth0 KYC integration) |
| **Risk disclosure** | N/A | Mandatory ToS + risk disclaimer |
| **Audit retention** | Per §13.1 — financial tables (orders/fills/trades/positions/fsm_transitions) sized at 7y from day 1 to be forward-compatible with broker-dealer requirements; ops logs at 90d | Same; SEC Rule 17a-4 WORM storage activated for regulated subset |
| **SOC 2** | N/A | Type I baseline before launch |
| **AI explainability (FINRA bulletins)** | N/A (private) | Logged reasoning + user-facing "why?" |
| **AI as advice vs. execution** | Strategy executor only | ToS clarifies: agent operates user's chosen strategy, does not advise |

**Phase B is feature-flagged off in code and infra** until all of the above are addressed.

---

## 21. Build Phase Plan

Each phase has **two gates** that both must clear before the next phase can
begin merging work:

- **Code gate** — replay parity, unit tests, integration tests (per phase row)
- **Production-readiness gate** — Azure dev deployment + ≥5 RTH session soak
  against the phase's live external dependencies + observability + alerts +
  runbook (per §21.1)

The two gates are STRICT: Phase N+1 PRs are blocked from merging until Phase N
clears both. This avoids the "deploy at the end" failure mode where local-only
code surprises us in production.

| Phase | Deliverable | Code exit gate | Soak gate (per §21.1) |
|---|---|---|---|
| **0 — Scaffolding** | Multi-module Gradle, Terraform skeleton, GitHub Actions, Auth0 dev tenant, AKS dev cluster, MS SQL + Mongo + Strimzi Kafka provisioned | `make ci` green; empty containers deploy to dev AKS | Infrastructure provisioned in dev; CI/CD pipeline executes end-to-end on a no-op service; Key Vault + App Insights wired |
| **1 — Data layer** | Market Data Service + bar aggregator + Level Calculator + Indicator Engine; replay harness | Replay parity ≥ 99% on 30 sessions | Live Alpaca paid tier; 5 RTH session soak; metrics + 5 alerts + runbook |
| **2 — Decision Engine** | Signal + Risk + Strike + Trade Saga + all FSMs (no Sentinel yet) | Hand-labeled days produce expected signals; FSM transitions persisted | 5 RTH sessions on live Alpaca: signals fire on real data, hand-reviewed; Risk FSM transitions logged; saga step latencies under SLO |
| **3 — Execution (paper)** | Execution Service + Alpaca paper + stop watcher + trailing manager + EOD flatten | 5+ paper sessions match replay within ±2% | 5 RTH sessions of live paper trading: orders placed, stops fire correctly, EOD flatten reliable, no fills mismatched |
| **4 — AI Agent (Narrator + Reviewer)** | AI Agent Service skeleton; Trade Narrator; Daily Reviewer | Narratives appear in journal; daily reports generated; cost cap honored | 5 RTH sessions: real Anthropic calls; cost tracked; narratives reviewed for advice-vs-execution framing; Reviewer reports landing nightly |
| **5 — AI Agent (Sentinel + Assistant)** | Pre-Trade Sentinel with veto wired into Trade Saga; Conversational Assistant with read-only tools | Sentinel false-veto rate < 5%; Assistant answers 10 canonical questions correctly; **AI replay parity**: same recorded inputs produce identical Sentinel decisions across runs (deterministic via `temperature=0` + tool-use `tool_choice="tool"` forcing) | 5 RTH sessions: Sentinel veto loop on live signals; Assistant chat exercised by owner; veto-rate anomaly alert quiet |
| **6 — Journal & State + Cold path + Frontend** | Journal Service + User/Config + Projection + Calendar + Notification + Angular dashboard | API gateway live; dashboard reads positions and AI chat | 5 RTH sessions: dashboard live; Auth0 login working; positions / journal / chat all rendering against live data |
| **7 — Resilience hardening** | CB + bulkhead + idempotency tests; chaos kill-test; reconnect logic; DLQ + replay tooling; AI fallback paths verified | Chaos test passes: kill mid-trade, resume cleanly; AI degrades gracefully | 5 RTH sessions with chaos injection: process kills, network partitions, AI rate limits — all recover cleanly |
| **8 — Phase A live** | Switch Alpaca paper → live with owner account; small position sizes; Sentinel veto on conservative threshold | First 5 live sessions reviewed daily; no surprises beyond paper soak | (Phase 8 IS the soak — 5 live sessions = the gate) |
| **9 — Phase A soak** | 60+ sessions live trading with logging; AI cost trends reviewed weekly | Documented edge over 60 sessions; AI cost predictable; ready to consider Phase B | n/a (this phase IS production operation) |
| **10 — Phase B prep (gated on legal)** | Legal review; Auth0 user flows; Alpaca per-user OAuth; billing; AI suggestion tools | Legal sign-off; SOC 2 baseline; ToS published | Stage environment activated; multi-tenant integration tests pass |
| **11 — Phase B launch** | Multi-tenant unlock + Operations Agent | Production launch | Production launch with paying customers |

**No phase merges to main without:**
1. All acceptance tests green (incl. AI replay)
2. Replay parity preserved
3. Architecture decision record (ADR) updated for any deviation
4. **Soak gate (§21.1) passed for the phase being merged out of**

## 21.1 Per-phase production-readiness gate

Every phase ships to Azure dev and runs against the live external dependencies
it introduced. A phase is not done until:

- ✅ **Deployed to Azure dev** via Terraform-provisioned AKS + Key Vault + App Insights + outbound NAT
- ✅ **Soaked for ≥ 5 RTH sessions** with the phase's live external dependency
- ✅ **Observability shipped** — metrics, traces, structured logs flowing to App Insights
- ✅ **Alerts active** — see per-phase alert table below
- ✅ **Runbook published** — `docs/runbooks/phase-N.md` covers restart, log access, common diagnoses, escalation
- ✅ **No P0/P1 incident during soak** that wasn't immediately recovered

This is a **STRICT** gate: Phase N+1 PRs cannot merge to main until Phase N
clears both code and soak gates. Use feature branches normally, but the merge
button is held until soak is green.

### Live external dependencies introduced per phase

| Phase | Live external dep introduced |
|---|---|
| 0 | Azure (AKS, Key Vault, App Insights) |
| 1 | Alpaca SIP stocks WS + REST (paper account, paid Algo Trader Plus) |
| 2 | Alpaca options chain via REST (`/v1beta1/options/...`) |
| 3 | Alpaca Trading API for paper order placement |
| 4 | Anthropic API (Narrator + Reviewer) |
| 5 | (Sentinel + Assistant use same Anthropic) |
| 6 | Auth0 |
| 7 | Chaos engineering against all of the above |
| 8 | Alpaca live (key swap; data + execution stay on same provider) |
| 10 | Stripe (Phase B prep) |

### Alerts to ship per phase (cumulative)

| Phase | New alerts |
|---|---|
| 1 | (a) no SPY ticks for 5 min during RTH (b) Alpaca WS CB UNHEALTHY (c) bar emission lag P99 > 100ms (d) market-data-service container restart (e) ATR(14) stale > 1 trading day |
| 2 | (a) signal evaluation latency P99 > 50ms (b) Risk FSM HALTED (c) saga step timeout |
| 3 | (a) Alpaca CB UNHEALTHY (b) order fill timeout (c) EOD flatten failure (d) trail manager NBBO snapshot stale |
| 4 | (a) Anthropic CB UNHEALTHY (b) AI cost cap breached (c) narrator queue depth > 50 |
| 5 | (a) Sentinel veto-rate anomaly (>20% or =0% over 10 sessions) (b) Sentinel timeout > 30s |
| 6 | (a) Auth0 CB UNHEALTHY (b) APIM rate limit hits (c) dashboard latency P99 > 500ms |
| 7 | (chaos test pass) |

### Runbook content baseline

Every `docs/runbooks/phase-N.md` covers:
- How to deploy / redeploy this service to dev
- How to access logs (App Insights query, kubectl logs)
- How to restart a pod
- 3+ common failure modes and diagnoses
- Escalation path (who/how to alert; for Phase A: just "owner via SMS")

### Soak failure handling

If a phase soak surfaces a P0/P1 issue:
1. Open a `bug` issue tagged with the phase milestone
2. Block phase exit until fixed
3. Restart soak counter — must complete a fresh 5 sessions clean

Minor issues (P2/P3) are tracked but don't reset the counter.

---

## 22. Open Implementation Decisions (deferred to relevant build phase)

These do not block sign-off:
1. Liquidity gate exact threshold for option spread (default `≤ $0.10 OR ≤ 5%`)
2. Trail-watcher tick frequency (NBBO tick vs 15s snapshot)
3. Reconciliation tolerance between replay and paper (default ±2%)
4. Backtest data window (default trailing 12 months SPY 0DTE)
5. SOC 2 auditor selection (Phase B prep)
6. Auth0 social-login providers enabled (Phase B prep)
7. Billing platform (Stripe likely; Phase B prep)
8. DR strategy depth (Phase A: cold standby; Phase B: warm with RPO ≤ 5min)
9. Sentinel veto confidence threshold tuning (default 0.85; tune from Phase 8 data)
10. Reviewer's config-tweak proposals — Phase A advisory-only; Phase B may auto-apply for trusted users
11. Anthropic prompt template versioning + A/B test framework

---

## 23. Out of Scope for v1 (Phase A)

- Multi-instrument fan-out (SPX, QQQ, IWM)
- Multi-leg options (verticals, condors)
- Pyramiding / averaging in
- Discretionary override UI
- Manual paper-only mode (live execution is the product)
- Scalping mode (separate playbook per SME)
- Multi-tenant operations (code is ready, ops is not)
- Marketing / customer onboarding
- Operations Agent (Phase B only)
- AI suggestion tools that auto-apply (Phase B with user approval gate)
- Cross-tenant pattern memory (Phase B only)
