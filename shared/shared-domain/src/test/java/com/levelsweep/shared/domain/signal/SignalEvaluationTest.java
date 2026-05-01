package com.levelsweep.shared.domain.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.options.OptionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SignalEvaluationTest {

    private static final Instant T0 = Instant.parse("2026-04-30T13:32:00Z");

    @Test
    void skipFactoryProducesSkipWithCopiedReasons() {
        List<String> mutableReasons = new ArrayList<>();
        mutableReasons.add("emas_warming_up");

        SignalEvaluation eval = SignalEvaluation.skip("OWNER", "SPY", T0, mutableReasons);

        assertThat(eval.action()).isEqualTo(SignalAction.SKIP);
        assertThat(eval.level()).isEmpty();
        assertThat(eval.optionSide()).isEmpty();
        assertThat(eval.levelPrice()).isEmpty();
        assertThat(eval.reasons()).containsExactly("emas_warming_up");

        // Mutating the source list must not affect the record.
        mutableReasons.add("late_addition");
        assertThat(eval.reasons()).containsExactly("emas_warming_up");
    }

    @Test
    void skipFactoryRejectsEmptyReasons() {
        assertThatThrownBy(() -> SignalEvaluation.skip("OWNER", "SPY", T0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one reason");
    }

    @Test
    void enterFactoryProducesLongCallEntry() {
        SignalEvaluation eval = SignalEvaluation.enter(
                "OWNER",
                "SPY",
                T0,
                SignalAction.ENTER_LONG,
                SweptLevel.PDL,
                OptionSide.CALL,
                new BigDecimal("593.00"),
                List.of("sweep:PDL", "ema_stack:LONG_OK"));

        assertThat(eval.action()).isEqualTo(SignalAction.ENTER_LONG);
        assertThat(eval.level()).contains(SweptLevel.PDL);
        assertThat(eval.optionSide()).contains(OptionSide.CALL);
        assertThat(eval.levelPrice()).contains(new BigDecimal("593.00"));
        assertThat(eval.reasons()).containsExactly("sweep:PDL", "ema_stack:LONG_OK");
    }

    @Test
    void enterFactoryProducesShortPutEntry() {
        SignalEvaluation eval = SignalEvaluation.enter(
                "OWNER",
                "SPY",
                T0,
                SignalAction.ENTER_SHORT,
                SweptLevel.PDH,
                OptionSide.PUT,
                new BigDecimal("595.00"),
                List.of("sweep:PDH"));

        assertThat(eval.optionSide()).contains(OptionSide.PUT);
    }

    @Test
    void canonicalConstructorRejectsLongActionWithPutSide() {
        assertThatThrownBy(() -> new SignalEvaluation(
                        "OWNER",
                        "SPY",
                        T0,
                        SignalAction.ENTER_LONG,
                        Optional.of(SweptLevel.PDL),
                        Optional.of(OptionSide.PUT),
                        Optional.of(BigDecimal.ONE),
                        List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("optionSide=CALL");
    }

    @Test
    void canonicalConstructorRejectsShortActionWithCallSide() {
        assertThatThrownBy(() -> new SignalEvaluation(
                        "OWNER",
                        "SPY",
                        T0,
                        SignalAction.ENTER_SHORT,
                        Optional.of(SweptLevel.PDH),
                        Optional.of(OptionSide.CALL),
                        Optional.of(BigDecimal.ONE),
                        List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("optionSide=PUT");
    }

    @Test
    void canonicalConstructorRejectsSkipWithLevel() {
        assertThatThrownBy(() -> new SignalEvaluation(
                        "OWNER",
                        "SPY",
                        T0,
                        SignalAction.SKIP,
                        Optional.of(SweptLevel.PDH),
                        Optional.empty(),
                        Optional.empty(),
                        List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKIP must have empty");
    }

    @Test
    void canonicalConstructorRejectsEnterWithoutLevel() {
        assertThatThrownBy(() -> new SignalEvaluation(
                        "OWNER",
                        "SPY",
                        T0,
                        SignalAction.ENTER_LONG,
                        Optional.empty(),
                        Optional.of(OptionSide.CALL),
                        Optional.of(BigDecimal.ONE),
                        List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires level");
    }

    @Test
    void canonicalConstructorRejectsNullCollaborators() {
        assertThatThrownBy(() -> SignalEvaluation.skip(null, "SPY", T0, List.of("r")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SignalEvaluation.skip("OWNER", null, T0, List.of("r")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SignalEvaluation.skip("OWNER", "SPY", null, List.of("r")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SignalEvaluation.skip("OWNER", "SPY", T0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reasonsListIsUnmodifiable() {
        SignalEvaluation eval = SignalEvaluation.skip("OWNER", "SPY", T0, List.of("a", "b"));
        assertThatThrownBy(() -> eval.reasons().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }
}
