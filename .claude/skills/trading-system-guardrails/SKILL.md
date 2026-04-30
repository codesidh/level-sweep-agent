---
name: trading-system-guardrails
description: Mandatory rules for any code that touches trading logic, broker calls, audit trail, credentials, or money. Use when reviewing or writing code in Decision Engine, Execution Service, Risk Manager, or anything that creates orders, persists trades, or handles Alpaca/Auth0 secrets. Triggers on terms "trade", "order", "broker", "credential", "secret", "audit", "Alpaca", or any code in execution-service / decision-engine / journal-service modules.
---

# Trading System Guardrails

Non-negotiable rules for the order path.

## MUST

1. **Idempotency**: every external call (Alpaca, DB write, Kafka produce) carries a deterministic key. Use `sha256(tenant_id|trade_id|action)` for orders.
2. **Audit before action**: every FSM transition and trade decision is persisted to MS SQL `fsm_transitions` BEFORE the next step proceeds.
3. **Fail-closed**: any unhandled exception in the order path → emit `events.system_halt`, do not retry, alert.
4. **Tenant scoping**: every query, every Kafka publish, every tool invocation includes `tenant_id`. No queries without `WHERE tenant_id = ?`.
5. **Replay parity**: any change to indicator computation, signal logic, risk rules, or strike selection requires `./gradlew replayTest` at ≥99% on 30 sessions before merge.
6. **Test isolation**: integration tests run against Alpaca paper or mocks. Never against live.

## MUST NOT

1. ❌ Place orders from any service other than Execution Service.
2. ❌ Mutate Trade FSM state outside the Saga Orchestrator.
3. ❌ Mutate Risk FSM state outside the Risk Manager.
4. ❌ Log Alpaca tokens, OAuth secrets, or any value retrieved from Key Vault. Redact at log level.
5. ❌ Hard-code position sizes, risk thresholds, or strategy parameters. They live in `tenant_config` (per-tenant) or as env-driven defaults.
6. ❌ Skip the daily risk-budget check. Even retries must re-validate budget.
7. ❌ Suppress exceptions in the order path. Bubble up to the saga, which decides compensation.
8. ❌ Use real money in tests. `ALPACA_BASE_URL` must point to paper in dev/stage.
9. ❌ Bypass the EOD flatten. 15:55 ET force-flatten is mandatory.
10. ❌ Touch user money. Alpaca custodies; we orchestrate.

## When you see code that violates these

Stop. Flag it. Propose the fix. Reference this skill in the comment.
