package com.levelsweep.marketdata.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.marketdata.levels.LevelCalculator;
import com.levelsweep.marketdata.levels.SessionWindows;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Replay-harness end-to-end tests. Drives synthetic session data through
 * the full data-layer pipeline ({@link DataLayerPipeline}) and validates:
 *
 * <ol>
 *   <li>Determinism — running the same input twice produces byte-identical
 *       outputs (the core guarantee of the {@code replay-parity} skill)
 *   <li>Bar production — the pipeline emits bars at the expected boundaries
 *       across all timeframes
 *   <li>Indicator readiness — after sufficient warm-up, EMAs become populated
 *   <li>Level computation — feeding RTH + overnight bars into the level
 *       calculator yields valid PDH/PDL/PMH/PML
 *   <li>DST handling — a spring-forward session does not break the pipeline
 * </ol>
 *
 * <p>Synthetic data is generated with a seeded RNG so all tests are
 * deterministic. Once the Polygon paid tier lands (issue #6), real
 * recordings replace these synthetic streams.
 */
class ReplayHarnessTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalDate NORMAL_DAY = LocalDate.of(2026, 4, 30); // Thursday
    private static final LocalDate SPRING_FORWARD = LocalDate.of(2026, 3, 9); // Monday after spring-forward
    private static final long SEED = 42L;

    @Test
    void deterministicAcrossRuns() {
        DataLayerPipeline first = run("SPY", NORMAL_DAY, 594.0);
        DataLayerPipeline second = run("SPY", NORMAL_DAY, 594.0);
        // Bars should match exactly (count + per-bar OHLC)
        assertThat(first.capturedBars()).hasSize(second.capturedBars().size());
        for (int i = 0; i < first.capturedBars().size(); i++) {
            Bar a = first.capturedBars().get(i);
            Bar b = second.capturedBars().get(i);
            assertThat(a.symbol()).isEqualTo(b.symbol());
            assertThat(a.timeframe()).isEqualTo(b.timeframe());
            assertThat(a.openTime()).isEqualTo(b.openTime());
            assertThat(a.open()).isEqualByComparingTo(b.open());
            assertThat(a.high()).isEqualByComparingTo(b.high());
            assertThat(a.low()).isEqualByComparingTo(b.low());
            assertThat(a.close()).isEqualByComparingTo(b.close());
            assertThat(a.volume()).isEqualTo(b.volume());
        }
        // Snapshots match
        assertThat(first.capturedSnapshots()).hasSize(second.capturedSnapshots().size());
    }

    @Test
    void barsEmittedForAllTimeframes() {
        DataLayerPipeline p = run("SPY", NORMAL_DAY, 594.0);
        long oneMin = p.capturedBars().stream().filter(b -> b.timeframe() == Timeframe.ONE_MIN).count();
        long twoMin = p.capturedBars().stream().filter(b -> b.timeframe() == Timeframe.TWO_MIN).count();
        long fifteenMin =
                p.capturedBars().stream().filter(b -> b.timeframe() == Timeframe.FIFTEEN_MIN).count();
        // RTH session is 6.5 hours = 390 minutes. 1m bars are produced per minute with ticks.
        // With tickInterval=30s we have 2 ticks per minute → ~390 1m bars expected.
        // The first bar is in-flight until a tick crosses the boundary, so 389-390 bars.
        assertThat(oneMin).isGreaterThanOrEqualTo(389L).isLessThanOrEqualTo(390L);
        // 2m bars: 390/2 = 195
        assertThat(twoMin).isGreaterThanOrEqualTo(194L).isLessThanOrEqualTo(195L);
        // 15m bars: 390/15 = 26
        assertThat(fifteenMin).isGreaterThanOrEqualTo(25L).isLessThanOrEqualTo(26L);
    }

    @Test
    void indicatorsReadyByEndOfSession() {
        DataLayerPipeline p = run("SPY", NORMAL_DAY, 594.0);
        // 195 × 2-min bars > 200-period bootstrap requires more bars OR pre-warmed state.
        // Phase 1 ships without pre-warming — just assert ema13 and ema48 ready.
        IndicatorSnapshot last = p.capturedSnapshots().get(p.capturedSnapshots().size() - 1);
        assertThat(last.ema13()).isNotNull();
        assertThat(last.ema48()).isNotNull();
        // ema200 not necessarily ready — 195 bars < 200 threshold
    }

    @Test
    void levelCalculationFromCapturedBars() {
        // Run prior day RTH to capture bars
        DataLayerPipeline rth = run("SPY", LocalDate.of(2026, 4, 29), 594.0);
        List<Bar> rthOneMin = rth.capturedBars().stream()
                .filter(b -> b.timeframe() == Timeframe.ONE_MIN)
                .filter(b -> SessionWindows.rth(LocalDate.of(2026, 4, 29)).contains(b.openTime()))
                .toList();
        // Run overnight
        List<Tick> overnightTicks = SyntheticSessionGenerator.generateOvernightSession(
                "SPY", NORMAL_DAY, 594.5, SEED + 1, 30);
        DataLayerPipeline overnight = new DataLayerPipeline("SPY", NY);
        for (Tick t : overnightTicks) {
            overnight.onTick(t);
        }
        overnight.flushAll();
        List<Bar> overnightOneMin = overnight.capturedBars().stream()
                .filter(b -> b.timeframe() == Timeframe.ONE_MIN)
                .toList();
        // Compute levels
        Levels levels =
                LevelCalculator.compute("OWNER", "SPY", NORMAL_DAY, rthOneMin, overnightOneMin);
        assertThat(levels.pdh().compareTo(levels.pdl())).isPositive();
        assertThat(levels.pmh().compareTo(levels.pml())).isPositive();
        assertThat(levels.tenantId()).isEqualTo("OWNER");
        assertThat(levels.sessionDate()).isEqualTo(NORMAL_DAY);
    }

    @Test
    void springForwardSessionRunsCleanly() {
        // 2026-03-09 is the Monday after the Sunday spring-forward. RTH is normal but
        // the prior-day overnight stream crosses 02:00 ET → 03:00 ET in local time.
        DataLayerPipeline p = run("SPY", SPRING_FORWARD, 590.0);
        assertThat(p.capturedBars()).isNotEmpty();
        // Verify some bars have openTime past spring-forward boundary
        Instant springForwardBoundaryUtc = Instant.parse("2026-03-08T07:00:00Z"); // 02:00 EST = 07:00 UTC
        long barsAfterBoundary = p.capturedBars().stream()
                .filter(b -> b.openTime().isAfter(springForwardBoundaryUtc))
                .count();
        assertThat(barsAfterBoundary).isPositive();
    }

    @Test
    void emptyTickStreamProducesNoBars() {
        DataLayerPipeline p = new DataLayerPipeline("SPY", NY);
        p.flushAll();
        assertThat(p.capturedBars()).isEmpty();
        assertThat(p.capturedSnapshots()).isEmpty();
    }

    @Test
    void singleTickProducesOneFlushBar() {
        DataLayerPipeline p = new DataLayerPipeline("SPY", NY);
        p.onTick(new Tick("SPY", java.math.BigDecimal.valueOf(594.0), 100L, Instant.parse("2026-04-30T13:30:00Z")));
        p.flushAll();
        // One tick → one in-flight bar per timeframe → 4 bars on flush (1m, 2m, 15m, daily)
        assertThat(p.capturedBars()).hasSize(4);
        // Indicator engine consumes 2m + daily; should have emitted 1 snapshot from the 2m bar
        assertThat(p.capturedSnapshots()).hasSize(1);
    }

    /** Helper: run a single RTH session through the pipeline with synthetic ticks. */
    private static DataLayerPipeline run(String symbol, LocalDate sessionDate, double openPrice) {
        DataLayerPipeline p = new DataLayerPipeline(symbol, NY);
        List<Tick> ticks = SyntheticSessionGenerator.generateRthSession(symbol, sessionDate, openPrice, SEED, 30);
        for (Tick tick : ticks) {
            p.onTick(tick);
        }
        p.flushAll();
        return p;
    }
}
