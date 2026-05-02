package com.levelsweep.shared.domain.trade;

import java.time.Instant;
import java.util.Objects;

/**
 * Snapshot view of a position currently held by execution-service. Populated
 * by S3's fill listener when an entry order's TradeFilled arrives, removed
 * when a stop / trailing exit / EOD flatten closes the position.
 *
 * <p>Phase 3 keeps this record in an in-memory {@code InFlightTradeCache}
 * inside execution-service — Phase 7 will move state of record to the
 * {@code trades} table (already in V102 schema) so a JVM restart can rehydrate
 * positions on boot. Lives in shared-domain so both the cache and the EOD
 * flatten saga consume the same shape, and so a future cross-module replay
 * harness can deserialize the same record without a duplicate definition.
 *
 * <p>{@code correlationId} threads the original signal/saga run end-to-end so
 * the EOD flatten audit row can join back to the entry trace.
 */
public record InFlightTrade(
        String tenantId, String tradeId, String contractSymbol, int quantity, Instant enteredAt, String correlationId) {

    public InFlightTrade {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(enteredAt, "enteredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (contractSymbol.isBlank()) {
            throw new IllegalArgumentException("contractSymbol must not be blank");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
    }
}
