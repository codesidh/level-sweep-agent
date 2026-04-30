package com.levelsweep.shared.domain.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The four reference levels for a trading session.
 *
 * <p>Per {@code requirements.md} §4 — these are computed once per session
 * (finalized at 09:29:30 ET) and define the entry decision matrix in §8.
 *
 * <ul>
 *   <li>{@code pdh} / {@code pdl}: previous-day RTH (09:30–16:00 ET) high / low
 *   <li>{@code pmh} / {@code pml}: overnight 16:01 prior day → 09:29 ET high / low
 * </ul>
 *
 * <p>Tenant-scoped per {@code multi-tenant-readiness} skill — Phase A uses
 * {@code tenantId = "OWNER"}; Phase B opens up multi-tenant.
 */
public record Levels(
        String tenantId,
        String symbol,
        LocalDate sessionDate,
        BigDecimal pdh,
        BigDecimal pdl,
        BigDecimal pmh,
        BigDecimal pml) {

    public Levels {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(pdh, "pdh");
        Objects.requireNonNull(pdl, "pdl");
        Objects.requireNonNull(pmh, "pmh");
        Objects.requireNonNull(pml, "pml");
        if (pdl.compareTo(pdh) > 0) {
            throw new IllegalArgumentException("pdl > pdh: pdl=" + pdl + " pdh=" + pdh);
        }
        if (pml.compareTo(pmh) > 0) {
            throw new IllegalArgumentException("pml > pmh: pml=" + pml + " pmh=" + pmh);
        }
    }
}
