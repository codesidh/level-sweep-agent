package com.levelsweep.aiagent.reviewer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One signal evaluation captured during the trading session. Phase 2's signal
 * engine produces these; Phase 4's {@link DailyReviewer} aggregates them into
 * the day's narrative for Opus 4.7.
 *
 * <p>Phase 4 status: the {@link SessionJournalAggregator} attempts to read from
 * a {@code signal_history} Mongo collection if a producer exists. If not, the
 * aggregator returns an empty list and logs a TODO — Phase 5/6 wires the
 * decision-engine producer side.
 *
 * @param tenantId      multi-tenant scope (architecture-spec §22 #4)
 * @param sessionDate   ET local session date (one bucket per RTH day)
 * @param signalId      unique evaluation id (saga correlation)
 * @param evaluatedAt   wall-clock instant of the evaluation
 * @param side          option side derived from the level type
 * @param levelType     which level fired the signal (PDH/PDL/PMH/PML)
 * @param outcome       what happened to this evaluation
 * @param reasonCode    short machine-readable reason (e.g. "ema48_under", "regime_high_vol")
 * @param correlationId trace correlation id linking back to saga + audit rows
 */
public record SignalEvaluationRecord(
        String tenantId,
        LocalDate sessionDate,
        String signalId,
        Instant evaluatedAt,
        Side side,
        LevelType levelType,
        Outcome outcome,
        String reasonCode,
        String correlationId) {

    /** Option side derived from the level break direction. */
    public enum Side {
        CALL,
        PUT
    }

    /** The four levels the strategy hunts (architecture-spec strategy + glossary). */
    public enum LevelType {
        PDH,
        PDL,
        PMH,
        PML
    }

    /**
     * Disposition of one signal evaluation:
     *
     * <ul>
     *   <li>{@code TAKEN} — passed Risk + Sentinel, order submitted</li>
     *   <li>{@code SKIPPED} — Risk FSM denied (e.g. day-loss cap, news blackout)</li>
     *   <li>{@code VETOED} — Sentinel vetoed (confidence ≥ 0.85)</li>
     *   <li>{@code REJECTED} — Alpaca rejected the order at the broker layer</li>
     * </ul>
     */
    public enum Outcome {
        TAKEN,
        SKIPPED,
        VETOED,
        REJECTED
    }

    public SignalEvaluationRecord {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(signalId, "signalId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(levelType, "levelType");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(correlationId, "correlationId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (signalId.isBlank()) {
            throw new IllegalArgumentException("signalId must not be blank");
        }
    }
}
