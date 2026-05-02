package com.levelsweep.execution.trail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Mutable per-trade state for the §10 trailing stop. Held by
 * {@link TrailRegistry}; advanced by {@link TrailStateMachine} via a pure
 * function returning a new state — the registry then atomically replaces the
 * stored reference.
 *
 * <p>{@link #entryPremium} is the avg-fill premium captured at registration
 * (from {@code TradeFilled.filledAvgPrice()}); UPL fractions are computed
 * against this base. {@link #qty} is the contract count (scales the floor
 * price, not the percentage). {@link #floor} is the trailing stop floor
 * expressed as a UPL fraction (e.g. {@code 0.25} for +25%) — never decreases
 * once {@link #armed} is true (§10.2 monotonicity).
 *
 * <p>Sustainment counters track how many consecutive snapshots have crossed
 * the relevant threshold. {@link #consecAboveCount} drives activation /
 * ratchet (positive moves); {@link #consecAtFloorCount} drives the exit
 * trigger (negative moves to the floor).
 */
public final class TrailState {

    private final String tenantId;
    private final String tradeId;
    private final String contractSymbol;
    private final BigDecimal entryPremium;
    private final int qty;
    private final String correlationId;

    private boolean armed;
    private BigDecimal floor; // null when not armed
    private int consecAboveCount;
    private int consecAtFloorCount;
    private BigDecimal lastNbboMid;
    private Instant lastObservedAt;

    public TrailState(
            String tenantId,
            String tradeId,
            String contractSymbol,
            BigDecimal entryPremium,
            int qty,
            String correlationId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.tradeId = Objects.requireNonNull(tradeId, "tradeId");
        this.contractSymbol = Objects.requireNonNull(contractSymbol, "contractSymbol");
        this.entryPremium = Objects.requireNonNull(entryPremium, "entryPremium");
        this.qty = qty;
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        if (entryPremium.signum() <= 0) {
            throw new IllegalArgumentException("entryPremium must be > 0: " + entryPremium);
        }
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be > 0: " + qty);
        }
    }

    public String tenantId() {
        return tenantId;
    }

    public String tradeId() {
        return tradeId;
    }

    public String contractSymbol() {
        return contractSymbol;
    }

    public BigDecimal entryPremium() {
        return entryPremium;
    }

    public int qty() {
        return qty;
    }

    public String correlationId() {
        return correlationId;
    }

    public boolean armed() {
        return armed;
    }

    public BigDecimal floor() {
        return floor;
    }

    public int consecAboveCount() {
        return consecAboveCount;
    }

    public int consecAtFloorCount() {
        return consecAtFloorCount;
    }

    public BigDecimal lastNbboMid() {
        return lastNbboMid;
    }

    public Instant lastObservedAt() {
        return lastObservedAt;
    }

    /** Mark armed with the given floor. Package-private — only TrailStateMachine mutates. */
    void arm(BigDecimal floor, BigDecimal nbboMid, Instant ts) {
        if (this.armed && this.floor != null && floor.compareTo(this.floor) < 0) {
            throw new IllegalStateException("floor monotonicity violated: existing=" + this.floor + " new=" + floor);
        }
        this.armed = true;
        this.floor = floor;
        this.consecAboveCount = 0;
        this.lastNbboMid = nbboMid;
        this.lastObservedAt = ts;
    }

    /** Bump the "snapshot above next ratchet threshold" counter. */
    void incAbove(BigDecimal nbboMid, Instant ts) {
        this.consecAboveCount++;
        this.lastNbboMid = nbboMid;
        this.lastObservedAt = ts;
    }

    /** Reset the "above" counter — UPL retraced below the next threshold. */
    void resetAbove(BigDecimal nbboMid, Instant ts) {
        this.consecAboveCount = 0;
        this.lastNbboMid = nbboMid;
        this.lastObservedAt = ts;
    }

    /** Bump the "snapshot at-or-below floor" counter. */
    void incAtFloor(BigDecimal nbboMid, Instant ts) {
        this.consecAtFloorCount++;
        this.lastNbboMid = nbboMid;
        this.lastObservedAt = ts;
    }

    /** Reset the "at floor" counter — NBBO recovered above the floor. */
    void resetAtFloor(BigDecimal nbboMid, Instant ts) {
        this.consecAtFloorCount = 0;
        this.lastNbboMid = nbboMid;
        this.lastObservedAt = ts;
    }
}
