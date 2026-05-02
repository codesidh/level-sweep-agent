package com.levelsweep.shared.domain.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Audit-only domain event fired by the Phase 3 Step 5 Trail Manager every
 * time the trailing floor advances under the §10.2 ratchet rule. Not consumed
 * by any operational path — the {@code ExitOrderRouter} only listens for
 * {@link TradeTrailBreached}. Phase 4 narrator + journal-service observe this
 * for human-readable timeline rendering.
 *
 * <p>{@link #uplPct} is the unrealized P&amp;L percentage that triggered the
 * ratchet (e.g. {@code 0.35} for +35%); {@link #newFloorPct} is the resulting
 * trailing floor (e.g. {@code 0.30} for +30%). {@link #nbboMid} is the NBBO
 * midpoint that drove the calculation. All three are {@link BigDecimal} to
 * preserve audit-grade precision.
 *
 * <p>Determinism: derived purely from the (TrailState, NBBO snapshot) pair —
 * no clock reads (the {@code observedAt} is the Trail Manager's externally
 * supplied snapshot timestamp, not {@code Instant.now()}).
 */
public record TradeTrailRatcheted(
        String tenantId,
        String tradeId,
        Instant observedAt,
        BigDecimal nbboMid,
        BigDecimal uplPct,
        BigDecimal newFloorPct,
        String correlationId) {

    public TradeTrailRatcheted {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(nbboMid, "nbboMid");
        Objects.requireNonNull(uplPct, "uplPct");
        Objects.requireNonNull(newFloorPct, "newFloorPct");
        Objects.requireNonNull(correlationId, "correlationId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (nbboMid.signum() < 0) {
            throw new IllegalArgumentException("nbboMid must be non-negative: " + nbboMid);
        }
    }
}
