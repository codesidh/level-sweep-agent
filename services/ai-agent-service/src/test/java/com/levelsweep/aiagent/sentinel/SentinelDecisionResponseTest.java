package com.levelsweep.aiagent.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Allow;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.DecisionPath;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Fallback;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.FallbackReason;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.ReasonCode;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Veto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Validation contract for {@link SentinelDecisionResponse}. The threshold
 * (Veto requires confidence ≥ 0.85) is enforced at construction so the
 * downstream saga path can rely on the type system, not extra branching.
 */
class SentinelDecisionResponseTest {

    @Test
    void vetoAtThresholdAccepted() {
        Veto v = new Veto("REQ_1", new BigDecimal("0.85"), ReasonCode.STRUCTURE_DIVERGENCE, "diverging EMAs", 250L);
        assertThat(v.confidence()).isEqualByComparingTo("0.85");
    }

    @Test
    void vetoAboveThresholdAccepted() {
        Veto v = new Veto("REQ_1", new BigDecimal("0.99"), ReasonCode.RECENT_LOSSES, "two losses", 250L);
        assertThat(v.confidence()).isEqualByComparingTo("0.99");
    }

    @Test
    void vetoAtOneAccepted() {
        Veto v = new Veto("REQ_1", BigDecimal.ONE, ReasonCode.OTHER, "max conviction", 250L);
        assertThat(v.confidence()).isEqualByComparingTo("1");
    }

    @Test
    void vetoBelowThresholdRejected() {
        assertThatThrownBy(
                        () -> new Veto("REQ_1", new BigDecimal("0.84"), ReasonCode.STRUCTURE_DIVERGENCE, "weak", 250L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Veto confidence");
    }

    @Test
    void vetoAboveOneRejected() {
        assertThatThrownBy(() -> new Veto("REQ_1", new BigDecimal("1.01"), ReasonCode.OTHER, "x", 250L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Veto confidence");
    }

    @Test
    void allowAcceptsFullRange() {
        Allow zero = new Allow("REQ_1", BigDecimal.ZERO, ReasonCode.OTHER, "", 100L, DecisionPath.EXPLICIT_ALLOW);
        Allow one =
                new Allow("REQ_1", BigDecimal.ONE, ReasonCode.STRUCTURE_MATCH, "ok", 100L, DecisionPath.EXPLICIT_ALLOW);
        assertThat(zero.confidence()).isEqualByComparingTo("0");
        assertThat(one.confidence()).isEqualByComparingTo("1");
    }

    @Test
    void allowRejectsNegativeConfidence() {
        assertThatThrownBy(() -> new Allow(
                        "REQ_1", new BigDecimal("-0.01"), ReasonCode.OTHER, "x", 100L, DecisionPath.EXPLICIT_ALLOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void allowRejectsAboveOne() {
        assertThatThrownBy(() -> new Allow(
                        "REQ_1", new BigDecimal("1.01"), ReasonCode.OTHER, "x", 100L, DecisionPath.EXPLICIT_ALLOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void reasonTextAt280AcceptedFor281Rejected() {
        String exact = "x".repeat(280);
        new Veto("REQ_1", new BigDecimal("0.90"), ReasonCode.OTHER, exact, 100L);
        new Allow("REQ_1", new BigDecimal("0.50"), ReasonCode.OTHER, exact, 100L, DecisionPath.EXPLICIT_ALLOW);

        String tooLong = "x".repeat(281);
        assertThatThrownBy(() -> new Veto("REQ_1", new BigDecimal("0.90"), ReasonCode.OTHER, tooLong, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonText");
        assertThatThrownBy(() -> new Allow(
                        "REQ_1", new BigDecimal("0.50"), ReasonCode.OTHER, tooLong, 100L, DecisionPath.EXPLICIT_ALLOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonText");
    }

    @Test
    void allowAcceptsEmptyReasonText() {
        Allow a = new Allow("REQ_1", new BigDecimal("0.50"), ReasonCode.OTHER, "", 100L, DecisionPath.FALLBACK_ALLOW);
        assertThat(a.reasonText()).isEmpty();
    }

    @Test
    void fallbackHasNoReasonText() {
        Fallback f = new Fallback("REQ_1", FallbackReason.TRANSPORT, 750L);
        assertThat(f.reason()).isEqualTo(FallbackReason.TRANSPORT);
        assertThat(f.latencyMs()).isEqualTo(750L);
    }

    @Test
    void rejectsNullReasonText() {
        assertThatThrownBy(() -> new Veto("REQ_1", new BigDecimal("0.90"), ReasonCode.OTHER, null, 100L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reasonText");
        assertThatThrownBy(() -> new Allow(
                        "REQ_1", new BigDecimal("0.50"), ReasonCode.OTHER, null, 100L, DecisionPath.EXPLICIT_ALLOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reasonText");
    }

    @Test
    void thresholdConstantIsExplicit() {
        // ADR-0007 §2: 0.85 is the published threshold. Keep this literal in
        // the type so a typo here is loud (and a test failure is the canary).
        assertThat(SentinelDecisionResponse.VETO_CONFIDENCE_THRESHOLD).isEqualByComparingTo("0.85");
        assertThat(SentinelDecisionResponse.REASON_TEXT_MAX_LENGTH).isEqualTo(280);
    }

    @Test
    void everyEnumVariantIsClosedSet() {
        // Lock the closed set so a future add to ReasonCode / DecisionPath /
        // FallbackReason is a deliberate, ADR-amending change.
        assertThat(ReasonCode.values()).hasSize(6);
        assertThat(DecisionPath.values()).hasSize(3);
        assertThat(FallbackReason.values()).hasSize(6);
    }
}
