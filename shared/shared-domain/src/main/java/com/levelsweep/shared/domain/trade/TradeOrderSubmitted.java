package com.levelsweep.shared.domain.trade;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event emitted by the Phase 3 Step 2 {@code OrderPlacingTradeRouter}
 * after Alpaca has accepted ({@code 2xx}) an entry order for a previously
 * proposed trade. Phase 3 Step 3's fill listener subscribes to this CDI event
 * to build its tradeId → alpacaOrderId correlation map; Phase 4's narrator
 * also observes for human-readable timeline rendering.
 *
 * <p>{@link #correlationId} threads the saga run end-to-end (signal evaluation
 * → strike selection → order submission → fill); {@link #clientOrderId} is the
 * idempotency key the Saga produced ({@code "<tenantId>:<tradeId>"}) and
 * {@link #alpacaOrderId} is the broker-side handle.
 *
 * <p>Determinism: given a fixed clock and identical broker response, this
 * event is bit-identical across runs. Required for the Phase 3 Step 7
 * replay-parity harness.
 */
public record TradeOrderSubmitted(
        String tenantId,
        String tradeId,
        String correlationId,
        String contractSymbol,
        int quantity,
        String alpacaOrderId,
        String clientOrderId,
        String status,
        Instant submittedAt) {

    public TradeOrderSubmitted {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(submittedAt, "submittedAt");
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
        if (alpacaOrderId.isBlank()) {
            throw new IllegalArgumentException("alpacaOrderId must not be blank");
        }
        if (clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId must not be blank");
        }
        if (status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0: " + quantity);
        }
    }
}
