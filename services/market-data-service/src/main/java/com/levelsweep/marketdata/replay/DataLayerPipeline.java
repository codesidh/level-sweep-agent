package com.levelsweep.marketdata.replay;

import com.levelsweep.marketdata.bars.BarAggregator;
import com.levelsweep.marketdata.bars.BarListener;
import com.levelsweep.marketdata.indicators.IndicatorEngine;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Composes BarAggregator + IndicatorEngine into the Phase 1 data-layer
 * pipeline that the {@code replay-parity} skill requires. Used by the
 * replay harness for end-to-end testing without standing up Polygon WS.
 *
 * <p>Pipeline:
 *
 * <pre>
 *   ticks → BarAggregator (1m + 2m + 15m + daily)
 *                ↓
 *           BarListener fan-out
 *                ↓
 *           IndicatorEngine (consumes 2m + daily; ignores 1m + 15m)
 *                ↓
 *           IndicatorSnapshot consumer
 * </pre>
 *
 * <p>The Level Calculator is NOT wired here — it operates on session
 * boundaries (09:29:30 ET) and is exercised separately. Phase 2 wires the
 * full Decision Engine.
 */
public final class DataLayerPipeline {

    private final String symbol;
    private final ZoneId zone;
    private final BarAggregator aggregator;
    private final IndicatorEngine indicators;
    private final List<Bar> capturedBars;
    private final List<IndicatorSnapshot> capturedSnapshots;
    private final List<Quote> capturedQuotes;

    public DataLayerPipeline(String symbol, ZoneId zone) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.capturedBars = new ArrayList<>();
        this.capturedSnapshots = new ArrayList<>();
        this.capturedQuotes = new ArrayList<>();
        // Initialize indicators first so the bar fanout lambda below can reference it as
        // a definitely-assigned final field (Java's definite-assignment rules don't track
        // lambda captures across out-of-order constructor assignments).
        Consumer<IndicatorSnapshot> snapSink = capturedSnapshots::add;
        this.indicators = new IndicatorEngine(symbol, snapSink);
        BarListener barFanout = bar -> {
            capturedBars.add(bar);
            indicators.onBar(bar);
        };
        this.aggregator = new BarAggregator(
                symbol,
                zone,
                List.of(Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.FIFTEEN_MIN, Timeframe.DAILY),
                barFanout,
                new com.levelsweep.marketdata.polygon.TickListener() {
                    @Override
                    public void onTick(Tick tick) {
                        // not used (only quotes routed here)
                    }

                    @Override
                    public void onQuote(Quote quote) {
                        capturedQuotes.add(quote);
                    }
                });
    }

    public void onTick(Tick tick) {
        aggregator.onTick(tick);
    }

    public void onQuote(Quote quote) {
        aggregator.onQuote(quote);
    }

    public void flushAll() {
        aggregator.flushAll();
    }

    public void flushStale(Instant now) {
        aggregator.flushStale(now);
    }

    public List<Bar> capturedBars() {
        return List.copyOf(capturedBars);
    }

    public List<IndicatorSnapshot> capturedSnapshots() {
        return List.copyOf(capturedSnapshots);
    }

    public List<Quote> capturedQuotes() {
        return List.copyOf(capturedQuotes);
    }

    public String symbol() {
        return symbol;
    }

    public ZoneId zone() {
        return zone;
    }
}
