package com.levelsweep.aiagent.sentinel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Allow;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.DecisionPath;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Fallback;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.FallbackReason;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.ReasonCode;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Veto;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps an {@link AnthropicResponse} variant to a {@link SentinelDecisionResponse}.
 *
 * <p>Behavior matrix (ADR-0007 §3):
 *
 * <ul>
 *   <li>{@link AnthropicResponse.Success} — parse the JSON content. Any of
 *       missing key, invalid enum value, confidence out of [0, 1], or
 *       malformed JSON → {@link Fallback} with {@link FallbackReason#PARSE}.
 *       VETO with confidence ≥ 0.85 → {@link Veto}; VETO with confidence
 *       &lt; 0.85 → {@link Allow} with
 *       {@link DecisionPath#LOW_CONFIDENCE_VETO_OVERRIDDEN} (the parsed
 *       {@code reason_code} / {@code reason_text} are carried through so the
 *       audit trail records what the model said).</li>
 *   <li>{@link AnthropicResponse.RateLimited} → {@code RATE_LIMIT}.</li>
 *   <li>{@link AnthropicResponse.Overloaded} → {@code TRANSPORT}.</li>
 *   <li>{@link AnthropicResponse.InvalidRequest} → {@code PARSE} (treated as
 *       a contract issue — the request shape was wrong, not a runtime
 *       failure).</li>
 *   <li>{@link AnthropicResponse.TransportFailure} → {@code TRANSPORT}, with
 *       a special-case for the Connection FSM short-circuit:
 *       {@code exceptionMessage = "circuit_breaker_open"} → {@code CB_OPEN}.</li>
 *   <li>{@link AnthropicResponse.CostCapBreached} → {@code COST_CAP}.</li>
 * </ul>
 */
@ApplicationScoped
public class SentinelResponseParser {

    private static final Logger LOG = LoggerFactory.getLogger(SentinelResponseParser.class);

    /** Sentinel-of-bound for the magic transport-failure reason set by AnthropicConnectionMonitor. */
    public static final String CIRCUIT_BREAKER_OPEN = "circuit_breaker_open";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final ObjectMapper mapper;

    public SentinelResponseParser() {
        this(new ObjectMapper());
    }

    /** Test seam — inject an ObjectMapper if the test wants a stricter parser config. */
    SentinelResponseParser(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Map one Anthropic response to a Sentinel decision. Never throws. */
    public SentinelDecisionResponse parse(AnthropicResponse anthropicResponse, String clientRequestId, long latencyMs) {
        Objects.requireNonNull(anthropicResponse, "anthropicResponse");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        return switch (anthropicResponse) {
            case AnthropicResponse.Success s -> parseSuccess(s, clientRequestId, latencyMs);
            case AnthropicResponse.RateLimited ignored -> new Fallback(
                    clientRequestId, FallbackReason.RATE_LIMIT, latencyMs);
            case AnthropicResponse.Overloaded ignored -> new Fallback(
                    clientRequestId, FallbackReason.TRANSPORT, latencyMs);
            case AnthropicResponse.InvalidRequest ignored -> new Fallback(
                    clientRequestId, FallbackReason.PARSE, latencyMs);
            case AnthropicResponse.TransportFailure tf -> mapTransportFailure(tf, clientRequestId, latencyMs);
            case AnthropicResponse.CostCapBreached ignored -> new Fallback(
                    clientRequestId, FallbackReason.COST_CAP, latencyMs);
        };
    }

    private SentinelDecisionResponse parseSuccess(
            AnthropicResponse.Success success, String clientRequestId, long latencyMs) {
        String text = success.responseText();
        if (text == null || text.isBlank()) {
            LOG.warn("sentinel parser empty response text clientRequestId={}", clientRequestId);
            return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            LOG.warn("sentinel parser malformed JSON clientRequestId={}: {}", clientRequestId, e.toString());
            return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
        }
        if (root == null || !root.isObject()) {
            LOG.warn("sentinel parser non-object JSON root clientRequestId={}", clientRequestId);
            return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
        }

        JsonNode decisionNode = root.get("decision");
        JsonNode confidenceNode = root.get("confidence");
        JsonNode reasonCodeNode = root.get("reason_code");
        JsonNode reasonTextNode = root.get("reason_text");
        if (decisionNode == null || confidenceNode == null || reasonCodeNode == null || reasonTextNode == null) {
            LOG.warn("sentinel parser missing required key clientRequestId={}", clientRequestId);
            return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
        }

        String decisionRaw = decisionNode.asText("");
        if (!"ALLOW".equals(decisionRaw) && !"VETO".equals(decisionRaw)) {
            LOG.warn("sentinel parser invalid decision clientRequestId={} decision={}", clientRequestId, decisionRaw);
            return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
        }

        BigDecimal confidence;
        try {
            // Accept either a numeric or a string-encoded decimal. Reject NaN /
            // Infinity by going through BigDecimal directly.
            if (confidenceNode.isNumber()) {
                confidence = confidenceNode.decimalValue();
            } else if (confidenceNode.isTextual()) {
                confidence = new BigDecimal(confidenceNode.asText());
            } else {
                LOG.warn(
                        "sentinel parser non-numeric confidence clientRequestId={} type={}",
                        clientRequestId,
                        confidenceNode.getNodeType());
                return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
            }
        } catch (NumberFormatException e) {
            LOG.warn("sentinel parser unparseable confidence clientRequestId={}: {}", clientRequestId, e.toString());
            return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
        }
        if (confidence.compareTo(ZERO) < 0 || confidence.compareTo(ONE) > 0) {
            LOG.warn(
                    "sentinel parser confidence out of [0,1] clientRequestId={} confidence={}",
                    clientRequestId,
                    confidence);
            return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
        }

        ReasonCode reasonCode;
        try {
            reasonCode = ReasonCode.valueOf(reasonCodeNode.asText(""));
        } catch (IllegalArgumentException e) {
            LOG.warn(
                    "sentinel parser invalid reason_code clientRequestId={} reasonCode={}",
                    clientRequestId,
                    reasonCodeNode.asText(""));
            return new Fallback(clientRequestId, FallbackReason.PARSE, latencyMs);
        }

        String reasonText = reasonTextNode.asText("");
        if (reasonText.length() > SentinelDecisionResponse.REASON_TEXT_MAX_LENGTH) {
            // Soft-truncate rather than fail — the model occasionally over-runs.
            // Truncation is audited by length comparison; the saga path is
            // unaffected.
            reasonText = reasonText.substring(0, SentinelDecisionResponse.REASON_TEXT_MAX_LENGTH);
        }

        if ("VETO".equals(decisionRaw)) {
            if (confidence.compareTo(SentinelDecisionResponse.VETO_CONFIDENCE_THRESHOLD) >= 0) {
                return new Veto(clientRequestId, confidence, reasonCode, reasonText, latencyMs);
            }
            // Lower-confidence VETO → demote to Allow with the override path
            // so the audit trail captures what the model said.
            return new Allow(
                    clientRequestId,
                    confidence,
                    reasonCode,
                    reasonText,
                    latencyMs,
                    DecisionPath.LOW_CONFIDENCE_VETO_OVERRIDDEN);
        }
        // decisionRaw == "ALLOW"
        return new Allow(clientRequestId, confidence, reasonCode, reasonText, latencyMs, DecisionPath.EXPLICIT_ALLOW);
    }

    private static Fallback mapTransportFailure(
            AnthropicResponse.TransportFailure tf, String clientRequestId, long latencyMs) {
        if (CIRCUIT_BREAKER_OPEN.equals(tf.exceptionMessage())) {
            return new Fallback(clientRequestId, FallbackReason.CB_OPEN, latencyMs);
        }
        return new Fallback(clientRequestId, FallbackReason.TRANSPORT, latencyMs);
    }
}
