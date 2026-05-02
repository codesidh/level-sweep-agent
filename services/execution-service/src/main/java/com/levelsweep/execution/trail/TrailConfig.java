package com.levelsweep.execution.trail;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Configuration for the §10 trailing-stop FSM, sourced from
 * {@code application.yml} {@code levelsweep.trail.*}. Per ADR-0005 §2 these
 * defaults are tunable in dev and locked in Phase 8 production:
 *
 * <ul>
 *   <li>{@code sustainment-snapshots} — N consecutive 1-second NBBO
 *       snapshots required to advance the FSM (default 3).
 *   <li>{@code activation-upl-pct} — UPL fraction at which the trail arms
 *       (default 0.30 → +30% UPL).
 *   <li>{@code ratchet-step-pct} — step size for the floor ratchet
 *       (default 0.05 → +5% per advance).
 * </ul>
 *
 * <p>{@link BigDecimal} on the percentage fields keeps the comparisons
 * exact — fractional bps drift from {@code double} arithmetic would let a
 * synthetic NBBO at exactly the floor flip-flop between exit and hold.
 */
@ApplicationScoped
public final class TrailConfig {

    private final int sustainmentSnapshots;
    private final BigDecimal activationUplPct;
    private final BigDecimal ratchetStepPct;

    public TrailConfig(
            @ConfigProperty(name = "levelsweep.trail.sustainment-snapshots", defaultValue = "3")
                    int sustainmentSnapshots,
            @ConfigProperty(name = "levelsweep.trail.activation-upl-pct", defaultValue = "0.30")
                    BigDecimal activationUplPct,
            @ConfigProperty(name = "levelsweep.trail.ratchet-step-pct", defaultValue = "0.05")
                    BigDecimal ratchetStepPct) {
        if (sustainmentSnapshots < 1 || sustainmentSnapshots > 10) {
            throw new IllegalArgumentException(
                    "levelsweep.trail.sustainment-snapshots must be in [1,10]: " + sustainmentSnapshots);
        }
        Objects.requireNonNull(activationUplPct, "activationUplPct");
        Objects.requireNonNull(ratchetStepPct, "ratchetStepPct");
        if (activationUplPct.signum() <= 0) {
            throw new IllegalArgumentException("activationUplPct must be > 0: " + activationUplPct);
        }
        if (ratchetStepPct.signum() <= 0) {
            throw new IllegalArgumentException("ratchetStepPct must be > 0: " + ratchetStepPct);
        }
        this.sustainmentSnapshots = sustainmentSnapshots;
        this.activationUplPct = activationUplPct;
        this.ratchetStepPct = ratchetStepPct;
    }

    /** Test seam — pure POJO construction without ConfigProperty. */
    public static TrailConfig of(int sustainmentSnapshots, BigDecimal activationUplPct, BigDecimal ratchetStepPct) {
        return new TrailConfig(sustainmentSnapshots, activationUplPct, ratchetStepPct);
    }

    public int sustainmentSnapshots() {
        return sustainmentSnapshots;
    }

    public BigDecimal activationUplPct() {
        return activationUplPct;
    }

    public BigDecimal ratchetStepPct() {
        return ratchetStepPct;
    }
}
