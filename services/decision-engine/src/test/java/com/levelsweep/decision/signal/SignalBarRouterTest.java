package com.levelsweep.decision.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.levelsweep.shared.domain.signal.SignalAction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit test for {@link SignalBarRouter}: wires a real
 * {@link SignalEvaluator}, populated holders, and verifies the router routes
 * 2-min bars through evaluation while no-op'ing other timeframes and missing
 * holder state.
 */
class SignalBarRouterTest {

    private static final Instant OPEN_TIME = Instant.parse("2026-04-30T13:30:00Z");
    private static final BigDecimal ATR = new BigDecimal("1.00");

    private SignalEvaluator evaluator;
    private IndicatorSnapshotHolder snapshotHolder;
    private LevelsHolder levelsHolder;
    private SignalBarRouter router;

    @BeforeEach
    void setUp() {
        evaluator = new SignalEvaluator(new BigDecimal("0.20"), new BigDecimal("0.30"), new BigDecimal("0.50"));
        snapshotHolder = new IndicatorSnapshotHolder();
        levelsHolder = new LevelsHolder();
        router = new SignalBarRouter(evaluator, snapshotHolder, levelsHolder);
    }

    @Test
    void noOpsWhenBarIsNotTwoMin() {
        Bar oneMin = bar(Timeframe.ONE_MIN, "590.10", "590.30", "589.50", "590.20");
        // No exception even though holders are empty — short-circuit before holder lookup.
        router.onBar(oneMin);
        // Spot-check: holders remain empty.
        assertThat(snapshotHolder.latest()).isEmpty();
        assertThat(levelsHolder.latest()).isEmpty();
    }

    @Test
    void noOpsWhenSnapshotMissing() {
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");
        levelsHolder.setLatest(standardLevels());
        // snapshotHolder still empty.
        router.onBar(twoMin); // should not throw, just skip
    }

    @Test
    void noOpsWhenLevelsMissing() {
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");
        snapshotHolder.setLatest(bullishSnapshot());
        // levelsHolder still empty.
        router.onBar(twoMin); // should not throw
    }

    @Test
    void evaluatesAndLogsWhenAllInputsPresent() {
        // Use a recording evaluator to verify it was called and the result flows to logging.
        // Wire a custom router with the recording evaluator.
        RecordingEvaluator recording = new RecordingEvaluator();
        SignalBarRouter routerUnderTest = new SignalBarRouter(recording, snapshotHolder, levelsHolder);

        snapshotHolder.setLatest(bullishSnapshot());
        levelsHolder.setLatest(standardLevels());
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");

        routerUnderTest.onBar(twoMin);

        assertThat(recording.callCount).isEqualTo(1);
        assertThat(recording.lastBar).isEqualTo(twoMin);
    }

    @Test
    void rejectsNullBar() {
        assertThatThrownBy(() -> router.onBar(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThatThrownBy(() -> new SignalBarRouter(null, snapshotHolder, levelsHolder))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SignalBarRouter(evaluator, null, levelsHolder))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SignalBarRouter(evaluator, snapshotHolder, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void holdersStartEmptyAndAcceptUpdates() {
        assertThat(snapshotHolder.latest()).isEmpty();
        assertThat(levelsHolder.latest()).isEmpty();

        IndicatorSnapshot snap = bullishSnapshot();
        Levels levels = standardLevels();
        snapshotHolder.setLatest(snap);
        levelsHolder.setLatest(levels);

        assertThat(snapshotHolder.latest()).contains(snap);
        assertThat(levelsHolder.latest()).contains(levels);

        // Null clears.
        snapshotHolder.setLatest(null);
        assertThat(snapshotHolder.latest()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------

    private static Bar bar(Timeframe tf, String open, String high, String low, String close) {
        return new Bar(
                "SPY",
                tf,
                OPEN_TIME,
                OPEN_TIME.plus(tf.duration()),
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                1_000L,
                10L);
    }

    private static IndicatorSnapshot bullishSnapshot() {
        return new IndicatorSnapshot(
                "SPY",
                OPEN_TIME.plus(Timeframe.TWO_MIN.duration()),
                new BigDecimal("595.00"),
                new BigDecimal("594.00"),
                new BigDecimal("593.00"),
                ATR);
    }

    private static Levels standardLevels() {
        return new Levels(
                "OWNER",
                "SPY",
                LocalDate.of(2026, 4, 30),
                new BigDecimal("600.00"),
                new BigDecimal("590.00"),
                new BigDecimal("598.00"),
                new BigDecimal("592.00"));
    }

    /**
     * Recording subclass used to verify the router invoked the evaluator with the
     * expected inputs. Subclasses {@link SignalEvaluator} to bypass interface plumbing
     * — the real production class is what {@link SignalBarRouter} depends on.
     */
    private static final class RecordingEvaluator extends SignalEvaluator {
        int callCount;
        Bar lastBar;

        RecordingEvaluator() {
            super(new BigDecimal("0.20"), new BigDecimal("0.30"), new BigDecimal("0.50"));
        }

        @Override
        public com.levelsweep.shared.domain.signal.SignalEvaluation evaluate(
                Bar bar, IndicatorSnapshot snapshot, Levels levels) {
            callCount++;
            lastBar = bar;
            return com.levelsweep.shared.domain.signal.SignalEvaluation.skip(
                    levels.tenantId(),
                    bar.symbol(),
                    bar.closeTime(),
                    java.util.List.of("recorded:" + SignalAction.SKIP));
        }
    }
}
