# ADR-0005: Execution-service stop watcher and trailing manager design

**Status**: accepted
**Date**: 2026-05-02
**Deciders**: owner
**Supersedes**: closes `architecture-spec.md` v2.4 §22 #2 (trail-watcher tick frequency placeholder)

## Context

Phase 3 saga steps S4 (Stop Watcher) and S5 (Trailing Manager) execute the §9 stop-loss and §10 trailing-stop rules from `requirements.md`. `architecture-spec.md` §9.4 places both watchers inside the **execution-service**:

> **Stop Watcher**: subscribes to `market.bars.2m`; evaluates §9 stop trigger
> **Trailing Manager**: subscribes to held option's NBBO; tracks trailing floor per §10 of req. **Trail evaluation uses last NBBO mid** (not bid or ask). Ratchet update requires a *sustained* move — exact "sustained" definition deferred to Phase 3 build (per §22 #2); default placeholder: NBBO mid above current floor for ≥ 3 consecutive 1-second snapshots

Two open design questions had to be resolved before implementation:

1. **Where do indicator values (`EMA13`, `EMA48`, `ATR(14)`) come from for the Stop Watcher?** The §9 trigger requires them, but `market.bars.2m` payloads carry only OHLCV — no indicators. The market-data-service already computes them in its `IndicatorEngine` (`services/market-data-service/src/main/java/com/levelsweep/marketdata/indicators/IndicatorEngine.java`) for the bar-aggregator pipeline; decision-engine's `IndicatorSnapshotHolder` is currently a placeholder cache with no producer. Recomputing indicators in execution-service would duplicate logic and break the replay-parity contract by allowing the implementations to drift.

2. **What does "sustained" mean for the trailing-stop ratchet, activation, and exit?** Spec §22 #2 defaulted to "NBBO mid above current floor for ≥ 3 consecutive 1-second snapshots" but left the rule open until Phase 3.

The replay-parity contract (CLAUDE.md guardrail #5) demands a single deterministic source of truth for indicators. The fail-closed contract (#3) demands that watcher trigger evaluation be conservative — a noisy single-tick should not fire either ratchet or exit.

## Decision

### 1. Indicator distribution: dedicated Kafka topic `market.indicators.2m`

**market-data-service** is the single computer of indicator values — its `IndicatorEngine` computes EMA13/EMA48/EMA200/ATR14 per bar close. After each 2-min bar close, market-data-service publishes the resulting `IndicatorSnapshot` to a new Kafka topic `market.indicators.2m`, keyed by `symbol`, retention 7d, partitions 8 (mirrors `market.bars.2m`).

Subscribers:
- **decision-engine** consumes the topic and writes to its in-memory `IndicatorSnapshotHolder` (until the precursor PR an empty placeholder); SignalEvaluator can finally see live indicator values rather than skipping bars with reason `"no_indicators"`. This unblocks live signal evaluation in dev — previously hand-built fixtures were the only path.
- **execution-service** consumes for stop watcher (S4) + trail manager (S5).

Execution-service joins `market.bars.2m` and `market.indicators.2m` by `(symbol, timestamp)` with a small in-memory joiner that holds at most one bar plus one indicator snapshot per symbol. The join window is one bar; if the indicator hasn't arrived within 5 seconds of the bar, the bar is dropped from evaluation (logged as `stop.watcher.indicator_skew`) and no stop trigger fires for that bar — fail-closed.

Spec amendment: add `market.indicators.2m` row to `architecture-spec.md` §12.1. `BarEmitter.java`'s comment "Indicators and levels are NOT published in this PR" is now obsolete and should be updated by the precursor PR.

**Precursor PR (lands first, separate from S4-S5 stack — branch off main):** market-data-service `IndicatorSnapshotEmitter` (mirrors `BarEmitter` pattern) + decision-engine `IndicatorSnapshotConsumer` (mirrors `BarConsumer` pattern) + `IndicatorSnapshotDeserializer`. S4-S5 layers the execution-service consumer on top.

### 2. Sustainment rule: 3 consecutive 1-second snapshots, config-tunable

The default sustainment count is **3 consecutive 1-second snapshots** for all three trailing-manager state transitions:

- **Activation** (`INACTIVE → ARMED` at +30% UPL)
- **Ratchet** (raise floor when UPL crosses next +5% threshold)
- **Exit** (`ARMED → EXIT_TRIGGERED` when NBBO mid falls to floor)

The count is exposed as `levelsweep.trail.sustainment-snapshots` (default 3, range 1–10). Phase 3 soak data may re-tune; Phase 8 production locks the value.

A single noisy snapshot — including activation — never advances the state machine. Floor monotonicity (`requirements.md §10.2`) is preserved at all times: once `ARMED`, the floor strictly never decreases regardless of UPL retracement.

### 3. Watcher placement remains in execution-service (per spec §9.4, not re-litigated)

Decision-engine emits indicator snapshots and waits on saga step 7 (manage trade) for one of the watcher events. Execution-service owns the watchers, the Alpaca exit-order submission, and the audit tables. The boundary stays where the spec puts it.

### 4. Single exit path via `ExitOrderRouter`

Both Stop Watcher and Trailing Manager publish CDI events (`TradeStopTriggered`, `TradeTrailBreached`) consumed by a single `ExitOrderRouter`. The router is the only component that submits exit orders to Alpaca via the existing S2 `AlpacaTradingClient`. Deterministic `clientOrderId = "<tenantId>:<tradeId>:exit"` — Alpaca rejects duplicates (422) so even a hypothetical simultaneous stop+trail trigger results in exactly one exit order. Single-attempt exit (consistent with §17.4 entry semantics — re-submitting an in-flight market exit risks double-exit).

### 5. NBBO polling cadence stays at 1s REST (per ADR-0004)

ADR-0004 already locked Alpaca REST polling at 1s for option NBBO. ADR-0005 honors that: `TrailPollScheduler` runs `@Scheduled(every="1s")` against `/v1beta1/options/snapshots/{contractSymbol}`. OPRA WS upgrade remains deferred per ADR-0004 §Negative.

### 6. Persistence: append-only audit tables in MS SQL

New Flyway migrations:
- `V202__create_stop_breach_audit.sql` — every stop trigger evaluation that fires
- `V203__create_trail_audit.sql` — every ratchet event AND every exit trigger

Audit rows are the basis for replay-parity assertions: a recorded session's audit trail must reproduce byte-for-byte under the replay harness.

## Consequences

### Positive

- **Single indicator implementation** preserved. Replay parity contract holds.
- **Fail-closed on bar/indicator skew**: a missing indicator never silently fires a spurious exit.
- **Deterministic exit path**: same trade, same input → same exit order, same `clientOrderId`. Replay test asserts byte-equality.
- **Sustainment is config-tunable**: soak data drives the production value rather than locking a guess.
- **Single Alpaca writer (`AlpacaTradingClient`)**: every order — entry, stop exit, trail exit, EOD flatten — flows through one component. One place to add CB / metrics / DLQ.
- **Audit-first persistence**: replay harness compares against the audit tables; future incident reviews have a complete trace.

### Negative

- **New Kafka topic** (`market.indicators.2m`) adds an additive operational concern — alerting + monitoring. Mitigated by: same retention/partition shape as `market.bars.2m`, same publisher pattern as decision-engine's `TradeProposedKafkaPublisher`, no new Strimzi config beyond a topic CRD.
- **Bar/indicator join introduces a 5-second tolerance window** — a real production indicator straggler longer than that drops the bar from stop evaluation. Acceptable: §9 trigger fires on the *next* bar instead. Logged as a metric so operators can detect chronic skew.
- **REST polling still 1s** (per ADR-0004). 0DTE option premium can move several percent in a fast market within one second; sustainment-3 means the ratchet/exit responds 3 seconds after the NBBO move. Acceptable for §10 rules; soak data may push for sustainment-1 + WS upgrade if missed ratchets become a problem.
- **CDI events fan out** to multiple listeners (`ExitOrderRouter`, audit repos, future narrator). CDI's synchronous fire is safe at the volume of held positions Phase A sees (≤ 1 SPY 0DTE trade at a time per CLAUDE.md scope), but Phase B multi-tenant scaling should re-evaluate (likely move to async via `@ObservesAsync`).

## Alternatives Considered

- **Execution-service computes its own EMAs from `market.bars.2m`** — rejected. Duplicates decision-engine logic, breaks replay-parity (two implementations drift), violates DRY without compensating benefit.
- **Decision-engine evaluates the §9 stop trigger and publishes `tenant.events.stop_triggered`; execution-service just submits the exit** — rejected. Blurs the boundary set by spec §9.4 ("Stop Watcher … in execution-service"). Decision-engine becomes "deciding when to exit," not just "deciding when to enter." Future-Phase-B implications: tenant-specific stop overrides become harder to localize.
- **Sustainment-1 (instant)** — rejected for ratchet and exit (noise on a single 1s NBBO snapshot is real on 0DTE). Rejected for activation as well, for consistency: a single +30% spike followed by a retrace would otherwise leave a floor armed at +25% with no genuine profit cushion.
- **Sustainment-N where N > 3** — rejected as default. 3 snapshots = 3 seconds, balancing noise robustness against responsiveness on a 0DTE clock. Soak can tune up if false ratchets are observed.
- **Two separate PRs (`feat/p3-stop-watcher` + `feat/p3-trail-manager`)** — rejected. S4 and S5 share `ExitOrderRouter`, the audit-pattern, and the watcher-arm-on-`TradeFilled` plumbing. Splitting them creates a stacked PR with awkward dependency direction. One bundled PR is cleaner.
- **Bundle the indicator publisher into the S4-S5 PR** — rejected. The publisher (in market-data-service) and consumer (in decision-engine) are a small, additive, isolated change that lands cleanly on its own and unlocks live signal evaluation in dev as a side effect. Precursor PR keeps S4-S5 review focused on watcher logic.

## References

- `requirements.md` §9 (Stop-Loss), §10 (Profit Target & Trailing Stop)
- `architecture-spec.md` v2.4 §9.4 (Execution Service), §11 (Trade Saga step 7), §12.1 (Topic naming — amended), §17.4 (No-retry on order paths), §22 #2 (now closed by this ADR)
- ADR-0004 (Alpaca as single market-data + execution provider — REST polling cadence)
- `services/market-data-service/src/main/java/com/levelsweep/marketdata/indicators/IndicatorEngine.java` (the producer of IndicatorSnapshot)
- `services/market-data-service/src/main/java/com/levelsweep/marketdata/messaging/BarEmitter.java` (mirrored pattern for the precursor PR's IndicatorSnapshotEmitter)
- `services/decision-engine/src/main/java/com/levelsweep/decision/saga/TradeProposedKafkaPublisher.java` (mirrored pattern; note path is `decision/saga/`, not `decision/messaging/`)
- `services/execution-service/src/main/java/com/levelsweep/execution/alpaca/AlpacaTradingClient.java` (single Alpaca writer — Phase 3 S2)
