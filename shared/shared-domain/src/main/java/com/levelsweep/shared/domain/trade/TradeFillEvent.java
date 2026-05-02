package com.levelsweep.shared.domain.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Catch-all domain event for every Alpaca trade-updates frame the listener
 * receives — including non-fill states ({@code new}, {@code canceled},
 * {@code expired}, {@code rejected}) that {@link TradeFilled} does not cover.
 *
 * <p>Phase 3 Step 3 (the trade-updates WebSocket listener) emits one of these
 * for every frame; {@link TradeFilled} is fired in addition for the
 * fill-flavored events. Downstream consumers of this event are the audit-log
 * persister (writes a {@code fills} table row) and the stop watcher (which
 * needs to stand down on rejected/canceled events without waiting for a
 * fill).
 *
 * <p>{@link #alpacaEvent} is the verbatim string from the upstream
 * {@code data.event} field — kept as a string (not an enum) so a new event
 * type from Alpaca does not crash the decoder; the consumer side decides what
 * to do with unknown values.
 *
 * <p>{@link #clientOrderId} is Alpaca's tenant-supplied tag — for our orders
 * it carries {@code "<tenantId>:<tradeId>"} per S2's idempotency contract.
 * The decoder splits this into separate fields when it can; if the upstream
 * order was not one of ours (e.g. a manual paper-account fill made for
 * testing), {@link #clientOrderId} is non-blank but the parsed
 * {@code tenantId/tradeId} would not be present.
 *
 * <p>Determinism: produced from a pure decode over the upstream JSON frame.
 */
public record TradeFillEvent(
        String tenantId,
        String alpacaOrderId,
        String clientOrderId,
        String alpacaEvent,
        Instant occurredAt,
        Optional<BigDecimal> filledAvgPrice,
        Optional<Integer> filledQty,
        Optional<String> reason) {

    public TradeFillEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(alpacaEvent, "alpacaEvent");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(filledAvgPrice, "filledAvgPrice");
        Objects.requireNonNull(filledQty, "filledQty");
        Objects.requireNonNull(reason, "reason");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (alpacaOrderId.isBlank()) {
            throw new IllegalArgumentException("alpacaOrderId must not be blank");
        }
        if (alpacaEvent.isBlank()) {
            throw new IllegalArgumentException("alpacaEvent must not be blank");
        }
    }
}
