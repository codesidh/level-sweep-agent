package com.levelsweep.shared.domain.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Append-only record of a single Risk FSM event. Persisted into
 * {@code risk_events} (architecture-spec §13.1) by the {@code RiskService}
 * coordinator each time the {@code RiskFsm} fires.
 *
 * <p>Field semantics by {@link #type}:
 *
 * <ul>
 *   <li>{@link RiskEventType#BUDGET_CONSUMED} — {@link #deltaAmount} carries
 *       the magnitude of loss realized by the fill (≥0); {@link #cumulativeLoss}
 *       carries the running total. {@link #fromState}/{@link #toState} are
 *       both empty (state didn't necessarily change).
 *   <li>{@link RiskEventType#STATE_TRANSITION} — {@link #fromState} and
 *       {@link #toState} are both present. Other fields may be empty.
 *   <li>{@link RiskEventType#HALT_TRIGGERED} — {@link #toState} is
 *       {@link RiskState#HALTED}; {@link #reason} carries the halt cause
 *       (e.g. {@code "BUDGET_EXHAUSTED"}, {@code "MAX_TRADES"},
 *       {@code "NEWS_BLACKOUT"}, {@code "MANUAL"}).
 *   <li>{@link RiskEventType#DAILY_RESET} — emitted on the 09:29 ET roll-over;
 *       {@link #toState} is typically {@link RiskState#HEALTHY}.
 * </ul>
 *
 * <p>The canonical constructor enforces that {@link #reason} is non-null and
 * non-blank — every persisted risk event must explain itself for the audit
 * trail (architecture-spec §18.3).
 */
public record RiskEvent(
        String tenantId,
        LocalDate sessionDate,
        Instant occurredAt,
        RiskEventType type,
        Optional<RiskState> fromState,
        Optional<RiskState> toState,
        Optional<BigDecimal> deltaAmount,
        Optional<BigDecimal> cumulativeLoss,
        String reason) {

    public RiskEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fromState, "fromState");
        Objects.requireNonNull(toState, "toState");
        Objects.requireNonNull(deltaAmount, "deltaAmount");
        Objects.requireNonNull(cumulativeLoss, "cumulativeLoss");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
