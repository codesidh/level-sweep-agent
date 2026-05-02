package com.levelsweep.shared.domain.trade;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event emitted by the EOD flatten saga (Phase 3 Step 6) when a single
 * in-flight trade has been force-closed by the 15:55 ET cron. One event per
 * trade per session — multi-trade fires emit one per successful submission.
 *
 * <p>This event is the success-path counterpart to the {@code FAILED} audit row
 * persisted by {@code EodFlattenAuditRepository}: a downstream listener can
 * observe both signals to drive narrator/journal updates without touching the
 * audit table directly.
 *
 * <p>{@code alpacaOrderId} is the broker's accepted-order id (round-tripped
 * from {@code OrderSubmission.brokerOrderId}). Not nullable — failure paths
 * fire an audit row only, not this event.
 *
 * <p>{@code correlationId} threads the original signal/saga run end-to-end so a
 * narrator can stitch entry → exit lifecycle from a single trace id.
 */
public record TradeEodFlattened(
        String tenantId, String tradeId, String alpacaOrderId, Instant flattenedAt, String correlationId) {

    public TradeEodFlattened {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
        Objects.requireNonNull(flattenedAt, "flattenedAt");
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
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
    }
}
