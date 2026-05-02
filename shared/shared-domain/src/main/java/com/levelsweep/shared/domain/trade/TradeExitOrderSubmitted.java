package com.levelsweep.shared.domain.trade;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event fired by the Phase 3 Step 4/5 {@code ExitOrderRouter} after
 * Alpaca has accepted ({@code 2xx}) a market-sell exit order driven by either
 * a stop trigger ({@link TradeStopTriggered}) or a trail breach
 * ({@link TradeTrailBreached}). Phase 4 narrator + journal-service observe
 * this event to drive the timeline / position lifecycle.
 *
 * <p>Mirrors {@link TradeOrderSubmitted}'s shape with one additional field —
 * {@link #exitReason} — discriminating which trigger produced the exit
 * ({@link #EXIT_REASON_STOP}, {@link #EXIT_REASON_TRAIL},
 * {@link #EXIT_REASON_EOD}). Single-attempt exit per ADR-0005 §4 — broker
 * rejection or transport failure produces a WARN log only and does NOT fire
 * this event.
 *
 * <p>{@link #correlationId} threads the original signal/saga run end-to-end
 * so a narrator can stitch entry → exit lifecycle from a single trace id.
 *
 * <p>Determinism: identical inputs produce a bit-identical event. Required
 * for the Phase 3 Step 7 replay-parity harness.
 */
public record TradeExitOrderSubmitted(
        String tenantId,
        String tradeId,
        String correlationId,
        String contractSymbol,
        int quantity,
        String alpacaOrderId,
        String clientOrderId,
        String status,
        Instant submittedAt,
        String exitReason) {

    /** Exit was driven by a §9 stop trigger ({@link TradeStopTriggered}). */
    public static final String EXIT_REASON_STOP = "STOP";

    /** Exit was driven by a §10 trail breach ({@link TradeTrailBreached}). */
    public static final String EXIT_REASON_TRAIL = "TRAIL";

    /** Exit was driven by the §14 EOD flatten saga. */
    public static final String EXIT_REASON_EOD = "EOD";

    public TradeExitOrderSubmitted {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(exitReason, "exitReason");
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
        if (!EXIT_REASON_STOP.equals(exitReason)
                && !EXIT_REASON_TRAIL.equals(exitReason)
                && !EXIT_REASON_EOD.equals(exitReason)) {
            throw new IllegalArgumentException("exitReason must be 'STOP', 'TRAIL', or 'EOD': " + exitReason);
        }
    }
}
