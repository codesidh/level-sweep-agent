package com.levelsweep.marketdata.indicators;

import com.levelsweep.marketdata.bars.BarListener;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes EMA(13/48/200) on 2-min bars and ATR(14) on daily bars per
 * {@code requirements.md} §6 / §6.1.
 *
 * <p>Subscribes to {@link BarListener} (the bar aggregator from #11). On
 * every 2-min bar close, updates the EMAs and emits a snapshot to the
 * provided consumer (the Decision Engine in Phase 2 will subscribe). On
 * every daily bar close, updates ATR.
 *
 * <p>Other timeframes are ignored — the indicator engine is intentionally
 * scoped to the timeframes the strategy uses.
 *
 * <p>The latest snapshot is also exposed via {@link #latest()} for ad-hoc
 * read-only access (e.g., the AI Sentinel context in Phase 5).
 */
public final class IndicatorEngine implements BarListener {

    private static final Logger LOG = LoggerFactory.getLogger(IndicatorEngine.class);

    private final String symbol;
    private final EmaCalculator ema13;
    private final EmaCalculator ema48;
    private final EmaCalculator ema200;
    private final AtrCalculator atr14;
    private final Consumer<IndicatorSnapshot> snapshotSink;
    private volatile IndicatorSnapshot latestSnapshot;

    public IndicatorEngine(String symbol, Consumer<IndicatorSnapshot> snapshotSink) {
        this(symbol, new EmaCalculator(13), new EmaCalculator(48), new EmaCalculator(200), new AtrCalculator(14), snapshotSink);
    }

    /** Constructor allowing custom periods (used by tests). */
    public IndicatorEngine(
            String symbol,
            EmaCalculator ema13,
            EmaCalculator ema48,
            EmaCalculator ema200,
            AtrCalculator atr14,
            Consumer<IndicatorSnapshot> snapshotSink) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.ema13 = Objects.requireNonNull(ema13, "ema13");
        this.ema48 = Objects.requireNonNull(ema48, "ema48");
        this.ema200 = Objects.requireNonNull(ema200, "ema200");
        this.atr14 = Objects.requireNonNull(atr14, "atr14");
        this.snapshotSink = Objects.requireNonNull(snapshotSink, "snapshotSink");
    }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) {
            return;
        }
        try {
            if (bar.timeframe() == Timeframe.TWO_MIN) {
                onTwoMinBar(bar);
            } else if (bar.timeframe() == Timeframe.DAILY) {
                onDailyBar(bar);
            }
            // ONE_MIN, FIFTEEN_MIN ignored — the strategy uses 2m EMAs and daily ATR exclusively.
        } catch (Exception e) {
            LOG.warn("indicator update threw on bar {} — continuing", bar, e);
        }
    }

    private void onTwoMinBar(Bar bar) {
        BigDecimal close = bar.close();
        BigDecimal e13 = ema13.update(close);
        BigDecimal e48 = ema48.update(close);
        BigDecimal e200 = ema200.update(close);
        BigDecimal a14 = atr14.value(); // ATR is updated on daily bars only; carry forward.
        IndicatorSnapshot snap = new IndicatorSnapshot(symbol, bar.closeTime(), e13, e48, e200, a14);
        latestSnapshot = snap;
        try {
            snapshotSink.accept(snap);
        } catch (Exception e) {
            LOG.warn("snapshotSink threw — continuing", e);
        }
    }

    private void onDailyBar(Bar bar) {
        atr14.update(bar);
        // Daily bars don't trigger snapshot emission per se; the next 2-min
        // bar will pick up the updated ATR. (The Decision Engine evaluates
        // signals on 15-min and 2-min boundaries, not on daily.)
    }

    public IndicatorSnapshot latest() {
        return latestSnapshot;
    }

    public BigDecimal currentEma13() {
        return ema13.value();
    }

    public BigDecimal currentEma48() {
        return ema48.value();
    }

    public BigDecimal currentEma200() {
        return ema200.value();
    }

    public BigDecimal currentAtr14() {
        return atr14.value();
    }
}
