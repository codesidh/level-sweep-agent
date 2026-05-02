package com.levelsweep.aiagent.narrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for {@link NarrationPromptBuilder}. Two contracts to verify:
 *
 * <ul>
 *   <li><b>Determinism</b>: same input → byte-identical output. The prompt
 *       hash relies on this for replay-parity (architecture-spec Principle
 *       #2 + ADR-0006 §6).</li>
 *   <li><b>Tone</b>: every template has explicit "do not recommend / do not
 *       advise" framing — checked by string assertion below. Failing this
 *       asserts the prompt drifted out of the "explainer not advisor" stance
 *       (architecture-spec §4.11 + {@code ai-prompt-management} skill MUST
 *       NOT #3).</li>
 * </ul>
 */
class NarrationPromptBuilderTest {

    private static final Instant T = Instant.parse("2026-05-02T15:00:00Z");

    @Test
    void systemPromptForbidsAdviceLanguage() {
        String sys = NarrationPromptBuilder.systemPrompt();
        // Every advisory phrase listed in the system prompt's blocklist
        // must be present so future drift is caught.
        assertThat(sys).contains("Do NOT give advice");
        assertThat(sys).contains("Do NOT recommend");
        assertThat(sys).contains("you should");
        assertThat(sys).contains("explaining the strategy");
    }

    @Test
    void systemPromptCapsSentenceCount() {
        // Architecture-spec §4.3.2 — "1-3 sentence narrative".
        String sys = NarrationPromptBuilder.systemPrompt();
        assertThat(sys).contains("1 to 3 short sentences");
        assertThat(sys).contains("Maximum 3 sentences");
    }

    @Test
    void allKnownEventTypesHaveTemplates() {
        for (String type : NarrationPromptBuilder.KNOWN_EVENT_TYPES) {
            NarrationRequest req = new NarrationRequest("OWNER", type, "details=" + type, "TR_001", T);
            String body = NarrationPromptBuilder.userMessage(req);
            assertThat(body).isNotBlank();
            // Every template must reference the trade ID + occurred-at + payload.
            assertThat(body).contains("TR_001");
            assertThat(body).contains(T.toString());
            assertThat(body).contains("details=" + type);
            // Every template must explicitly disclaim advice.
            assertThat(body)
                    .as("template for %s must contain 'do not recommend'", type)
                    .containsIgnoringCase("do not recommend");
        }
    }

    @Test
    void fillTemplateOpensWithExplainNotAdvise() {
        NarrationRequest req = new NarrationRequest(
                "OWNER", NarrationPromptBuilder.EVENT_FILL, "contract=SPY250502C00500000", "TR_001", T);
        String body = NarrationPromptBuilder.userMessage(req);
        assertThat(body).startsWith("Explain in 1-3 sentences");
        assertThat(body).contains("filled by the broker");
        assertThat(body).contains("contract symbol");
    }

    @Test
    void rejectedTemplateMentionsBrokerRefusal() {
        NarrationRequest req =
                new NarrationRequest("OWNER", NarrationPromptBuilder.EVENT_REJECTED, "httpStatus=400", "TR_001", T);
        String body = NarrationPromptBuilder.userMessage(req);
        assertThat(body).startsWith("Explain in 1-3 sentences");
        assertThat(body).contains("rejected by the broker");
        assertThat(body).contains("did not enter the market");
    }

    @Test
    void stopTemplateMentionsDeterministicRule() {
        NarrationRequest req =
                new NarrationRequest("OWNER", NarrationPromptBuilder.EVENT_STOP, "stopReference=EMA13", "TR_001", T);
        String body = NarrationPromptBuilder.userMessage(req);
        assertThat(body).startsWith("Explain in 1-3 sentences");
        assertThat(body).contains("deterministic stop-loss rule fired");
        assertThat(body).contains("EMA13");
        assertThat(body).contains("not a discretionary call");
    }

    @Test
    void trailBreachTemplateMentionsLockedInProfit() {
        NarrationRequest req = new NarrationRequest(
                "OWNER", NarrationPromptBuilder.EVENT_TRAIL_BREACH, "exitFloorPct=0.35", "TR_001", T);
        String body = NarrationPromptBuilder.userMessage(req);
        assertThat(body).startsWith("Explain in 1-3 sentences");
        assertThat(body).contains("trailing-stop floor was breached");
        assertThat(body).contains("locked-in profit");
    }

    @Test
    void eodFlattenTemplateMentionsAutomatic() {
        NarrationRequest req = new NarrationRequest(
                "OWNER", NarrationPromptBuilder.EVENT_EOD_FLATTEN, "alpacaOrderId=AO_123", "TR_001", T);
        String body = NarrationPromptBuilder.userMessage(req);
        assertThat(body).startsWith("Explain in 1-3 sentences");
        assertThat(body).contains("end-of-day flatten rule");
        assertThat(body).contains("automatic and unconditional");
    }

    @Test
    void orderSubmittedTemplateMentionsAcceptedAwaitingFill() {
        NarrationRequest req =
                new NarrationRequest("OWNER", NarrationPromptBuilder.EVENT_ORDER_SUBMITTED, "quantity=2", "TR_001", T);
        String body = NarrationPromptBuilder.userMessage(req);
        assertThat(body).startsWith("Explain in 1-3 sentences");
        assertThat(body).contains("accepted by the broker");
        assertThat(body).contains("waiting for a fill");
    }

    @Test
    void deterministic_sameInputProducesByteIdenticalOutput() {
        NarrationRequest a = new NarrationRequest(
                "OWNER", NarrationPromptBuilder.EVENT_FILL, "contract=SPY, price=1.42, qty=2", "TR_001", T);
        // Distinct record instance, identical content.
        NarrationRequest b = new NarrationRequest(
                "OWNER", NarrationPromptBuilder.EVENT_FILL, "contract=SPY, price=1.42, qty=2", "TR_001", T);

        assertThat(NarrationPromptBuilder.userMessage(a)).isEqualTo(NarrationPromptBuilder.userMessage(b));
        // System prompt is also stable.
        assertThat(NarrationPromptBuilder.systemPrompt()).isEqualTo(NarrationPromptBuilder.systemPrompt());
    }

    @Test
    void deterministic_differentEventTypesProduceDifferentBodies() {
        NarrationRequest fill = new NarrationRequest("OWNER", NarrationPromptBuilder.EVENT_FILL, "x=1", "TR_001", T);
        NarrationRequest stop = new NarrationRequest("OWNER", NarrationPromptBuilder.EVENT_STOP, "x=1", "TR_001", T);
        assertThat(NarrationPromptBuilder.userMessage(fill)).isNotEqualTo(NarrationPromptBuilder.userMessage(stop));
    }

    @Test
    void unknownEventTypeRejectedAtRequestConstruction() {
        // The request itself validates the event type; the builder never sees
        // an unknown type in production.
        assertThatThrownBy(() -> new NarrationRequest("OWNER", "MYSTERY_EVENT", "{}", "TR_001", T))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown eventType");
    }
}
