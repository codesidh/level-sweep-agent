package com.levelsweep.marketdata.bars;

import com.levelsweep.marketdata.polygon.TickListener;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribes to a tick stream (via {@link TickListener}) and emits completed
 * bars to a {@link BarListener} for each requested {@link Timeframe}.
 *
 * <p>Cascading is intentionally NOT implemented (e.g., 1m-bars-feeding-2m-bars).
 * Each timeframe builds independently from raw ticks. This keeps each builder
 * pure and avoids ordering subtleties between cascades. The Indicator Engine
 * (#13) consumes whichever timeframe(s) it needs.
 *
 * <p>Quotes are forwarded to a separate {@link com.levelsweep.marketdata.polygon.TickListener}
 * if registered (the trailing manager will subscribe in Phase 3). Quotes do
 * not contribute to bars.
 */
public final class BarAggregator implements TickListener {

    private static final Logger LOG = LoggerFactory.getLogger(BarAggregator.class);

    private final String symbol;
    private final ZoneId zone;
    private final BarListener barListener;
    private final TickListener quoteForward;
    private final Map<Timeframe, BarBuilder> builders;

    public BarAggregator(String symbol, ZoneId zone, List<Timeframe> timeframes, BarListener barListener) {
        this(symbol, zone, timeframes, barListener, null);
    }

    public BarAggregator(
            String symbol,
            ZoneId zone,
            List<Timeframe> timeframes,
            BarListener barListener,
            TickListener quoteForward) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.barListener = Objects.requireNonNull(barListener, "barListener");
        this.quoteForward = quoteForward;
        Objects.requireNonNull(timeframes, "timeframes");
        if (timeframes.isEmpty()) {
            throw new IllegalArgumentException("at least one timeframe required");
        }
        this.builders = new EnumMap<>(Timeframe.class);
        for (Timeframe tf : timeframes) {
            this.builders.put(tf, new BarBuilder(symbol, tf, zone));
        }
    }

    @Override
    public void onTick(Tick tick) {
        if (!tick.symbol().equals(symbol)) {
            // Not our symbol; ignore. (Multi-symbol aggregators register one BarAggregator
            // per symbol upstream of the listener fan-out.)
            return;
        }
        for (Map.Entry<Timeframe, BarBuilder> e : builders.entrySet()) {
            try {
                Optional<com.levelsweep.shared.domain.marketdata.Bar> emitted =
                        e.getValue().apply(tick);
                emitted.ifPresent(this::emit);
            } catch (Exception ex) {
                LOG.warn("BarBuilder({}) threw on tick — continuing", e.getKey(), ex);
            }
        }
    }

    @Override
    public void onQuote(Quote quote) {
        if (quoteForward != null) {
            try {
                quoteForward.onQuote(quote);
            } catch (Exception e) {
                LOG.warn("quote-forward listener threw — continuing", e);
            }
        }
    }

    /**
     * Drive any in-flight bars whose boundary has elapsed. Call periodically
     * (e.g., once per second from a scheduler) so quiet symbols still emit
     * bars on time. At session close, also call {@link #flushAll()}.
     */
    public void flushStale(Instant now) {
        for (BarBuilder b : builders.values()) {
            try {
                b.flushIfStale(now).ifPresent(this::emit);
            } catch (Exception ex) {
                LOG.warn("BarBuilder({}) flushIfStale threw — continuing", b.timeframe(), ex);
            }
        }
    }

    /** Force-close every in-flight bar regardless of boundary status. */
    public void flushAll() {
        for (BarBuilder b : builders.values()) {
            try {
                b.flush().ifPresent(this::emit);
            } catch (Exception ex) {
                LOG.warn("BarBuilder({}) flush threw — continuing", b.timeframe(), ex);
            }
        }
    }

    public String symbol() {
        return symbol;
    }

    private void emit(com.levelsweep.shared.domain.marketdata.Bar bar) {
        try {
            barListener.onBar(bar);
        } catch (Exception e) {
            LOG.warn("BarListener threw on emit — continuing", e);
        }
    }
}
