---
name: fsm-discipline
description: Rules for state-machine implementation. Use when writing or reviewing code that manages session, trade, risk, order, position, or connection state. Triggers on FSM, state machine, Session FSM, Trade FSM, Risk FSM, Order FSM, Position FSM, Connection FSM, transition, lifecycle.
---

# FSM Discipline

State changes only via explicit FSM transitions.

## Rules

1. Every lifecycle uses an explicit FSM (Session, Risk, Trade, Order, Position, Connection). No ad-hoc boolean flags.
2. Each FSM has a single owner service. Only that service writes transitions:
   - **Session FSM** → Decision Engine
   - **Risk FSM** → Risk Manager (in Decision Engine)
   - **Trade FSM** → Trade Saga Orchestrator (in Decision Engine)
   - **Order FSM** → Execution Service
   - **Position FSM** → Execution Service
   - **Connection FSM** → owning service per dependency
3. Every transition is persisted to MS SQL `fsm_transitions` BEFORE side effects fire.
4. Transitions are idempotent: replaying the same transition is a no-op.
5. Invalid transitions `(current_state, event)` → throw `IllegalFsmTransitionException`. Do not silently ignore.
6. Recovery on restart: load latest transition; resume from there. No "in-flight" state lost.
7. FSM definitions live in code (enums + transition tables), not configuration.

## Pattern

```java
public enum TradeState { SIGNAL_EMITTED, APPROVED, SENTINEL_PENDING, /* ... */ }

public final class TradeFsm {
    private static final Map<Pair<TradeState, TradeEvent>, TradeState> TRANSITIONS = /* ... */;

    public TradeState transition(TradeState current, TradeEvent event) {
        return Optional.ofNullable(TRANSITIONS.get(Pair.of(current, event)))
            .orElseThrow(() -> new IllegalFsmTransitionException(current, event));
    }
}
```

Persistence wrapper:

```java
@Transactional
public TradeState applyTransition(UUID tradeId, TradeEvent event) {
    TradeState current = repo.loadCurrentState(tradeId);
    TradeState next = fsm.transition(current, event);
    repo.recordTransition(tradeId, current, next, event, clock.instant());
    return next;
}
```

## Anti-patterns to flag

- `if (trade.isFilled() && !trade.isClosed()) ...` ← derive state from FSM, don't compose flags
- Skipping the persistence step for "performance"
- Multiple writers to the same FSM
- Transition logic spread across multiple files
- Using booleans like `isHalted`, `isFlattened` instead of an FSM state
