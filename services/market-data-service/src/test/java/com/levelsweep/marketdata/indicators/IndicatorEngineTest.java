package com.levelsweep.marketdata.indicators;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndicatorEngineTest {

    private static Bar twoMinBar(double close, int minuteOffset) {
        Instant openTime = Instant.parse("2026-04-30T13:30:00Z").plusSeconds(minuteOffset * 60L);
        Instant closeTime = openTime.plusSeconds(120L);
        return new Bar(
                "SPY",
                Timeframe.TWO_MIN,
                openTime,
                closeTime,
                BigDecimal.valueOf(close - 0.05),
                BigDecimal.valueOf(close + 0.10),
                BigDecimal.valueOf(close - 0.10),
                BigDecimal.valueOf(close),
                100L,
                10L);
    }

    private static Bar dailyBar(double open, double high, double low, double close, int dayOffset) {
        Instant openTime = Instant.parse("2026-04-01T04:00:00Z").plusSeconds(dayOffset * 86400L);
        Instant closeTime = openTime.plusSeconds(86400L);
        return new Bar(
                "SPY",
                Timeframe.DAILY,
                openTime,
                closeTime,
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                1_000_000L,
                100_000L);
    }

    @Test
    void emitsSnapshotsOnTwoMinBars() {
        List<IndicatorSnapshot> emitted = new ArrayList<>();
        IndicatorEngine eng = new IndicatorEngine("SPY", emitted::add);
        for (int i = 0; i < 5; i++) {
            eng.onBar(twoMinBar(594.0 + i * 0.1, i));
        }
        assertThat(emitted).hasSize(5);
        // First snapshots' EMAs are still null (period 13 not reached); ATR null too
        assertThat(emitted.get(0).emasReady()).isFalse();
    }

    @Test
    void emasBecomeReadyAfterBootstrap() {
        List<IndicatorSnapshot> emitted = new ArrayList<>();
        IndicatorEngine eng = new IndicatorEngine("SPY", emitted::add);
        // Need 200 bars for ema200 to be ready
        for (int i = 0; i < 200; i++) {
            eng.onBar(twoMinBar(594.0 + i * 0.01, i));
        }
        IndicatorSnapshot last = emitted.get(emitted.size() - 1);
        assertThat(last.emasReady()).isTrue();
        assertThat(last.ema13()).isNotNull();
        assertThat(last.ema48()).isNotNull();
        assertThat(last.ema200()).isNotNull();
    }

    @Test
    void atr14WiredFromDailyBars() {
        List<IndicatorSnapshot> emitted = new ArrayList<>();
        IndicatorEngine eng = new IndicatorEngine("SPY", emitted::add);
        // Bootstrap ATR14 with 14 daily bars
        for (int i = 0; i < 14; i++) {
            eng.onBar(dailyBar(100.0 + i, 102.0 + i, 98.0 + i, 101.0 + i, i));
        }
        // No 2-min bars yet → no snapshots emitted
        assertThat(emitted).isEmpty();
        // One 2-min bar: ATR is now in the snapshot (carried forward)
        eng.onBar(twoMinBar(594.0, 0));
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).atr14()).isNotNull();
    }

    @Test
    void ignoresTicksForOtherSymbols() {
        List<IndicatorSnapshot> emitted = new ArrayList<>();
        IndicatorEngine eng = new IndicatorEngine("SPY", emitted::add);
        Bar foreign = new Bar(
                "QQQ",
                Timeframe.TWO_MIN,
                Instant.parse("2026-04-30T13:30:00Z"),
                Instant.parse("2026-04-30T13:32:00Z"),
                BigDecimal.valueOf(500.0),
                BigDecimal.valueOf(500.5),
                BigDecimal.valueOf(499.8),
                BigDecimal.valueOf(500.2),
                100L,
                10L);
        eng.onBar(foreign);
        assertThat(emitted).isEmpty();
    }

    @Test
    void ignoresOtherTimeframes() {
        // 1-min and 15-min bars are not consumed by this engine
        List<IndicatorSnapshot> emitted = new ArrayList<>();
        IndicatorEngine eng = new IndicatorEngine("SPY", emitted::add);
        Bar oneMin = new Bar(
                "SPY",
                Timeframe.ONE_MIN,
                Instant.parse("2026-04-30T13:30:00Z"),
                Instant.parse("2026-04-30T13:31:00Z"),
                BigDecimal.valueOf(594.0),
                BigDecimal.valueOf(594.5),
                BigDecimal.valueOf(593.8),
                BigDecimal.valueOf(594.2),
                100L,
                10L);
        Bar fifteenMin = new Bar(
                "SPY",
                Timeframe.FIFTEEN_MIN,
                Instant.parse("2026-04-30T13:30:00Z"),
                Instant.parse("2026-04-30T13:45:00Z"),
                BigDecimal.valueOf(594.0),
                BigDecimal.valueOf(595.0),
                BigDecimal.valueOf(593.5),
                BigDecimal.valueOf(594.5),
                1500L,
                150L);
        eng.onBar(oneMin);
        eng.onBar(fifteenMin);
        assertThat(emitted).isEmpty();
    }

    @Test
    void latestExposesMostRecentSnapshot() {
        List<IndicatorSnapshot> emitted = new ArrayList<>();
        IndicatorEngine eng = new IndicatorEngine("SPY", emitted::add);
        eng.onBar(twoMinBar(594.0, 0));
        eng.onBar(twoMinBar(594.5, 1));
        assertThat(eng.latest()).isEqualTo(emitted.get(1));
    }

    @Test
    void sinkExceptionIsContained() {
        IndicatorEngine eng = new IndicatorEngine("SPY", snap -> {
            throw new RuntimeException("downstream blew up");
        });
        // Should not propagate
        eng.onBar(twoMinBar(594.0, 0));
    }
}
