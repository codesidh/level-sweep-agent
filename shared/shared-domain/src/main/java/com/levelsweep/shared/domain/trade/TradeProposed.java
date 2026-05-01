package com.levelsweep.shared.domain.trade;

import com.levelsweep.shared.domain.options.OptionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain event emitted by the Trade Saga (Phase 2 Step 6) when a signal has
 * cleared the session, signal, risk, and strike-selection gates and a trade is
 * being proposed for downstream execution. The Phase 3 execution service is
 * the primary consumer — it will turn this into an Alpaca options entry order.
 *
 * <p>The event is fully self-describing: every field execution needs to place
 * the order is captured here, so the consumer does not have to re-resolve the
 * snapshot, the chain, or the strike selector. {@link #correlationId} threads
 * the saga run end-to-end (signal evaluation → risk gate → strike selection →
 * trade FSM → fsm_transitions row → execution span) so an operator can stitch
 * a single bar's lifetime back together from the audit trail.
 *
 * <p>Per the saga's contract: by the time this event is fired the
 * {@link com.levelsweep.shared.domain.options.OptionContract} has been
 * resolved, the per-trade FSM has advanced PROPOSED → ENTERED, and the risk
 * service has been told a trade started. The actual entry-order submission is
 * Phase 3.
 *
 * <p>Determinism: the saga runs synchronously on the bar-consumer thread; given
 * a fixed clock, a fixed UUID supplier, and identical (bar, snapshot, levels)
 * inputs the produced event is bit-identical across runs. This is required for
 * the Phase 2 Step 7 replay-parity harness.
 */
public record TradeProposed(
        String tenantId,
        String tradeId,
        LocalDate sessionDate,
        Instant proposedAt,
        String underlying,
        OptionSide side,
        String contractSymbol,
        BigDecimal entryNbboBid,
        BigDecimal entryNbboAsk,
        BigDecimal entryMid,
        Optional<BigDecimal> impliedVolatility,
        Optional<BigDecimal> delta,
        String correlationId,
        List<String> signalReasons) {

    public TradeProposed {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(underlying, "underlying");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(entryNbboBid, "entryNbboBid");
        Objects.requireNonNull(entryNbboAsk, "entryNbboAsk");
        Objects.requireNonNull(entryMid, "entryMid");
        Objects.requireNonNull(impliedVolatility, "impliedVolatility");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(signalReasons, "signalReasons");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        if (contractSymbol.isBlank()) {
            throw new IllegalArgumentException("contractSymbol must not be blank");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (entryNbboBid.signum() < 0) {
            throw new IllegalArgumentException("entryNbboBid must be non-negative: " + entryNbboBid);
        }
        if (entryNbboAsk.compareTo(entryNbboBid) < 0) {
            throw new IllegalArgumentException(
                    "entryNbboAsk must be >= entryNbboBid: bid=" + entryNbboBid + " ask=" + entryNbboAsk);
        }
        // Defensive copy — preserves ordering (deterministic for replay) and
        // produces an unmodifiable list so consumers cannot mutate the audit
        // trail in flight.
        signalReasons = List.copyOf(signalReasons);
    }
}
