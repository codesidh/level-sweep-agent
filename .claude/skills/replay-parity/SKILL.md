---
name: replay-parity
description: Rules for preserving determinism in the Decision Engine. Use when writing or reviewing changes to indicators, signal logic, risk rules, strike selection, or saga ordering. Triggers on indicator, EMA, ATR, signal, risk, strike, decision engine, replay.
---

# Replay Parity

The Decision Engine must be deterministic. The replay harness is the contract.

## Rules

1. **Any change to indicator computation, signal evaluation, risk rules, strike selection, or saga ordering** requires `./gradlew replayTest` green at ≥ 99% parity on 30 historical sessions.
2. **Replay harness uses recorded data only.** No live calls.
3. **Tolerances are documented**: P&L within ±2%, fill prices match, stop triggers match.
4. **Determinism = no wall-clock dependencies in pure logic.** Always inject `Clock` for time. Never call `Instant.now()` directly in business logic.
5. **No randomness in business logic.** If randomness is required (e.g., jitter), seed it deterministically per replay.
6. **External calls in replay = mocked.** Anthropic and Alpaca (SIP WS, options REST, Trading API) all replaced by recorded fixtures.
7. **Snapshot recording**: when a new edge case is found in production, record it and add to the replay set.
8. **Failing replay blocks merge.** No exceptions.

## Pattern

```java
public class IndicatorEngine {
    private final Clock clock;        // injected; in replay = fixed
    private final Random random;      // seeded; deterministic

    public IndicatorSnapshot compute(Bar bar, IndicatorState state) {
        // pure function of bar + state; no Instant.now()
        return new IndicatorSnapshot(
            ema(state.ema13(), bar.close(), 13),
            ema(state.ema48(), bar.close(), 48),
            ema(state.ema200(), bar.close(), 200),
            atr(state.atr(), bar)
        );
    }
}
```

## Adding a new replay session

1. Record live session as Alpaca SIP WS frames + Alpaca fills + Anthropic responses
2. Drop into `src/test/resources/replay/{session-date}/`
3. Add expected outcome to `expected.json`
4. Run `./gradlew replayTest --tests *.NewSessionReplayTest`
5. If parity ≥ 99%, commit. If not, the session reveals a bug — fix before merging anything else.

## Schema evolution rules (FSM enums + tables)

Any change to FSM state enums or `fsm_transitions` schema must remain backward-compatible with recorded replay sessions:

- New states/events: append-only ordinals; never reuse a removed ordinal.
- Renaming a state/event requires a one-time migration script and full replay regeneration; bump `fsm_version` on every transition row.
- Removing a state requires a deprecation period: states stay in code as `@Deprecated` until no replay session references them.
- All `fsm_transitions` rows include `fsm_version` (current); replay harness skips sessions whose `fsm_version < min_supported`.

## Anti-patterns to flag

- `Instant.now()` in indicator/signal/risk/strike code
- `Math.random()` or `new Random()` without seed
- Live external calls in test code
- Skipping replay test "because it's a small change"
- Tolerances loosened to make a test pass
- Non-deterministic ordering (use sorted collections in business logic)
- Reusing an ordinal of a removed FSM state
- DST-naive time math (`LocalDateTime.plus(...)` across DST boundaries — use `ZonedDateTime` with `America/New_York`)
