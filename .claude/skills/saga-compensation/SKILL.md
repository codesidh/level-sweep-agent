---
name: saga-compensation
description: Rules for orchestrated saga steps. Use when writing or reviewing the Trade Saga Orchestrator or any saga-style workflow. Triggers on saga, compensation, rollback, orchestrator, orchestration.
---

# Saga Compensation

Every forward step has an explicit compensation.

## Rules

1. Every saga step is a `(forward, compensation)` pair. Compensation may be a no-op but must be declared.
2. Saga state persists to MS SQL on every step transition before the next step fires.
3. Idempotency: replaying a forward step or compensation is safe.
4. Compensations run in reverse order on partial failure.
5. Compensation failures are alerted, not silently dropped.
6. Timeouts on every step that involves another service or external call (default 30s; configurable per step).
7. Recovery on restart: load all in-flight saga instances; resume from last persisted step.

## Saga step contract

```java
public interface SagaStep<C extends SagaContext> {
    String name();
    Duration timeout();
    StepResult forward(C ctx);
    void compensate(C ctx);
}
```

## Trade Saga compensations (reference)

See `architecture-spec.md` §11. Highlights:

| Forward step | Compensation if it fails |
|---|---|
| Sentinel veto request | Timeout/error → default ALLOW; alert |
| Submit entry order | Idempotent — duplicates rejected by Alpaca |
| Wait for fill | Timeout → publish `commands.cancel_entry` |
| Register watchers | Failure → publish `commands.emergency_flatten` |
| Submit exit order | Retry up to 3× then alert |
| Wait for exit fill | Timeout → escalate alert + manual ops review |
| Settle P&L | Persistent failure → halt new trades for the day |

## Anti-patterns to flag

- Forward step without compensation declared (even a no-op)
- Saga state held only in memory
- Synchronous wait on external service without timeout
- Compensation that itself can fail without alerting
- Composite forward step that can't be re-run safely
- Sleeping in a saga step instead of yielding to scheduler
