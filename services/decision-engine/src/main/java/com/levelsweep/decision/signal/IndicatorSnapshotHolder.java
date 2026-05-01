package com.levelsweep.decision.signal;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Last-value cache for the most recent {@link IndicatorSnapshot}. The Signal
 * Engine reads from this when evaluating a new bar; a future producer (Phase 2
 * follow-up — Indicator Refresher / IndicatorComputeService) will write to it
 * on each computation cycle.
 *
 * <p>Initially empty — no producer wires it yet, so the {@link SignalBarRouter}
 * will see {@link Optional#empty()} and skip the bar with reason
 * {@code "no_indicators"}. That is the desired Phase 2 Step 2 behavior; live
 * signal evaluation is unblocked once the refresher lands.
 *
 * <p>Thread-safe via {@link AtomicReference}; no locking, no fairness guarantees,
 * "latest wins" semantics. The Kafka consumer thread reads, the indicator
 * compute thread writes — both via atomic operations.
 */
@ApplicationScoped
public class IndicatorSnapshotHolder {

    private final AtomicReference<IndicatorSnapshot> latest = new AtomicReference<>();

    /** Replace the held snapshot with {@code snapshot}. Null clears the holder. */
    public void setLatest(IndicatorSnapshot snapshot) {
        latest.set(snapshot);
    }

    /** Read the most recently set snapshot, or empty if no producer has written yet. */
    public Optional<IndicatorSnapshot> latest() {
        return Optional.ofNullable(latest.get());
    }
}
