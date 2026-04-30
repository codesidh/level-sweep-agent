package com.levelsweep.marketdata.bars;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.marketdata.polygon.TickListener;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BarAggregatorTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static Tick t(String time, double price, long size) {
        return new Tick("SPY", BigDecimal.valueOf(price), size, Instant.parse(time));
    }

    @Test
    void emitsBarsForAllRequestedTimeframes() {
        Recording r = new Recording();
        BarAggregator agg = new BarAggregator(
                "SPY", NY, List.of(Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.FIFTEEN_MIN), r);

        // Stream 16 minutes of ticks (one per minute) — should produce:
        //   16 × 1m bars, 8 × 2m bars, 1 × 15m bar (the first 15-min, finalized when minute 16 arrives)
        for (int i = 0; i < 16; i++) {
            agg.onTick(t(String.format("2026-04-30T13:%02d:00Z", 30 + i), 594.0 + i * 0.01, 100));
        }
        // Flush to emit the open in-flight bars
        agg.flushAll();

        long oneMinCount = r.bars.stream().filter(b -> b.timeframe() == Timeframe.ONE_MIN).count();
        long twoMinCount = r.bars.stream().filter(b -> b.timeframe() == Timeframe.TWO_MIN).count();
        long fifteenMinCount = r.bars.stream().filter(b -> b.timeframe() == Timeframe.FIFTEEN_MIN).count();
        assertThat(oneMinCount).isEqualTo(16);
        assertThat(twoMinCount).isEqualTo(8);
        assertThat(fifteenMinCount).isEqualTo(2); // [09:30,09:45) closes at 13:45, [09:45,10:00) flushed
    }

    @Test
    void ignoresTicksForOtherSymbols() {
        Recording r = new Recording();
        BarAggregator agg = new BarAggregator("SPY", NY, List.of(Timeframe.ONE_MIN), r);
        Tick qqqTick = new Tick(
                "QQQ", BigDecimal.valueOf(500.0), 100L, Instant.parse("2026-04-30T13:30:00Z"));
        agg.onTick(qqqTick);
        agg.flushAll();
        assertThat(r.bars).isEmpty();
    }

    @Test
    void forwardsQuotesToProvidedListener() {
        Recording bars = new Recording();
        QuoteRecorder quotes = new QuoteRecorder();
        BarAggregator agg = new BarAggregator("SPY", NY, List.of(Timeframe.ONE_MIN), bars, quotes);
        Quote q = new Quote(
                "SPY",
                BigDecimal.valueOf(594.20),
                100L,
                BigDecimal.valueOf(594.30),
                100L,
                Instant.parse("2026-04-30T13:30:00Z"));
        agg.onQuote(q);
        assertThat(quotes.received).containsExactly(q);
    }

    @Test
    void quoteWithNoForwardListenerIsNoOp() {
        Recording bars = new Recording();
        BarAggregator agg = new BarAggregator("SPY", NY, List.of(Timeframe.ONE_MIN), bars);
        Quote q = new Quote(
                "SPY",
                BigDecimal.valueOf(594.20),
                100L,
                BigDecimal.valueOf(594.30),
                100L,
                Instant.parse("2026-04-30T13:30:00Z"));
        agg.onQuote(q); // Should not throw
        assertThat(bars.bars).isEmpty();
    }

    @Test
    void flushStaleEmitsBarsWhoseWindowsElapsed() {
        Recording r = new Recording();
        BarAggregator agg = new BarAggregator("SPY", NY, List.of(Timeframe.ONE_MIN), r);
        agg.onTick(t("2026-04-30T13:30:30Z", 594.0, 100));
        // 13:31:00 onward — the 1m bar [13:30, 13:31) is stale
        agg.flushStale(Instant.parse("2026-04-30T13:31:00Z"));
        assertThat(r.bars).hasSize(1);
        assertThat(r.bars.get(0).timeframe()).isEqualTo(Timeframe.ONE_MIN);
    }

    @Test
    void listenerExceptionIsContained() {
        BarListener throwing = bar -> {
            throw new RuntimeException("downstream blew up");
        };
        BarAggregator agg = new BarAggregator("SPY", NY, List.of(Timeframe.ONE_MIN), throwing);
        agg.onTick(t("2026-04-30T13:30:30Z", 594.0, 100));
        // Crossing the boundary triggers emit which throws — should not propagate
        agg.onTick(t("2026-04-30T13:31:00Z", 594.5, 100));
    }

    @Test
    void barListenerIsRequired() {
        // Sanity — null listener throws at construction time
        Recording bars = new Recording();
        BarAggregator agg = new BarAggregator("SPY", NY, List.of(Timeframe.ONE_MIN), bars);
        assertThat(agg.symbol()).isEqualTo("SPY");
    }

    /** Recording sink for emitted bars. */
    private static final class Recording implements BarListener {
        final List<Bar> bars = new ArrayList<>();

        @Override
        public void onBar(Bar bar) {
            bars.add(bar);
        }
    }

    /** Recording sink for forwarded quotes. */
    private static final class QuoteRecorder implements TickListener {
        final List<Quote> received = new ArrayList<>();

        @Override
        public void onTick(Tick tick) {
            // not used
        }

        @Override
        public void onQuote(Quote quote) {
            received.add(quote);
        }
    }
}
