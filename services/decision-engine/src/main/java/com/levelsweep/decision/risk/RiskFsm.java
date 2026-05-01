package com.levelsweep.decision.risk;

import com.levelsweep.shared.domain.risk.DailyRiskState;
import com.levelsweep.shared.domain.risk.RiskEvent;
import com.levelsweep.shared.domain.risk.RiskEventType;
import com.levelsweep.shared.domain.risk.RiskState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure-logic Risk FSM per {@code requirements.md} §11. No IO, no clock
 * injection, no CDI — the constructor takes the configurable thresholds and
 * each transition method takes the current snapshot + the {@link Instant}
 * the caller has already resolved. This shape keeps the FSM trivially
 * unit-testable and replay-deterministic; the {@code RiskService} bean owns
 * the IO boundary.
 *
 * <h3>Transitions</h3>
 *
 * <ul>
 *   <li>HEALTHY → BUDGET_LOW when {@code realizedLoss >= budgetLowFraction *
 *       dailyLossBudget} (default 0.7 — operator warning threshold)
 *   <li>BUDGET_LOW → HALTED when {@code realizedLoss >= dailyLossBudget}
 *       (§11.3 hard halt)
 *   <li>HEALTHY → HALTED can fire directly if a single fill jumps loss past
 *       the full budget without lingering in BUDGET_LOW
 *   <li>* → HALTED when {@code tradesTaken >= maxTradesPerDay} (operator
 *       discipline cap; default 5 per the {@code RiskFsmFactory})
 *   <li>* → HALTED on manual halt via {@link #onHaltManual}; once HALTED
 *       the FSM stays HALTED for the session (§11.4 halt persistence)
 * </ul>
 *
 * <h3>Reset</h3>
 *
 * <p>{@link #reset} produces a fresh HEALTHY state for the new trading day.
 * Per §11.4 the FSM does not auto-recover mid-session — the 09:29 ET reset
 * is the only path back from HALTED.
 */
public final class RiskFsm {

    /** Default trade cap per day. Mirrored by {@code RiskFsmFactory}. */
    public static final int DEFAULT_MAX_TRADES_PER_DAY = 5;

    /** Default warning fraction (70% of the daily budget). */
    public static final BigDecimal DEFAULT_BUDGET_LOW_FRACTION = new BigDecimal("0.70");

    /** Result of a transition: the new state and the events fired during it. */
    public record Result(DailyRiskState newState, List<RiskEvent> events) {
        public Result {
            Objects.requireNonNull(newState, "newState");
            Objects.requireNonNull(events, "events");
            events = List.copyOf(events);
        }
    }

    private final int maxTradesPerDay;
    private final BigDecimal budgetLowFraction;

    public RiskFsm(int maxTradesPerDay, BigDecimal budgetLowFraction) {
        if (maxTradesPerDay <= 0) {
            throw new IllegalArgumentException("maxTradesPerDay must be > 0; got " + maxTradesPerDay);
        }
        Objects.requireNonNull(budgetLowFraction, "budgetLowFraction");
        if (budgetLowFraction.signum() <= 0 || budgetLowFraction.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("budgetLowFraction must be in (0, 1); got " + budgetLowFraction);
        }
        this.maxTradesPerDay = maxTradesPerDay;
        this.budgetLowFraction = budgetLowFraction;
    }

    public int maxTradesPerDay() {
        return maxTradesPerDay;
    }

    public BigDecimal budgetLowFraction() {
        return budgetLowFraction;
    }

    /**
     * Build a fresh HEALTHY state for {@code (tenantId, sessionDate)}. Emits a
     * single {@link RiskEventType#DAILY_RESET} event.
     */
    public Result reset(
            String tenantId,
            LocalDate sessionDate,
            BigDecimal startingEquity,
            BigDecimal dailyLossBudget,
            Instant now) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(startingEquity, "startingEquity");
        Objects.requireNonNull(dailyLossBudget, "dailyLossBudget");
        Objects.requireNonNull(now, "now");
        if (startingEquity.signum() < 0) {
            throw new IllegalArgumentException("startingEquity must be >= 0; got " + startingEquity);
        }
        if (dailyLossBudget.signum() < 0) {
            throw new IllegalArgumentException("dailyLossBudget must be >= 0; got " + dailyLossBudget);
        }

        DailyRiskState fresh = new DailyRiskState(
                tenantId,
                sessionDate,
                startingEquity,
                dailyLossBudget,
                BigDecimal.ZERO,
                0,
                RiskState.HEALTHY,
                Optional.empty(),
                Optional.empty());

        RiskEvent event = new RiskEvent(
                tenantId,
                sessionDate,
                now,
                RiskEventType.DAILY_RESET,
                Optional.empty(),
                Optional.of(RiskState.HEALTHY),
                Optional.empty(),
                Optional.of(BigDecimal.ZERO),
                "DAILY_RESET");

        return new Result(fresh, List.of(event));
    }

    /**
     * Apply a realized P&L delta to the FSM. {@code realizedDelta} can be
     * positive (a profitable fill, no budget consumed) or negative (a loss);
     * only the negative magnitude is added to {@code realizedLoss}. The
     * Risk FSM models loss-against-budget, not gross P&L, so wins do not
     * "earn back" budget per §11.3.
     */
    public Result onFillRealized(DailyRiskState current, BigDecimal realizedDelta, Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(realizedDelta, "realizedDelta");
        Objects.requireNonNull(now, "now");

        if (current.state() == RiskState.HALTED) {
            // HALTED is terminal for the session — accumulate the loss for the
            // record but do not transition. Caller should already have rejected
            // any new entries; this branch covers in-flight exits of pre-halt
            // open positions.
            BigDecimal lossDelta = lossMagnitudeOf(realizedDelta);
            if (lossDelta.signum() == 0) {
                return new Result(current, List.of());
            }
            BigDecimal newLoss = current.realizedLoss().add(lossDelta);
            DailyRiskState updated = withLoss(current, newLoss);
            return new Result(updated, List.of(budgetConsumedEvent(updated, lossDelta, now)));
        }

        BigDecimal lossDelta = lossMagnitudeOf(realizedDelta);
        BigDecimal newLoss = current.realizedLoss().add(lossDelta);
        DailyRiskState afterLoss = withLoss(current, newLoss);

        List<RiskEvent> events = new ArrayList<>();
        if (lossDelta.signum() > 0) {
            events.add(budgetConsumedEvent(afterLoss, lossDelta, now));
        }

        RiskState target = thresholdState(afterLoss);
        DailyRiskState finalState = afterLoss;
        if (target != current.state()) {
            finalState = transitionTo(afterLoss, target, now, haltReasonForLoss(afterLoss));
            events.add(transitionEvent(current.state(), target, finalState, now));
            if (target == RiskState.HALTED) {
                events.add(haltEvent(finalState, now, haltReasonForLoss(afterLoss)));
            }
        }

        return new Result(finalState, events);
    }

    /**
     * Mark a new trade entry. Bumps {@code tradesTaken}; halts the FSM if the
     * cap is hit. Idempotency / dedup is the caller's responsibility — the FSM
     * trusts that each call corresponds to a distinct trade.
     */
    public Result onTradeStarted(DailyRiskState current, Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(now, "now");

        if (current.state() == RiskState.HALTED) {
            // Defensive: a halted FSM should not be receiving onTradeStarted
            // calls (RiskService.canTakeTrade gates the saga). Keep the state
            // unchanged rather than throwing — replay-friendly.
            return new Result(current, List.of());
        }

        int newCount = current.tradesTaken() + 1;
        DailyRiskState bumped = new DailyRiskState(
                current.tenantId(),
                current.sessionDate(),
                current.startingEquity(),
                current.dailyLossBudget(),
                current.realizedLoss(),
                newCount,
                current.state(),
                current.haltedAt(),
                current.haltReason());

        List<RiskEvent> events = new ArrayList<>();
        if (newCount >= maxTradesPerDay) {
            DailyRiskState halted = transitionTo(bumped, RiskState.HALTED, now, "MAX_TRADES");
            events.add(transitionEvent(current.state(), RiskState.HALTED, halted, now));
            events.add(haltEvent(halted, now, "MAX_TRADES"));
            return new Result(halted, events);
        }

        return new Result(bumped, List.of());
    }

    /**
     * Operator-initiated halt. Transitions to HALTED with the given reason.
     * Idempotent if already HALTED — emits no events.
     */
    public Result onHaltManual(DailyRiskState current, String reason, Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (current.state() == RiskState.HALTED) {
            return new Result(current, List.of());
        }
        DailyRiskState halted = transitionTo(current, RiskState.HALTED, now, reason);
        return new Result(
                halted,
                List.of(
                        transitionEvent(current.state(), RiskState.HALTED, halted, now),
                        haltEvent(halted, now, reason)));
    }

    // --- internals -------------------------------------------------------

    /**
     * Map a P&L delta to its loss-magnitude (≥0). Wins return 0 — the
     * Risk FSM is loss-only per §11.3.
     */
    private static BigDecimal lossMagnitudeOf(BigDecimal realizedDelta) {
        return realizedDelta.signum() < 0 ? realizedDelta.negate() : BigDecimal.ZERO;
    }

    private RiskState thresholdState(DailyRiskState s) {
        BigDecimal budget = s.dailyLossBudget();
        if (budget.signum() <= 0) {
            // Pathological: zero budget means no loss is tolerated. Stay in
            // current state (caller decides whether to halt).
            return s.state();
        }
        if (s.realizedLoss().compareTo(budget) >= 0) {
            return RiskState.HALTED;
        }
        BigDecimal warnThreshold = budget.multiply(budgetLowFraction);
        if (s.realizedLoss().compareTo(warnThreshold) >= 0) {
            return RiskState.BUDGET_LOW;
        }
        return RiskState.HEALTHY;
    }

    private static String haltReasonForLoss(DailyRiskState s) {
        if (s.realizedLoss().compareTo(s.dailyLossBudget()) >= 0) {
            return "BUDGET_EXHAUSTED";
        }
        return "BUDGET_LOW_THRESHOLD";
    }

    private static DailyRiskState withLoss(DailyRiskState s, BigDecimal newLoss) {
        return new DailyRiskState(
                s.tenantId(),
                s.sessionDate(),
                s.startingEquity(),
                s.dailyLossBudget(),
                newLoss,
                s.tradesTaken(),
                s.state(),
                s.haltedAt(),
                s.haltReason());
    }

    private static DailyRiskState transitionTo(DailyRiskState s, RiskState target, Instant now, String reason) {
        if (target == RiskState.HALTED) {
            return new DailyRiskState(
                    s.tenantId(),
                    s.sessionDate(),
                    s.startingEquity(),
                    s.dailyLossBudget(),
                    s.realizedLoss(),
                    s.tradesTaken(),
                    target,
                    Optional.of(now),
                    Optional.of(reason));
        }
        return new DailyRiskState(
                s.tenantId(),
                s.sessionDate(),
                s.startingEquity(),
                s.dailyLossBudget(),
                s.realizedLoss(),
                s.tradesTaken(),
                target,
                s.haltedAt(),
                s.haltReason());
    }

    private static RiskEvent budgetConsumedEvent(DailyRiskState afterLoss, BigDecimal lossDelta, Instant now) {
        return new RiskEvent(
                afterLoss.tenantId(),
                afterLoss.sessionDate(),
                now,
                RiskEventType.BUDGET_CONSUMED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(lossDelta),
                Optional.of(afterLoss.realizedLoss()),
                "BUDGET_CONSUMED");
    }

    private static RiskEvent transitionEvent(
            RiskState from, RiskState to, DailyRiskState afterTransition, Instant now) {
        return new RiskEvent(
                afterTransition.tenantId(),
                afterTransition.sessionDate(),
                now,
                RiskEventType.STATE_TRANSITION,
                Optional.of(from),
                Optional.of(to),
                Optional.empty(),
                Optional.of(afterTransition.realizedLoss()),
                from + "->" + to);
    }

    private static RiskEvent haltEvent(DailyRiskState halted, Instant now, String reason) {
        return new RiskEvent(
                halted.tenantId(),
                halted.sessionDate(),
                now,
                RiskEventType.HALT_TRIGGERED,
                Optional.empty(),
                Optional.of(RiskState.HALTED),
                Optional.empty(),
                Optional.of(halted.realizedLoss()),
                reason);
    }
}
