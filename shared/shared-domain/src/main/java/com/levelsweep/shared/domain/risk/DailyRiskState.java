package com.levelsweep.shared.domain.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-tenant snapshot of the daily Risk FSM. Populated at 09:29 ET when the
 * Risk Manager (Phase 2 Step 3) computes {@link #startingEquity} from the
 * Alpaca account balance and derives {@link #dailyLossBudget} per
 * {@code requirements.md} §11.2.
 *
 * <p>This record is the in-memory + persisted shape: {@link #realizedLoss}
 * accumulates as fills settle, {@link #tradesTaken} counts entries, and
 * {@link #state} transitions per the Risk FSM thresholds. The
 * {@code DailyRiskStateRepository} maps this 1:1 onto the extended
 * {@code daily_state} columns added in {@code V103__extend_daily_state_for_risk.sql}.
 *
 * <p>Invariants enforced in the canonical constructor:
 *
 * <ul>
 *   <li>{@code tradesTaken >= 0}, {@code realizedLoss >= 0} (loss is
 *       represented as a non-negative magnitude — sign of P&L lives in
 *       {@code trades.realized_pnl})
 *   <li>{@code state == HALTED} implies {@code haltedAt} is present
 *   <li>{@code haltedAt} present implies {@code haltReason} is present
 *       (and vice-versa) — a halt without a reason violates the audit trail
 * </ul>
 *
 * <p>Tenant-scoped per the {@code multi-tenant-readiness} skill: every field
 * pivots on {@link #tenantId} so Phase B can hold many concurrent FSMs in
 * a single Decision Engine process.
 */
public record DailyRiskState(
        String tenantId,
        LocalDate sessionDate,
        BigDecimal startingEquity,
        BigDecimal dailyLossBudget,
        BigDecimal realizedLoss,
        int tradesTaken,
        RiskState state,
        Optional<Instant> haltedAt,
        Optional<String> haltReason) {

    public DailyRiskState {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(startingEquity, "startingEquity");
        Objects.requireNonNull(dailyLossBudget, "dailyLossBudget");
        Objects.requireNonNull(realizedLoss, "realizedLoss");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(haltedAt, "haltedAt");
        Objects.requireNonNull(haltReason, "haltReason");

        if (tradesTaken < 0) {
            throw new IllegalArgumentException("tradesTaken must be >= 0; got " + tradesTaken);
        }
        if (realizedLoss.signum() < 0) {
            throw new IllegalArgumentException(
                    "realizedLoss must be >= 0 (magnitude); got " + realizedLoss);
        }
        if (state == RiskState.HALTED && haltedAt.isEmpty()) {
            throw new IllegalArgumentException("HALTED state requires haltedAt");
        }
        if (haltedAt.isPresent() != haltReason.isPresent()) {
            throw new IllegalArgumentException(
                    "haltedAt and haltReason must both be present or both absent");
        }
    }
}
