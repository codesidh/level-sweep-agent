package com.levelsweep.shared.domain.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain event emitted when an Alpaca options entry order is filled — fully
 * or partially. Phase 3 Step 3 (the trade-updates WebSocket listener) is the
 * primary producer; downstream consumers are the per-trade FSM in
 * decision-engine (drives ENTERED → ACTIVE), the stop watcher (S4), and the
 * trail manager (S5).
 *
 * <p>Wire format: published to the {@code tenant.fills} Kafka topic
 * (architecture-spec §12.1) keyed by {@link #tenantId} so the broker
 * partitions per-tenant; preserves ordering within a tenant's fill stream.
 *
 * <p>{@link #correlationId} is the same value carried on the upstream
 * {@code TradeProposed} → execution {@code OrderSubmitted} chain so an
 * operator can stitch a single trade's lifetime back together from the audit
 * trail (signal evaluation → risk gate → order submit → fill).
 *
 * <p>Status is one of {@code "filled"} or {@code "partial_fill"}; the
 * non-fill terminal states (rejected, canceled, expired) are surfaced via
 * the broader {@link TradeFillEvent} catch-all and never produce a
 * {@code TradeFilled}. Splitting the two keeps the FSM observer in
 * decision-engine simple — it only subscribes to {@code TradeFilled}.
 *
 * <p>Determinism: produced from a pure decode over the upstream JSON frame
 * (no clock reads, no UUID generation). Two runs of the listener over the
 * same WS frame produce bit-identical events.
 */
public record TradeFilled(
        String tenantId,
        String tradeId,
        String alpacaOrderId,
        String contractSymbol,
        BigDecimal filledAvgPrice,
        int filledQty,
        String status,
        Instant filledAt,
        String correlationId) {

    public TradeFilled {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(filledAvgPrice, "filledAvgPrice");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(filledAt, "filledAt");
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
        if (filledAvgPrice.signum() < 0) {
            throw new IllegalArgumentException("filledAvgPrice must be non-negative: " + filledAvgPrice);
        }
        if (filledQty <= 0) {
            throw new IllegalArgumentException("filledQty must be positive: " + filledQty);
        }
        if (!"filled".equals(status) && !"partial_fill".equals(status)) {
            throw new IllegalArgumentException("status must be 'filled' or 'partial_fill': " + status);
        }
    }
}
