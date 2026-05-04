package com.levelsweep.aiagent.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Bar;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Direction;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.IndicatorSnapshot;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.LevelSwept;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Outcome;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.RecentTrade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Determinism contract for {@link SentinelPromptBuilder}. The replay harness
 * (ADR-0007 §5) hashes the prompt bytes; a single character drift on identical
 * input would invalidate the recorded fixture corpus. These tests pin the
 * shape AND the byte-identical determinism property.
 */
class SentinelPromptBuilderTest {

    private static final SentinelPromptBuilder BUILDER = new SentinelPromptBuilder("claude-haiku-4-5");
    private static final Instant T = Instant.parse("2026-05-02T15:00:00Z");

    @Test
    void buildProducesHaikuRequestWithSentinelRole() {
        AnthropicRequest req = BUILDER.build(longCallPdh());
        assertThat(req.model()).isEqualTo("claude-haiku-4-5");
        assertThat(req.role()).isEqualTo(Role.SENTINEL);
        assertThat(req.tenantId()).isEqualTo("OWNER");
        assertThat(req.maxTokens()).isEqualTo(SentinelPromptBuilder.MAX_TOKENS);
        assertThat(req.temperature()).isEqualTo(0.0d);
        assertThat(req.projectedCostUsd()).isEqualByComparingTo(SentinelPromptBuilder.PROJECTED_COST_USD);
        assertThat(req.messages()).hasSize(1);
        assertThat(req.messages().get(0).role()).isEqualTo("user");
    }

    @Test
    void systemPromptHasContractCriticalCopy() {
        String sys = SentinelPromptBuilder.systemPrompt();
        // Every clause specified in ADR-0007 §1 + §2 must be present.
        assertThat(sys).contains("pre-trade Sentinel");
        assertThat(sys).contains("0DTE SPY options trader");
        assertThat(sys).contains("ALLOW or VETO");
        assertThat(sys).contains("Default on uncertainty = ALLOW");
        assertThat(sys).contains("Confidence < 0.85 means do NOT veto");
        assertThat(sys).contains("STRUCTURE_MATCH");
        assertThat(sys).contains("STRUCTURE_DIVERGENCE");
        assertThat(sys).contains("REGIME_MISALIGNED");
        assertThat(sys).contains("RECENT_LOSSES");
        assertThat(sys).contains("LOW_LIQUIDITY_PROFILE");
        assertThat(sys).contains("OTHER");
        // STRICT JSON only — Sentinel parser rejects free text wrapped in JSON.
        assertThat(sys).contains("STRICT JSON");
    }

    @Test
    void userMessageContainsAllRequestFields() {
        SentinelDecisionRequest req = longCallPdh();
        String msg = BUILDER.renderUserMessage(req);
        assertThat(msg).contains("tenant_id=OWNER");
        assertThat(msg).contains("trade_id=TR_001");
        assertThat(msg).contains("signal_id=SIG_001");
        assertThat(msg).contains("direction=LONG_CALL");
        assertThat(msg).contains("level_swept=PDH");
        assertThat(msg).contains("now_utc=2026-05-02T15:00:00Z");
        assertThat(msg).contains("vix_close_prev=14.50");
        assertThat(msg).contains("ema13=500.00");
        assertThat(msg).contains("regime=BULL");
        // recent bars + recent trades sections present.
        assertThat(msg).contains("recent_bars");
        assertThat(msg).contains("recent_trades");
    }

    @Test
    void deterministic_sameInputProducesByteIdenticalOutput() {
        SentinelDecisionRequest a = longCallPdh();
        SentinelDecisionRequest b = longCallPdh();
        // Distinct record instances, identical content.
        assertThat(BUILDER.renderUserMessage(a)).isEqualTo(BUILDER.renderUserMessage(b));
        assertThat(SentinelPromptBuilder.systemPrompt()).isEqualTo(SentinelPromptBuilder.systemPrompt());
    }

    @Test
    void deterministic_subSecondJitterStripped() {
        // Two requests differ only in sub-second time precision. Prompt bytes
        // must be identical — the second-truncation is the determinism anchor.
        SentinelDecisionRequest a = longCallPdhAt(Instant.parse("2026-05-02T15:00:00Z"));
        SentinelDecisionRequest b = longCallPdhAt(Instant.parse("2026-05-02T15:00:00.999Z"));
        assertThat(BUILDER.renderUserMessage(a)).isEqualTo(BUILDER.renderUserMessage(b));
    }

    @Test
    void deterministic_bigDecimalScaleNormalized() {
        // 14.5 vs 14.500 → both render as 14.50 with HALF_EVEN scale=2.
        SentinelDecisionRequest a = longCallPdhWithVix(new BigDecimal("14.5"));
        SentinelDecisionRequest b = longCallPdhWithVix(new BigDecimal("14.500"));
        assertThat(BUILDER.renderUserMessage(a)).isEqualTo(BUILDER.renderUserMessage(b));
        assertThat(BUILDER.renderUserMessage(a)).contains("vix_close_prev=14.50");
    }

    @Test
    void differentDirectionsProduceDifferentBodies() {
        SentinelDecisionRequest call = longCallPdh();
        SentinelDecisionRequest put = new SentinelDecisionRequest(
                "OWNER",
                "TR_001",
                "SIG_001",
                Direction.LONG_PUT,
                LevelSwept.PDL,
                snapshot("BEAR"),
                List.of(),
                new BigDecimal("14.50"),
                T);
        assertThat(BUILDER.renderUserMessage(call)).isNotEqualTo(BUILDER.renderUserMessage(put));
        assertThat(BUILDER.renderUserMessage(put)).contains("direction=LONG_PUT");
        assertThat(BUILDER.renderUserMessage(put)).contains("level_swept=PDL");
        assertThat(BUILDER.renderUserMessage(put)).contains("regime=BEAR");
    }

    @Test
    void allFourLevelsRenderCorrectly() {
        for (LevelSwept lvl : LevelSwept.values()) {
            SentinelDecisionRequest r = new SentinelDecisionRequest(
                    "OWNER",
                    "TR_001",
                    "SIG_001",
                    Direction.LONG_CALL,
                    lvl,
                    snapshot("RANGE"),
                    List.of(),
                    new BigDecimal("14.50"),
                    T);
            assertThat(BUILDER.renderUserMessage(r)).contains("level_swept=" + lvl.name());
        }
    }

    @Test
    void recentTradesEmitInOrder() {
        SentinelDecisionRequest req = new SentinelDecisionRequest(
                "OWNER",
                "TR_001",
                "SIG_001",
                Direction.LONG_CALL,
                LevelSwept.PDH,
                snapshot("BULL"),
                List.of(
                        new RecentTrade("TR_A", Outcome.LOSS, new BigDecimal("-1.0"), T.minusSeconds(3600)),
                        new RecentTrade("TR_B", Outcome.WIN, new BigDecimal("2.0"), T.minusSeconds(1800)),
                        new RecentTrade("TR_C", Outcome.BE, new BigDecimal("0.0"), T.minusSeconds(900))),
                new BigDecimal("14.50"),
                T);
        String msg = BUILDER.renderUserMessage(req);
        int idxA = msg.indexOf("trade_id=TR_A");
        int idxB = msg.indexOf("trade_id=TR_B");
        int idxC = msg.indexOf("trade_id=TR_C");
        assertThat(idxA).isPositive();
        assertThat(idxA).isLessThan(idxB);
        assertThat(idxB).isLessThan(idxC);
        assertThat(msg).contains("outcome=LOSS");
        assertThat(msg).contains("outcome=WIN");
        assertThat(msg).contains("outcome=BE");
        assertThat(msg).contains("r_multiple=-1.0");
        assertThat(msg).contains("r_multiple=2.0");
    }

    @Test
    void emptyTradeWindowRendersExplicitNone() {
        SentinelDecisionRequest req = new SentinelDecisionRequest(
                "OWNER",
                "TR_001",
                "SIG_001",
                Direction.LONG_CALL,
                LevelSwept.PDH,
                new IndicatorSnapshot(
                        new BigDecimal("500.00"),
                        new BigDecimal("499.00"),
                        new BigDecimal("498.00"),
                        new BigDecimal("1.50"),
                        new BigDecimal("60.00"),
                        "BULL",
                        List.of()),
                List.of(),
                new BigDecimal("14.50"),
                T);
        String msg = BUILDER.renderUserMessage(req);
        // (none) lines render in BOTH the bars + trades sections so the model
        // sees an explicit "no recent context" signal.
        assertThat(msg).contains("recent_bars");
        assertThat(msg).contains("recent_trades");
        assertThat(msg).containsPattern("recent_bars[^\\n]*\\n  \\(none\\)");
        assertThat(msg).containsPattern("recent_trades[^\\n]*\\n  \\(none\\)");
    }

    @Test
    void buildIsByteIdenticalAcrossInvocations() {
        // The full request body — including the wrapped user message inside
        // the AnthropicMessage — must be byte-identical for identical input.
        // This is the contract PromptHasher relies on (ADR-0006 §6 + ADR-0007 §5).
        SentinelDecisionRequest req = longCallPdh();
        AnthropicRequest a = BUILDER.build(req);
        AnthropicRequest b = BUILDER.build(req);
        assertThat(a.systemPrompt()).isEqualTo(b.systemPrompt());
        assertThat(a.messages()).isEqualTo(b.messages());
        assertThat(a.model()).isEqualTo(b.model());
        assertThat(a.maxTokens()).isEqualTo(b.maxTokens());
        assertThat(a.temperature()).isEqualTo(b.temperature());
        assertThat(a.projectedCostUsd()).isEqualByComparingTo(b.projectedCostUsd());
    }

    private static SentinelDecisionRequest longCallPdh() {
        return longCallPdhAt(T);
    }

    private static SentinelDecisionRequest longCallPdhAt(Instant when) {
        return new SentinelDecisionRequest(
                "OWNER",
                "TR_001",
                "SIG_001",
                Direction.LONG_CALL,
                LevelSwept.PDH,
                snapshot("BULL"),
                List.of(new RecentTrade("TR_PRIOR_1", Outcome.WIN, new BigDecimal("1.5"), T.minusSeconds(3600))),
                new BigDecimal("14.50"),
                when);
    }

    private static SentinelDecisionRequest longCallPdhWithVix(BigDecimal vix) {
        return new SentinelDecisionRequest(
                "OWNER", "TR_001", "SIG_001", Direction.LONG_CALL, LevelSwept.PDH, snapshot("BULL"), List.of(), vix, T);
    }

    private static IndicatorSnapshot snapshot(String regime) {
        return new IndicatorSnapshot(
                new BigDecimal("500.00"),
                new BigDecimal("499.00"),
                new BigDecimal("498.00"),
                new BigDecimal("1.50"),
                new BigDecimal("60.00"),
                regime,
                List.of(
                        new Bar(T.minusSeconds(240), new BigDecimal("499.50"), 12000L),
                        new Bar(T.minusSeconds(120), new BigDecimal("499.75"), 13000L),
                        new Bar(T, new BigDecimal("500.00"), 14000L)));
    }
}
