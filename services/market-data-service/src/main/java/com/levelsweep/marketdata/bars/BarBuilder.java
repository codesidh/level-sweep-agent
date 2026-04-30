package com.levelsweep.marketdata.bars;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds bars for a single (symbol, timeframe) pair from incoming ticks.
 *
 * <p>Stateful; not thread-safe. The {@link BarAggregator} composes one
 * builder per timeframe and serializes calls.
 *
 * <p>Behavior:
 *
 * <ol>
 *   <li>First tick opens an "in-flight" bar at {@code Timeframe.floor(tickTime)}.
 *   <li>Subsequent ticks within the same boundary update high/low/close/volume/ticks.
 *   <li>A tick whose floor differs from the in-flight bar's floor causes the
 *       in-flight bar to close and emit; a new in-flight bar is opened at the
 *       new floor.
 *   <li>{@link #flushIfStale(Instant)} can be called periodically to close
 *       the current bar if its boundary has elapsed even without a new tick
 *       (used at session close / EOD).
 * </ol>
 *
 * <p>Empty bars (no ticks) are skipped — Phase 1 strategy doesn't consume
 * them. If gap-filling is needed later (e.g., for charts), do it in a
 * downstream materializer.
 */
public final class BarBuilder {

    private final String symbol;
    private final Timeframe timeframe;
    private final ZoneId zone;

    // In-flight bar state. Null when no bar is open.
    private Instant openTime;
    private Instant closeTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;
    private long ticks;

    public BarBuilder(String symbol, Timeframe timeframe, ZoneId zone) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.timeframe = Objects.requireNonNull(timeframe, "timeframe");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * Apply a tick. Returns {@code Optional.of(emittedBar)} if this tick
     * caused the previous in-flight bar to close, otherwise {@code empty()}.
     * The new in-flight bar continues internally; call again with the next tick.
     */
    public Optional<Bar> apply(Tick tick) {
        Objects.requireNonNull(tick, "tick");
        if (!tick.symbol().equals(symbol)) {
            throw new IllegalArgumentException("tick symbol mismatch: expected=" + symbol + " got=" + tick.symbol());
        }
        ZonedDateTime tsZ = tick.timestamp().atZone(zone);
        ZonedDateTime barOpenZ = timeframe.floor(tsZ);
        Instant barOpenInstant = barOpenZ.toInstant();
        Instant barCloseInstant = barOpenZ.plus(timeframe.duration()).toInstant();

        Optional<Bar> emitted = Optional.empty();
        if (openTime == null) {
            openInFlight(barOpenInstant, barCloseInstant, tick);
            return emitted;
        }
        if (!barOpenInstant.equals(openTime)) {
            // Tick belongs to a later bar — close the in-flight bar and start a new one.
            // Note: ticks arriving with timestamp < openTime are out-of-order; we drop them
            //       to preserve replay determinism (would require reordering). Out-of-order
            //       arrivals from Polygon are extremely rare (sequence numbers within ms).
            if (barOpenInstant.isAfter(openTime)) {
                emitted = Optional.of(closeInFlight());
                openInFlight(barOpenInstant, barCloseInstant, tick);
            }
            return emitted;
        }
        // Same bar — update aggregates
        if (tick.price().compareTo(high) > 0) {
            high = tick.price();
        }
        if (tick.price().compareTo(low) < 0) {
            low = tick.price();
        }
        close = tick.price();
        volume += tick.size();
        ticks += 1;
        return emitted;
    }

    /**
     * If the in-flight bar's window has fully elapsed before {@code now},
     * close and emit it. Used at session close to drain bars when ticks stop.
     */
    public Optional<Bar> flushIfStale(Instant now) {
        if (openTime == null) {
            return Optional.empty();
        }
        if (now.isBefore(closeTime)) {
            return Optional.empty();
        }
        return Optional.of(closeInFlight());
    }

    /** Force-close any in-flight bar (e.g., on session boundary or shutdown). */
    public Optional<Bar> flush() {
        if (openTime == null) {
            return Optional.empty();
        }
        return Optional.of(closeInFlight());
    }

    public String symbol() {
        return symbol;
    }

    public Timeframe timeframe() {
        return timeframe;
    }

    public boolean hasInFlight() {
        return openTime != null;
    }

    private void openInFlight(Instant barOpen, Instant barClose, Tick tick) {
        openTime = barOpen;
        closeTime = barClose;
        open = tick.price();
        high = tick.price();
        low = tick.price();
        close = tick.price();
        volume = tick.size();
        ticks = 1;
    }

    private Bar closeInFlight() {
        Bar bar = new Bar(symbol, timeframe, openTime, closeTime, open, high, low, close, volume, ticks);
        openTime = null;
        closeTime = null;
        open = high = low = close = null;
        volume = 0;
        ticks = 0;
        return bar;
    }
}
