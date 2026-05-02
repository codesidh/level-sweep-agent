package com.levelsweep.shared.domain.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain event fired by the Phase 3 Step 4 Stop Watcher when a held trade's
 * 2-minute bar close violates the §9 stop trigger. {@link #stopReference} is
 * either {@code "EMA13"} (default) or {@code "EMA48"} (the §9.2 exception
 * — chosen when {@code |EMA13 − EMA48| < 0.5 × ATR(14)} at trigger evaluation
 * time). The Phase 3 Step 4/5 {@code ExitOrderRouter} observes this event and
 * submits a single market-sell exit order; observers must be idempotent
 * because the deterministic {@code clientOrderId} elsewhere relies on Alpaca
 * rejecting duplicates.
 *
 * <p>{@link #alpacaOrderId} is the broker-side handle from S2's accepted entry
 * order — useful for joining audit rows back to the original entry. {@link
 * #correlationId} threads the saga run end-to-end.
 *
 * <p>Determinism: produced from a pure (bar, indicator, registered-stop)
 * tuple — no clock reads, no UUIDs. Two runs of the watcher over the same
 * bar+indicator produce bit-identical events.
 */
public record TradeStopTriggered(
        String tenantId,
        String tradeId,
        String alpacaOrderId,
        String contractSymbol,
        Instant barTimestamp,
        BigDecimal barClose,
        String stopReference,
        Instant triggeredAt,
        String correlationId) {

    /** Stop reference identifier when the EMA13 close-violation rule fires. */
    public static final String STOP_REF_EMA13 = "EMA13";

    /** Stop reference identifier when the §9.2 EMA48 exception fires. */
    public static final String STOP_REF_EMA48 = "EMA48";

    public TradeStopTriggered {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(barTimestamp, "barTimestamp");
        Objects.requireNonNull(barClose, "barClose");
        Objects.requireNonNull(stopReference, "stopReference");
        Objects.requireNonNull(triggeredAt, "triggeredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (alpacaOrderId.isBlank()) {
            throw new IllegalArgumentException("alpacaOrderId must not be blank");
        }
        if (contractSymbol.isBlank()) {
            throw new IllegalArgumentException("contractSymbol must not be blank");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (!STOP_REF_EMA13.equals(stopReference) && !STOP_REF_EMA48.equals(stopReference)) {
            throw new IllegalArgumentException("stopReference must be 'EMA13' or 'EMA48': " + stopReference);
        }
    }
}
