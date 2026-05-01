package com.levelsweep.decision.signal;

import com.levelsweep.shared.domain.marketdata.Levels;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Last-value cache for the session's reference {@link Levels}. The Signal
 * Engine reads from this when evaluating a bar; the Levels Computer (a Phase 2
 * follow-up that runs at 09:29:30 ET per {@code requirements.md} §4) will be
 * the producer.
 *
 * <p>Initially empty; until populated the {@link SignalBarRouter} will skip
 * incoming bars with reason {@code "no_levels"}. Phase 2 Step 2 ships the
 * Signal Engine without the levels producer wired up; that is intentional and
 * tracked as a follow-up.
 *
 * <p>Thread-safe via {@link AtomicReference}. Only one writer is expected
 * per session (the levels computer at 09:29:30 ET) so contention is non-existent
 * in practice; the atomic is purely for visibility across the writer / reader
 * threads.
 */
@ApplicationScoped
public class LevelsHolder {

    private final AtomicReference<Levels> latest = new AtomicReference<>();

    /** Replace the held levels record with {@code levels}. Null clears the holder. */
    public void setLatest(Levels levels) {
        latest.set(levels);
    }

    /** Read the most recently set levels, or empty if no producer has written yet. */
    public Optional<Levels> latest() {
        return Optional.ofNullable(latest.get());
    }
}
