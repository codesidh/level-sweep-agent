package com.levelsweep.aiagent.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Allow;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.DecisionPath;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Fallback;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.FallbackReason;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.ReasonCode;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Veto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behavior matrix for {@link SentinelResponseParser}. Each {@link
 * AnthropicResponse} variant maps to a Sentinel decision per ADR-0007 §3.
 */
class SentinelResponseParserTest {

    private static final SentinelResponseParser PARSER = new SentinelResponseParser();
    private static final String REQ_ID = "REQ_TEST_1";
    private static final long LATENCY = 250L;
    private static final String MODEL = "claude-haiku-4-5";

    // ---------- Success: ALLOW happy path ----------

    @Test
    void successAllow_returnsAllowExplicit() {
        AnthropicResponse.Success s = success(
                "{\"decision\":\"ALLOW\",\"confidence\":0.72,\"reason_code\":\"STRUCTURE_MATCH\",\"reason_text\":\"clean trend\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertThat(out).isInstanceOf(Allow.class);
        Allow a = (Allow) out;
        assertThat(a.decisionPath()).isEqualTo(DecisionPath.EXPLICIT_ALLOW);
        assertThat(a.confidence()).isEqualByComparingTo("0.72");
        assertThat(a.reasonCode()).isEqualTo(ReasonCode.STRUCTURE_MATCH);
        assertThat(a.reasonText()).isEqualTo("clean trend");
        assertThat(a.clientRequestId()).isEqualTo(REQ_ID);
        assertThat(a.latencyMs()).isEqualTo(LATENCY);
    }

    // ---------- Success: VETO confidence boundary ----------

    @Test
    void successVeto_atThreshold_returnsVeto() {
        AnthropicResponse.Success s = success(
                "{\"decision\":\"VETO\",\"confidence\":0.85,\"reason_code\":\"STRUCTURE_DIVERGENCE\",\"reason_text\":\"EMAs diverging\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertThat(out).isInstanceOf(Veto.class);
        Veto v = (Veto) out;
        assertThat(v.confidence()).isEqualByComparingTo("0.85");
        assertThat(v.reasonCode()).isEqualTo(ReasonCode.STRUCTURE_DIVERGENCE);
    }

    @Test
    void successVeto_aboveThreshold_returnsVeto() {
        AnthropicResponse.Success s = success(
                "{\"decision\":\"VETO\",\"confidence\":0.99,\"reason_code\":\"RECENT_LOSSES\",\"reason_text\":\"three losses in a row\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertThat(out).isInstanceOf(Veto.class);
    }

    @Test
    void successVeto_justBelowThreshold_demotedToAllowOverridden() {
        AnthropicResponse.Success s = success(
                "{\"decision\":\"VETO\",\"confidence\":0.84,\"reason_code\":\"REGIME_MISALIGNED\",\"reason_text\":\"counter-trend\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertThat(out).isInstanceOf(Allow.class);
        Allow a = (Allow) out;
        assertThat(a.decisionPath()).isEqualTo(DecisionPath.LOW_CONFIDENCE_VETO_OVERRIDDEN);
        // The parsed reason fields carry through so the audit trail records the model's words.
        assertThat(a.confidence()).isEqualByComparingTo("0.84");
        assertThat(a.reasonCode()).isEqualTo(ReasonCode.REGIME_MISALIGNED);
        assertThat(a.reasonText()).isEqualTo("counter-trend");
    }

    @Test
    void successVeto_atZero_demotedToAllowOverridden() {
        AnthropicResponse.Success s = success(
                "{\"decision\":\"VETO\",\"confidence\":0.00,\"reason_code\":\"OTHER\",\"reason_text\":\"unsure\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertThat(out).isInstanceOf(Allow.class);
        assertThat(((Allow) out).decisionPath()).isEqualTo(DecisionPath.LOW_CONFIDENCE_VETO_OVERRIDDEN);
    }

    // ---------- Success: parse-failure paths ----------

    @Test
    void successMalformedJson_returnsFallbackParse() {
        AnthropicResponse.Success s = success("not even json {");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successEmptyText_returnsFallbackParse() {
        AnthropicResponse.Success s = success("");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successMissingDecisionKey_returnsFallbackParse() {
        AnthropicResponse.Success s =
                success("{\"confidence\":0.90,\"reason_code\":\"STRUCTURE_MATCH\",\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successMissingConfidenceKey_returnsFallbackParse() {
        AnthropicResponse.Success s =
                success("{\"decision\":\"ALLOW\",\"reason_code\":\"STRUCTURE_MATCH\",\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successMissingReasonCodeKey_returnsFallbackParse() {
        AnthropicResponse.Success s = success("{\"decision\":\"ALLOW\",\"confidence\":0.5,\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successMissingReasonTextKey_returnsFallbackParse() {
        AnthropicResponse.Success s =
                success("{\"decision\":\"ALLOW\",\"confidence\":0.5,\"reason_code\":\"STRUCTURE_MATCH\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successInvalidDecisionEnum_returnsFallbackParse() {
        AnthropicResponse.Success s =
                success("{\"decision\":\"MAYBE\",\"confidence\":0.5,\"reason_code\":\"OTHER\",\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successInvalidReasonCodeEnum_returnsFallbackParse() {
        AnthropicResponse.Success s = success(
                "{\"decision\":\"ALLOW\",\"confidence\":0.5,\"reason_code\":\"NOT_A_REAL_CODE\",\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successConfidenceAboveOne_returnsFallbackParse() {
        AnthropicResponse.Success s =
                success("{\"decision\":\"ALLOW\",\"confidence\":1.5,\"reason_code\":\"OTHER\",\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successConfidenceBelowZero_returnsFallbackParse() {
        AnthropicResponse.Success s =
                success("{\"decision\":\"ALLOW\",\"confidence\":-0.1,\"reason_code\":\"OTHER\",\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successConfidenceAsString_acceptedIfDecimal() {
        AnthropicResponse.Success s = success(
                "{\"decision\":\"ALLOW\",\"confidence\":\"0.75\",\"reason_code\":\"STRUCTURE_MATCH\",\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertThat(out).isInstanceOf(Allow.class);
        assertThat(((Allow) out).confidence()).isEqualByComparingTo("0.75");
    }

    @Test
    void successConfidenceAsUnparseableString_returnsFallbackParse() {
        AnthropicResponse.Success s = success(
                "{\"decision\":\"ALLOW\",\"confidence\":\"high\",\"reason_code\":\"OTHER\",\"reason_text\":\"x\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successNonObjectRoot_returnsFallbackParse() {
        AnthropicResponse.Success s = success("[\"ALLOW\",0.5]");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertFallback(out, FallbackReason.PARSE);
    }

    @Test
    void successOverLongReasonText_softTruncated() {
        // 300-char reason_text comes back; Allow's ctor would reject > 280, so
        // the parser truncates rather than failing the call.
        String over = "y".repeat(300);
        AnthropicResponse.Success s =
                success("{\"decision\":\"ALLOW\",\"confidence\":0.5,\"reason_code\":\"OTHER\",\"reason_text\":\"" + over
                        + "\"}");
        SentinelDecisionResponse out = PARSER.parse(s, REQ_ID, LATENCY);
        assertThat(out).isInstanceOf(Allow.class);
        assertThat(((Allow) out).reasonText()).hasSize(SentinelDecisionResponse.REASON_TEXT_MAX_LENGTH);
    }

    // ---------- Non-Success variants ----------

    @Test
    void rateLimited_returnsFallbackRateLimit() {
        AnthropicResponse.RateLimited r = new AnthropicResponse.RateLimited(REQ_ID, Role.SENTINEL, MODEL, LATENCY, "");
        assertFallback(PARSER.parse(r, REQ_ID, LATENCY), FallbackReason.RATE_LIMIT);
    }

    @Test
    void overloaded_returnsFallbackTransport() {
        AnthropicResponse.Overloaded o =
                new AnthropicResponse.Overloaded(REQ_ID, Role.SENTINEL, MODEL, LATENCY, 529, "");
        assertFallback(PARSER.parse(o, REQ_ID, LATENCY), FallbackReason.TRANSPORT);
    }

    @Test
    void invalidRequest_returnsFallbackParse() {
        AnthropicResponse.InvalidRequest ir =
                new AnthropicResponse.InvalidRequest(REQ_ID, Role.SENTINEL, MODEL, LATENCY, 400, "bad schema");
        assertFallback(PARSER.parse(ir, REQ_ID, LATENCY), FallbackReason.PARSE);
    }

    @Test
    void transportFailure_returnsFallbackTransport() {
        AnthropicResponse.TransportFailure tf = new AnthropicResponse.TransportFailure(
                REQ_ID, Role.SENTINEL, MODEL, LATENCY, "ConnectException: refused");
        assertFallback(PARSER.parse(tf, REQ_ID, LATENCY), FallbackReason.TRANSPORT);
    }

    @Test
    void transportFailure_circuitBreakerOpen_returnsFallbackCbOpen() {
        AnthropicResponse.TransportFailure tf =
                new AnthropicResponse.TransportFailure(REQ_ID, Role.SENTINEL, MODEL, LATENCY, "circuit_breaker_open");
        assertFallback(PARSER.parse(tf, REQ_ID, LATENCY), FallbackReason.CB_OPEN);
    }

    @Test
    void costCapBreached_returnsFallbackCostCap() {
        AnthropicResponse.CostCapBreached c = new AnthropicResponse.CostCapBreached(
                REQ_ID,
                Role.SENTINEL,
                MODEL,
                LATENCY,
                new BigDecimal("0.50"),
                new BigDecimal("0.49"),
                new BigDecimal("0.02"));
        assertFallback(PARSER.parse(c, REQ_ID, LATENCY), FallbackReason.COST_CAP);
    }

    // ---------- helpers ----------

    private static AnthropicResponse.Success success(String text) {
        return new AnthropicResponse.Success(
                REQ_ID, Role.SENTINEL, MODEL, LATENCY, text, List.of(), 100, 50, 0, BigDecimal.ZERO);
    }

    private static void assertFallback(SentinelDecisionResponse out, FallbackReason expected) {
        assertThat(out).isInstanceOf(Fallback.class);
        Fallback f = (Fallback) out;
        assertThat(f.reason()).isEqualTo(expected);
        assertThat(f.clientRequestId()).isEqualTo(REQ_ID);
        assertThat(f.latencyMs()).isEqualTo(LATENCY);
    }
}
