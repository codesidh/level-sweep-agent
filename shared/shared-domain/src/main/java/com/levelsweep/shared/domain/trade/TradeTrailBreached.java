package com.levelsweep.shared.domain.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain event fired by the Phase 3 Step 5 Trail Manager when the NBBO
 * mid-point retraces to (or below) the current armed trailing floor for the
 * sustainment window — see {@code requirements.md} §10.3. The Phase 3 Step
 * 4/5 {@code ExitOrderRouter} observes this event and submits a single
 * market-sell exit order; observers must be idempotent because the
 * deterministic {@code clientOrderId} elsewhere relies on Alpaca rejecting
 * duplicates.
 *
 * <p>{@link #exitFloorPct} is the floor (as a fractional UPL — e.g.
 * {@code 0.35} for +35%) that the trade is exiting at. {@link #correlationId}
 * threads the saga run end-to-end.
 *
 * <p>Determinism: produced from a pure (TrailState, NBBO snapshot) — no
 * clock reads, no UUIDs.
 */
public record TradeTrailBreached(
        String tenantId,
        String tradeId,
        String contractSymbol,
        Instant observedAt,
        BigDecimal nbboMid,
        BigDecimal exitFloorPct,
        String correlationId) {

    public TradeTrailBreached {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(nbboMid, "nbboMid");
        Objects.requireNonNull(exitFloorPct, "exitFloorPct");
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
        if (nbboMid.signum() < 0) {
            throw new IllegalArgumentException("nbboMid must be non-negative: " + nbboMid);
        }
    }
}
