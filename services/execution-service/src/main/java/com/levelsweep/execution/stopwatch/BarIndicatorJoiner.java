package com.levelsweep.execution.stopwatch;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure POJO that joins {@code market.bars.2m} and {@code market.indicators.2m}
 * by {@code (symbol, timestamp)}. Held in {@link StopWatcherService} to drive
 * the §9 trigger evaluation — the trigger requires both the bar's close and
 * the indicator snapshot at the same instant.
 *
 * <p>Per ADR-0005 §1: if the indicator hasn't arrived within 5 seconds of
 * the bar (or vice versa), the bar is dropped from evaluation and a
 * {@code stop.watcher.indicator_skew} metric is logged. The §9 trigger then
 * fires on the next bar instead — fail-closed.
 *
 * <p>Per-symbol state holds at most one pending bar OR one pending indicator
 * at a time; arrival of a matching counterpart immediately emits the joined
 * pair to the {@link Listener} and clears the slot. A mismatched timestamp
 * (the new arrival's timestamp differs from the pending one) drops the
 * pending side and replaces it with the new arrival — the older arrival is
 * stale.
 *
 * <p>Determinism: a fixed {@link Clock} produces the same drop/emit decisions
 * across replay runs. Tests inject {@link Clock#fixed} so the 5-second
 * timeout is exercised deterministically.
 *
 * <p>Thread-safety: per-symbol state is guarded by synchronizing on the
 * Pending instance — Quarkus reactive messaging fans incoming bars and
 * indicators across threads. The pure-POJO nature makes the class trivially
 * unit-testable.
 */
public final class BarIndicatorJoiner {

    private static final Logger LOG = LoggerFactory.getLogger(BarIndicatorJoiner.class);
    private static final Duration DEFAULT_SKEW_TOLERANCE = Duration.ofSeconds(5);

    private final Clock clock;
    private final Duration skewTolerance;
    private final Listener listener;
    private final Map<String, Pending> pendingBySymbol = new ConcurrentHashMap<>();

    public BarIndicatorJoiner(Clock clock, Listener listener) {
        this(clock, DEFAULT_SKEW_TOLERANCE, listener);
    }

    BarIndicatorJoiner(Clock clock, Duration skewTolerance, Listener listener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.skewTolerance = Objects.requireNonNull(skewTolerance, "skewTolerance");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Accept a bar. If a pending indicator exists for the same symbol with a
     * matching {@code closeTime == indicator.timestamp()}, emit the joined
     * pair and clear. Otherwise stash the bar in the pending slot.
     */
    public void onBar(Bar bar) {
        Objects.requireNonNull(bar, "bar");
        joinOrStash(bar.symbol(), bar, null, bar.closeTime());
    }

    /**
     * Accept an indicator snapshot. Mirror behavior of {@link #onBar(Bar)}.
     */
    public void onIndicator(IndicatorSnapshot indicator) {
        Objects.requireNonNull(indicator, "indicator");
        joinOrStash(indicator.symbol(), null, indicator, indicator.timestamp());
    }

    private void joinOrStash(String symbol, Bar arrivingBar, IndicatorSnapshot arrivingIndicator, Instant arrivingTs) {
        Pending pending = pendingBySymbol.computeIfAbsent(symbol, s -> new Pending());
        synchronized (pending) {
            // First, expire any stale pending side that has aged past skewTolerance.
            Instant now = clock.instant();
            if (pending.bar != null && Duration.between(pending.arrivedAt, now).compareTo(skewTolerance) > 0) {
                LOG.warn(
                        "stop.watcher.indicator_skew: dropping stale pending bar symbol={} barTs={} ageMs={}",
                        symbol,
                        pending.bar.closeTime(),
                        Duration.between(pending.arrivedAt, now).toMillis());
                pending.clear();
            }
            if (pending.indicator != null
                    && Duration.between(pending.arrivedAt, now).compareTo(skewTolerance) > 0) {
                LOG.warn(
                        "stop.watcher.indicator_skew: dropping stale pending indicator symbol={} indicatorTs={} ageMs={}",
                        symbol,
                        pending.indicator.timestamp(),
                        Duration.between(pending.arrivedAt, now).toMillis());
                pending.clear();
            }

            // Try to join with a still-fresh counterpart.
            if (arrivingBar != null && pending.indicator != null) {
                if (pending.indicator.timestamp().equals(arrivingTs)) {
                    IndicatorSnapshot ind = pending.indicator;
                    pending.clear();
                    listener.onJoined(arrivingBar, ind);
                    return;
                } else {
                    // Mismatched timestamps — drop the older pending indicator.
                    LOG.warn(
                            "stop.watcher.indicator_skew: dropping mismatched pending indicator symbol={} pendingTs={} arrivingBarTs={}",
                            symbol,
                            pending.indicator.timestamp(),
                            arrivingTs);
                    pending.clear();
                }
            }
            if (arrivingIndicator != null && pending.bar != null) {
                if (pending.bar.closeTime().equals(arrivingTs)) {
                    Bar bar = pending.bar;
                    pending.clear();
                    listener.onJoined(bar, arrivingIndicator);
                    return;
                } else {
                    LOG.warn(
                            "stop.watcher.indicator_skew: dropping mismatched pending bar symbol={} pendingTs={} arrivingIndicatorTs={}",
                            symbol,
                            pending.bar.closeTime(),
                            arrivingTs);
                    pending.clear();
                }
            }

            // No partner present — stash the new arrival.
            pending.arrivedAt = now;
            pending.bar = arrivingBar;
            pending.indicator = arrivingIndicator;
        }
    }

    /** Listener fired when a (bar, indicator) tuple is joined for the same symbol/timestamp. */
    @FunctionalInterface
    public interface Listener {
        void onJoined(Bar bar, IndicatorSnapshot indicator);
    }

    /** Per-symbol pending slot. Mutated under the synchronized block above. */
    private static final class Pending {
        Instant arrivedAt;
        Bar bar;
        IndicatorSnapshot indicator;

        void clear() {
            this.arrivedAt = null;
            this.bar = null;
            this.indicator = null;
        }
    }
}
