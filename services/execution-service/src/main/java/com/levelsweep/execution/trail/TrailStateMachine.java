package com.levelsweep.execution.trail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure stateless advancer for the §10 trailing-stop FSM.
 *
 * <p>State transitions (per ADR-0005 §2 and {@code requirements.md} §10):
 *
 * <ul>
 *   <li>{@code INACTIVE} — no floor armed; UPL has not yet sustained ≥
 *       {@link TrailConfig#activationUplPct()} for {@link
 *       TrailConfig#sustainmentSnapshots()} consecutive snapshots.
 *   <li>{@code ARMED} — UPL sustained the activation threshold; floor set
 *       to {@code activation - ratchetStep} (+25% on the +30% activation).
 *       Each additional sustained {@code ratchetStep} crossing raises the
 *       floor by {@code ratchetStep} (§10.2).
 *   <li>{@code EXIT_TRIGGERED} — NBBO mid retraced to (or below) the floor
 *       for the sustainment window; the trade exits at market.
 * </ul>
 *
 * <p>Sustainment-3 (config-tunable) means a single noisy snapshot — including
 * activation — never advances the FSM. Floor monotonicity (§10.2) is
 * preserved: once armed, the floor strictly never decreases regardless of
 * UPL retracement above it.
 *
 * <p>Determinism: pure function over (TrailState, NBBO snapshot,
 * TrailConfig). The registry mutates state via the package-private setters
 * on {@link TrailState}; this class returns a {@link Decision} variant the
 * caller dispatches on.
 */
public final class TrailStateMachine {

    /** Scale used for UPL fraction comparisons — 4 decimals (basis-point precision). */
    static final int UPL_SCALE = 4;

    private TrailStateMachine() {}

    /**
     * Apply one NBBO mid observation. The returned {@link Decision} variant
     * captures whatever side-effect the caller should perform (audit row +
     * CDI event). The state object is mutated in-place — the caller does
     * not need to re-store it.
     */
    public static Decision advance(TrailState state, BigDecimal nbboMid, Instant ts, TrailConfig cfg) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(nbboMid, "nbboMid");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(cfg, "cfg");

        BigDecimal uplPct = uplPct(state.entryPremium(), nbboMid);

        if (!state.armed()) {
            // Activation path: need uplPct ≥ activationUplPct sustained for N snapshots.
            if (uplPct.compareTo(cfg.activationUplPct()) >= 0) {
                state.incAbove(nbboMid, ts);
                if (state.consecAboveCount() >= cfg.sustainmentSnapshots()) {
                    BigDecimal floor = cfg.activationUplPct().subtract(cfg.ratchetStepPct());
                    state.arm(floor, nbboMid, ts);
                    return new Decision.Armed(floor, uplPct);
                }
                return new Decision.Inactive(uplPct);
            }
            state.resetAbove(nbboMid, ts);
            return new Decision.Inactive(uplPct);
        }

        // Already armed. Two competing paths:
        //   1) UPL retraced to (or below) floor → exit (sustainment-N)
        //   2) UPL crossed the next ratchet threshold → raise floor (sustainment-N)

        // Exit takes precedence — if uplPct ≤ floor, nothing else matters.
        // Sustainment-3 means a single retrace tick does not exit; need N consec.
        if (uplPct.compareTo(state.floor()) <= 0) {
            // Reset the "above next ratchet" counter — we're below the
            // current ratchet threshold, so the ratchet path is irrelevant.
            state.incAtFloor(nbboMid, ts);
            if (state.consecAtFloorCount() >= cfg.sustainmentSnapshots()) {
                return new Decision.ExitTriggered(state.floor(), uplPct);
            }
            return new Decision.Holding(uplPct, state.floor());
        }

        // UPL above floor — recovered. Reset the at-floor counter.
        state.resetAtFloor(nbboMid, ts);

        // Compute the next ratchet target. The next floor would be at
        // floor + ratchetStep, which corresponds to a UPL threshold of
        // floor + 2 * ratchetStep (the rule jumps in pairs: +30%/+25%,
        // +35%/+30%, +40%/+35%, ...).
        BigDecimal nextUplThreshold = state.floor().add(cfg.ratchetStepPct().multiply(new BigDecimal("2")));

        if (uplPct.compareTo(nextUplThreshold) >= 0) {
            state.incAbove(nbboMid, ts);
            if (state.consecAboveCount() >= cfg.sustainmentSnapshots()) {
                BigDecimal newFloor = state.floor().add(cfg.ratchetStepPct());
                state.arm(newFloor, nbboMid, ts);
                return new Decision.Ratcheted(newFloor, uplPct);
            }
            return new Decision.Holding(uplPct, state.floor());
        }

        // UPL between floor and next ratchet threshold — neither advance nor exit.
        state.resetAbove(nbboMid, ts);
        return new Decision.Holding(uplPct, state.floor());
    }

    /**
     * UPL as a fraction of entry premium: {@code (nbboMid - entry) / entry}.
     * Single-leg long-call/long-put assumption — qty does not enter the
     * formula because the percentage is identical regardless of contract
     * count.
     */
    static BigDecimal uplPct(BigDecimal entryPremium, BigDecimal nbboMid) {
        return nbboMid.subtract(entryPremium).divide(entryPremium, UPL_SCALE, RoundingMode.HALF_UP);
    }

    /** Outcome of one {@link #advance} call. Sealed sum so the caller pattern-matches exhaustively. */
    public sealed interface Decision
            permits Decision.Inactive, Decision.Holding, Decision.Armed, Decision.Ratcheted, Decision.ExitTriggered {

        /** Current observed UPL — useful for structured logs. */
        BigDecimal uplPct();

        /** Sustainment in progress (or below activation): no audit row, no event. */
        record Inactive(BigDecimal uplPct) implements Decision {
            public Inactive {
                Objects.requireNonNull(uplPct, "uplPct");
            }
        }

        /** Armed but neither ratcheting nor exiting on this snapshot: no audit row, no event. */
        record Holding(BigDecimal uplPct, BigDecimal currentFloor) implements Decision {
            public Holding {
                Objects.requireNonNull(uplPct, "uplPct");
                Objects.requireNonNull(currentFloor, "currentFloor");
            }
        }

        /** Activation crossed for the sustainment window: write RATCHET audit row + fire TradeTrailRatcheted. */
        record Armed(BigDecimal newFloor, BigDecimal uplPct) implements Decision {
            public Armed {
                Objects.requireNonNull(newFloor, "newFloor");
                Objects.requireNonNull(uplPct, "uplPct");
            }
        }

        /** Floor advanced for the sustainment window: write RATCHET audit row + fire TradeTrailRatcheted. */
        record Ratcheted(BigDecimal newFloor, BigDecimal uplPct) implements Decision {
            public Ratcheted {
                Objects.requireNonNull(newFloor, "newFloor");
                Objects.requireNonNull(uplPct, "uplPct");
            }
        }

        /** Exit threshold crossed for the sustainment window: write EXIT audit row + fire TradeTrailBreached. */
        record ExitTriggered(BigDecimal exitFloor, BigDecimal uplPct) implements Decision {
            public ExitTriggered {
                Objects.requireNonNull(exitFloor, "exitFloor");
                Objects.requireNonNull(uplPct, "uplPct");
            }
        }
    }
}
