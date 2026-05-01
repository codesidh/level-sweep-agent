package com.levelsweep.decision.fsm.trade;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Identity + current snapshot for a single trade. Held in the per-tenant in-memory
 * map by {@code TradeService}; mirrored to MS SQL via the {@code trades} table (S3)
 * and the {@code fsm_transitions} append log.
 *
 * <p>{@code tradeId} is a UUID minted by the saga at the {@link TradeState#PROPOSED}
 * boot transition. {@code contractSymbol} stays empty until Strike Selector lands
 * a contract on this trade.
 */
public record TradeFsmInstance(
        String tenantId,
        LocalDate sessionDate,
        String tradeId,
        TradeState state,
        Optional<String> contractSymbol,
        Optional<Instant> proposedAt,
        Optional<Instant> filledAt,
        Optional<Instant> exitedAt) {

    public TradeFsmInstance {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(filledAt, "filledAt");
        Objects.requireNonNull(exitedAt, "exitedAt");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId blank");
        }
    }

    /** Returns a copy with the new state. */
    public TradeFsmInstance withState(TradeState newState) {
        return new TradeFsmInstance(
                tenantId, sessionDate, tradeId, newState, contractSymbol, proposedAt, filledAt, exitedAt);
    }
}
