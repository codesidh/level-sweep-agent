package com.levelsweep.shared.domain.trade;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic, replay-safe description of a single order to submit to the
 * broker. The Phase 3 Step 2 {@code AlpacaTradingClient} is the primary
 * consumer; the shape is intentionally broker-agnostic so a future second
 * broker (Tradier, IBKR, …) can be slotted behind the same interface.
 *
 * <p>{@link #clientOrderId} is the broker-side idempotency key — the format is
 * always {@code "<tenantId>:<tradeId>"} so the Saga's already-deterministic
 * tradeId carries through to the broker. Re-submitting the same payload will
 * produce a 422 from Alpaca (duplicate {@code client_order_id}) which the
 * client maps to {@code OrderSubmission.Rejected}; in-flight retries are
 * therefore safe even though we deliberately do NOT retry on entry per
 * architecture-spec §17.4.
 *
 * <p>Determinism: identical {@link com.levelsweep.shared.domain.trade.TradeProposed}
 * inputs to the {@code OrderPlacingTradeRouter} produce a bit-identical
 * {@code OrderRequest} — required for the Phase 3 Step 7 replay-parity harness.
 */
public record OrderRequest(
        String tenantId,
        String tradeId,
        String contractSymbol,
        int quantity,
        String side,
        String type,
        Optional<BigDecimal> limitPrice,
        String timeInForce,
        String clientOrderId) {

    /** Side string for opening (buying) a long call/put. */
    public static final String SIDE_BUY = "buy";

    /** Side string for closing a long call/put (used by S6 EOD flatten). */
    public static final String SIDE_SELL = "sell";

    /** Limit order type — entry orders cross the spread by a penny. */
    public static final String TYPE_LIMIT = "limit";

    /** Market order type — kept for the EOD flatten path. */
    public static final String TYPE_MARKET = "market";

    /** Day-only TIF — the only TIF supported in Phase 3 (intra-session). */
    public static final String TIF_DAY = "day";

    public OrderRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(timeInForce, "timeInForce");
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (contractSymbol.isBlank()) {
            throw new IllegalArgumentException("contractSymbol must not be blank");
        }
        if (clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0: " + quantity);
        }
        if (!SIDE_BUY.equals(side) && !SIDE_SELL.equals(side)) {
            throw new IllegalArgumentException("side must be 'buy' or 'sell': " + side);
        }
        if (!TYPE_LIMIT.equals(type) && !TYPE_MARKET.equals(type)) {
            throw new IllegalArgumentException("type must be 'limit' or 'market': " + type);
        }
        if (TYPE_LIMIT.equals(type) && limitPrice.isEmpty()) {
            throw new IllegalArgumentException("limitPrice required for type=limit");
        }
        if (TYPE_MARKET.equals(type) && limitPrice.isPresent()) {
            throw new IllegalArgumentException("limitPrice must be empty for type=market");
        }
        if (limitPrice.isPresent() && limitPrice.get().signum() <= 0) {
            throw new IllegalArgumentException("limitPrice must be > 0: " + limitPrice.get());
        }
    }

    /** Canonical idempotency key shape: {@code "<tenantId>:<tradeId>"}. */
    public static String idempotencyKey(String tenantId, String tradeId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        return tenantId + ":" + tradeId;
    }
}
