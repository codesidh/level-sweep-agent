package com.levelsweep.shared.domain.trade;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event emitted by the Phase 3 Step 2 {@code OrderPlacingTradeRouter}
 * when the broker refuses (4xx/5xx) or the transport layer fails for an entry
 * order. Per architecture-spec §17.4, entry submissions do NOT retry — signal
 * time-sensitivity makes another attempt strictly worse than slippage from a
 * widening spread. This event is therefore fatal for the trade's entry path.
 *
 * <p>Phase 3 Step 3 (fill listener) observes this event to advance the trade
 * FSM ENTERED → REJECTED rather than waiting on a fill that will never arrive.
 * Phase 4 narrator surfaces it on the timeline so the operator sees why a
 * proposed trade did not enter.
 *
 * <p>{@link #httpStatus} is {@code -1} for transport-layer failures (no
 * response from broker) and the actual HTTP status for broker-side rejects.
 * The {@link #reason} string is whatever Alpaca returned in the body or the
 * exception message from the {@link java.net.http.HttpClient}.
 */
public record TradeOrderRejected(
        String tenantId,
        String tradeId,
        String correlationId,
        String contractSymbol,
        String clientOrderId,
        int httpStatus,
        String reason,
        Instant rejectedAt) {

    /** Sentinel for transport-layer failures (no HTTP response observed). */
    public static final int HTTP_STATUS_TRANSPORT_FAILURE = -1;

    public TradeOrderRejected {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(rejectedAt, "rejectedAt");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (contractSymbol.isBlank()) {
            throw new IllegalArgumentException("contractSymbol must not be blank");
        }
        if (clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId must not be blank");
        }
        if (httpStatus != HTTP_STATUS_TRANSPORT_FAILURE && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be -1 (transport) or 100..599: " + httpStatus);
        }
    }
}
