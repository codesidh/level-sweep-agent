package com.levelsweep.shared.domain.trade;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Domain event emitted by the Trade Saga (Phase 2 Step 6) when a bar evaluation
 * short-circuits before producing a trade. Captures the stage at which the
 * saga gave up and the reason list — both required for the audit trail
 * mandated by {@code requirements.md} §18 acceptance criterion 7 (every
 * decision, fired or skipped, must be reconstructable).
 *
 * <p>The {@link #stage} field is intentionally a {@link String} rather than an
 * enum so that downstream consumers (Phase 3 execution, a future ops UI)
 * receive a stable wire-format identifier without cross-module enum coupling.
 * The saga uses the well-known constants below; new stages should be added to
 * the saga and documented in this record's javadoc — never silently widened
 * by callers.
 *
 * <p>Known stages:
 *
 * <ul>
 *   <li>{@code "SESSION_NOT_TRADING"} — session FSM is not in the {@code TRADING}
 *       state (PRE_MARKET, ARMED, FLATTENING, CLOSED, BLACKOUT).
 *   <li>{@code "SIGNAL_SKIP"} — Signal Engine produced {@code SignalAction.SKIP}.
 *       The {@link #reasons} list mirrors {@code SignalEvaluation.reasons()}.
 *   <li>{@code "RISK_BLOCKED"} — Risk FSM in {@code HALTED} or not yet
 *       initialized for the tenant.
 *   <li>{@code "NO_STRIKE"} — Strike Selector returned {@code NoCandidates}.
 *       The {@link #reasons} list contains the selector's reasonCode.
 * </ul>
 *
 * <p>Determinism: identical inputs to the saga (including a fixed clock and
 * fixed UUID supplier) produce identical {@code TradeSkipped} events.
 */
public record TradeSkipped(
        String tenantId,
        LocalDate sessionDate,
        Instant evaluatedAt,
        String stage,
        List<String> reasons,
        String correlationId) {

    public static final String STAGE_SESSION_NOT_TRADING = "SESSION_NOT_TRADING";
    public static final String STAGE_SIGNAL_SKIP = "SIGNAL_SKIP";
    public static final String STAGE_RISK_BLOCKED = "RISK_BLOCKED";
    public static final String STAGE_NO_STRIKE = "NO_STRIKE";
    /** ADR-0007 §2 — Sentinel returned VETO with confidence ≥ 0.85. Saga compensates. */
    public static final String STAGE_SENTINEL_VETO = "SENTINEL_VETO";

    public TradeSkipped {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(correlationId, "correlationId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (stage.isBlank()) {
            throw new IllegalArgumentException("stage must not be blank");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("reasons must contain at least one entry — audit-trail contract");
        }
        // Defensive copy to keep the audit trail immutable in flight.
        reasons = List.copyOf(reasons);
    }
}
