package com.levelsweep.decision.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.levelsweep.shared.domain.signal.OptionSide;
import com.levelsweep.shared.domain.signal.SignalAction;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import com.levelsweep.shared.domain.signal.SweptLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link SignalEvaluator}. Construct the evaluator
 * directly (no {@code @QuarkusTest}) so the suite is fast.
 *
 * <p>Test fixtures pin {@code atr14 = 1.00} so the buffer / gap / proximity
 * thresholds map cleanly to dollar amounts:
 *
 * <ul>
 *   <li>sweep buffer = 0.20 × 1.00 = $0.20
 *   <li>EMA gap floor = 0.30 × 1.00 = $0.30
 *   <li>near-level cap = 0.50 × 1.00 = $0.50
 * </ul>
 *
 * <p>Levels: PDH=600, PDL=590, PMH=598, PML=592 — all four distinct so each
 * sweep direction can be exercised independently.
 */
class SignalEvaluatorTest {

    private static final Instant OPEN_TIME = Instant.parse("2026-04-30T13:30:00Z");
    private static final Instant CLOSE_TIME = OPEN_TIME.plus(Timeframe.TWO_MIN.duration());
    private static final LocalDate SESSION = LocalDate.of(2026, 4, 30);

    private static final BigDecimal ATR = new BigDecimal("1.00");

    // Defaults match @ConfigProperty defaults in production.
    private final SignalEvaluator evaluator =
            new SignalEvaluator(new BigDecimal("0.20"), new BigDecimal("0.30"), new BigDecimal("0.50"));

    private static Levels standardLevels() {
        return new Levels(
                "OWNER",
                "SPY",
                SESSION,
                new BigDecimal("600.00"),
                new BigDecimal("590.00"),
                new BigDecimal("598.00"),
                new BigDecimal("592.00"));
    }

    private static IndicatorSnapshot bullishSnapshot() {
        return new IndicatorSnapshot(
                "SPY",
                CLOSE_TIME,
                new BigDecimal("595.00"),
                new BigDecimal("594.00"),
                new BigDecimal("593.00"),
                ATR);
    }

    private static IndicatorSnapshot bearishSnapshot() {
        return new IndicatorSnapshot(
                "SPY",
                CLOSE_TIME,
                new BigDecimal("593.00"),
                new BigDecimal("594.00"),
                new BigDecimal("595.00"),
                ATR);
    }

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

    // ---- Pre-check skips ---------------------------------------------------

    @Test
    void skipsWhenTimeframeIsNotTwoMin() {
        Bar fifteen = bar(Timeframe.FIFTEEN_MIN, "590.10", "590.30", "589.50", "590.20");
        SignalEvaluation eval = evaluator.evaluate(fifteen, bullishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.reasons()).containsExactly("wrong_timeframe:FIFTEEN_MIN");
    }

    @Test
    void skipsWhenEmasWarmingUp() {
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");
        IndicatorSnapshot warming = new IndicatorSnapshot("SPY", CLOSE_TIME, null, new BigDecimal("594.00"), null, ATR);

        SignalEvaluation eval = evaluator.evaluate(twoMin, warming, standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.reasons()).containsExactly("emas_warming_up");
    }

    @Test
    void skipsWhenAtrWarmingUp() {
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");
        IndicatorSnapshot noAtr = new IndicatorSnapshot(
                "SPY",
                CLOSE_TIME,
                new BigDecimal("595.00"),
                new BigDecimal("594.00"),
                new BigDecimal("593.00"),
                null);

        SignalEvaluation eval = evaluator.evaluate(twoMin, noAtr, standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.reasons()).containsExactly("atr_warming_up");
    }

    // ---- Sweep skips -------------------------------------------------------

    @Test
    void skipsWhenNoLevelSwept() {
        // Bar trades comfortably inside all four levels, no wick beyond.
        Bar twoMin = bar(Timeframe.TWO_MIN, "595.00", "595.50", "594.50", "595.20");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bullishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.reasons()).containsExactly("sweep:none");
    }

    @Test
    void skipsWhenWickPiercesButCloseStaysBelowLevelSoNoReversal() {
        // High pierces above PDH=600 but close=600.10 is still above (no reversal back below).
        // Algorithm: PDH SHORT requires close < pdh — here close > pdh, so no sweep.
        Bar twoMin = bar(Timeframe.TWO_MIN, "599.90", "600.30", "599.80", "600.10");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bullishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.reasons()).containsExactly("sweep:none");
    }

    // ---- EMA stack mismatch ------------------------------------------------

    @Test
    void skipsWhenEmaStackMismatchesLongSetup() {
        // PDL LONG sweep, but EMA stack is bearish.
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bearishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.reasons())
                .containsExactly("sweep:PDL", "ema_stack_mismatch:LONG_setup_but_13<48<200");
    }

    @Test
    void skipsWhenStackOrderedButGapTooSmall() {
        // EMAs ordered bullish but ema13-ema48 gap is only 0.10 (< 0.30 floor).
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");
        IndicatorSnapshot tightStack = new IndicatorSnapshot(
                "SPY",
                CLOSE_TIME,
                new BigDecimal("594.10"),
                new BigDecimal("594.00"),
                new BigDecimal("593.00"),
                ATR);

        SignalEvaluation eval = evaluator.evaluate(twoMin, tightStack, standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.reasons()).element(0).isEqualTo("sweep:PDL");
        assertThat(eval.reasons()).element(1).asString().startsWith("ema_stack_mismatch:LONG_setup");
    }

    // ---- Far-from-level skip ----------------------------------------------

    @Test
    void skipsWhenCloseTooFarFromSweptLevel() {
        // Sweep PDL=590 — but close=590.80 is 0.80 above (> 0.50 near cap).
        // To satisfy the bar invariant low<=close<=high we need high>=590.80.
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.40", "590.85", "589.50", "590.80");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bullishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.reasons()).hasSize(3);
        assertThat(eval.reasons().get(0)).isEqualTo("sweep:PDL");
        assertThat(eval.reasons().get(1)).startsWith("ema_stack:LONG_OK");
        assertThat(eval.reasons().get(2)).startsWith("far_from_level:");
    }

    // ---- Successful entries -----------------------------------------------

    @Test
    void firesPdlLongCallSetup() {
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bullishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.ENTER_LONG);
        assertThat(eval.level()).contains(SweptLevel.PDL);
        assertThat(eval.optionSide()).contains(OptionSide.CALL);
        assertThat(eval.levelPrice()).contains(new BigDecimal("590.00"));
        assertThat(eval.reasons()).hasSize(4);
        assertThat(eval.reasons().get(0)).isEqualTo("sweep:PDL");
        assertThat(eval.reasons().get(1)).startsWith("ema_stack:LONG_OK[13>48>200");
        assertThat(eval.reasons().get(2)).startsWith("atr_buffer:0.50");
        assertThat(eval.reasons().get(3)).isEqualTo("near_level:0.20*atr");
    }

    @Test
    void firesPmlLongCallSetup() {
        // PML=592 sweep: low<591.80, close>592. close=592.20, high=592.30, low=591.50.
        Bar twoMin = bar(Timeframe.TWO_MIN, "592.10", "592.30", "591.50", "592.20");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bullishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.ENTER_LONG);
        assertThat(eval.level()).contains(SweptLevel.PML);
        assertThat(eval.optionSide()).contains(OptionSide.CALL);
    }

    @Test
    void firesPdhShortPutSetup() {
        // PDH=600 sweep: high>600.20, close<600. open=600.10, high=600.50, low=599.50, close=599.80.
        Bar twoMin = bar(Timeframe.TWO_MIN, "600.10", "600.50", "599.50", "599.80");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bearishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.ENTER_SHORT);
        assertThat(eval.level()).contains(SweptLevel.PDH);
        assertThat(eval.optionSide()).contains(OptionSide.PUT);
        assertThat(eval.levelPrice()).contains(new BigDecimal("600.00"));
    }

    @Test
    void firesPmhShortPutSetup() {
        // PMH=598 sweep: high>598.20, close<598. open=598.10, high=598.30, low=597.50, close=597.80.
        Bar twoMin = bar(Timeframe.TWO_MIN, "598.10", "598.30", "597.50", "597.80");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bearishSnapshot(), standardLevels());

        assertThat(eval.action()).isEqualTo(SignalAction.ENTER_SHORT);
        assertThat(eval.level()).contains(SweptLevel.PMH);
        assertThat(eval.optionSide()).contains(OptionSide.PUT);
    }

    // ---- Multi-sweep tie-breaking ------------------------------------------

    @Test
    void multipleSweptLevelsPickLargestWick() {
        // Use unusual levels where PDH and PMH are close together so a single bar can sweep both.
        // PDH=600.00, PMH=600.20. Bar high=600.50 sweeps both for SHORT.
        // Wick to PDH = 0.50, wick to PMH = 0.30 → PDH wins.
        Levels tightLevels = new Levels(
                "OWNER",
                "SPY",
                SESSION,
                new BigDecimal("600.00"),
                new BigDecimal("590.00"),
                new BigDecimal("600.20"),
                new BigDecimal("592.00"));
        Bar twoMin = bar(Timeframe.TWO_MIN, "600.10", "600.50", "599.50", "599.80");

        SignalEvaluation eval = evaluator.evaluate(twoMin, bearishSnapshot(), tightLevels);

        assertThat(eval.action()).isEqualTo(SignalAction.ENTER_SHORT);
        assertThat(eval.level()).contains(SweptLevel.PDH);
    }

    // ---- Determinism check -------------------------------------------------

    @Test
    void sameInputsProduceSameOutputs() {
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");
        IndicatorSnapshot snap = bullishSnapshot();
        Levels levels = standardLevels();

        SignalEvaluation a = evaluator.evaluate(twoMin, snap, levels);
        SignalEvaluation b = evaluator.evaluate(twoMin, snap, levels);

        assertThat(a).isEqualTo(b);
        assertThat(a.reasons()).isEqualTo(b.reasons());
    }

    // ---- Null-input rejection ---------------------------------------------

    @Test
    void rejectsNullArguments() {
        Bar twoMin = bar(Timeframe.TWO_MIN, "590.10", "590.30", "589.50", "590.20");
        IndicatorSnapshot snap = bullishSnapshot();
        Levels levels = standardLevels();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluator.evaluate(null, snap, levels))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluator.evaluate(twoMin, null, levels))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluator.evaluate(twoMin, snap, null))
                .isInstanceOf(NullPointerException.class);
    }
}
