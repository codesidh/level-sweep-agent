package com.levelsweep.aiagent.anthropic;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Sealed outcome of {@link AnthropicClient#submit(AnthropicRequest)}. Mirrors
 * {@code com.levelsweep.shared.domain.trade.OrderSubmission} from the Alpaca
 * client — exhaustive switch on the variant means callers must handle every
 * documented failure mode (architecture-spec §4.9).
 *
 * <p>The base interface carries fields common to every outcome: a deterministic
 * client-side request id, role, model, and observed latency. The two-arg
 * "everything happened" variant ({@link Success}) extends with full token
 * accounting + computed cost; the failure variants carry the minimum context
 * needed to log + alert + degrade gracefully (Sentinel ALLOW, Narrator skip,
 * Reviewer skip, Assistant error).
 */
public sealed interface AnthropicResponse
        permits AnthropicResponse.Success,
                AnthropicResponse.RateLimited,
                AnthropicResponse.Overloaded,
                AnthropicResponse.InvalidRequest,
                AnthropicResponse.TransportFailure,
                AnthropicResponse.CostCapBreached {

    String clientRequestId();

    Role role();

    String model();

    long latencyMs();

    /**
     * 2xx response from the Messages API with full text + token accounting. The
     * computed {@code costUsd} is the post-call reconciliation against
     * {@code response.usage} (input + output, including cache read/write) per
     * the {@code ai-prompt-management} skill rule.
     */
    record Success(
            String clientRequestId,
            Role role,
            String model,
            long latencyMs,
            String responseText,
            List<String> toolCalls,
            int inputTokens,
            int outputTokens,
            int cachedTokens,
            BigDecimal costUsd)
            implements AnthropicResponse {
        public Success {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(responseText, "responseText");
            Objects.requireNonNull(toolCalls, "toolCalls");
            Objects.requireNonNull(costUsd, "costUsd");
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /**
     * Anthropic returned 429 (rate-limited). Sentinel callers default to ALLOW
     * (architecture-spec §4.9); Narrator/Reviewer queue + retry.
     */
    record RateLimited(String clientRequestId, Role role, String model, long latencyMs, String responseBodySnippet)
            implements AnthropicResponse {
        public RateLimited {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(responseBodySnippet, "responseBodySnippet");
        }
    }

    /**
     * Anthropic returned 529 / 503 (overloaded — common at 09:30 ET burst).
     * Same fail-open posture as {@link RateLimited} for the Sentinel; backoff
     * + retry for Narrator/Reviewer.
     */
    record Overloaded(
            String clientRequestId, Role role, String model, long latencyMs, int httpStatus, String responseBodySnippet)
            implements AnthropicResponse {
        public Overloaded {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(responseBodySnippet, "responseBodySnippet");
        }
    }

    /**
     * Anthropic returned 400 / other 4xx. No retry — most likely a tool-schema
     * regression that needs human review (architecture-spec §4.9).
     */
    record InvalidRequest(
            String clientRequestId, Role role, String model, long latencyMs, int httpStatus, String responseBodySnippet)
            implements AnthropicResponse {
        public InvalidRequest {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(responseBodySnippet, "responseBodySnippet");
        }
    }

    /**
     * Network / DNS / TCP / parse failure — the call never reached the API or
     * the response was unparseable. Sentinel defaults ALLOW, Narrator queues.
     */
    record TransportFailure(String clientRequestId, Role role, String model, long latencyMs, String exceptionMessage)
            implements AnthropicResponse {
        public TransportFailure {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(exceptionMessage, "exceptionMessage");
        }
    }

    /**
     * Per-tenant per-day per-role cost cap would be exceeded by this call.
     * Returned BEFORE any HTTP call (hard pre-flight) — see
     * {@link com.levelsweep.aiagent.cost.DailyCostTracker}. Per the
     * {@code ai-prompt-management} skill: Sentinel degrades to ALLOW, Narrator
     * + Reviewer skip, Assistant disabled until 00:00 ET.
     */
    record CostCapBreached(
            String clientRequestId,
            Role role,
            String model,
            long latencyMs,
            BigDecimal capUsd,
            BigDecimal currentSpendUsd,
            BigDecimal projectedCallCostUsd)
            implements AnthropicResponse {
        public CostCapBreached {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(capUsd, "capUsd");
            Objects.requireNonNull(currentSpendUsd, "currentSpendUsd");
            Objects.requireNonNull(projectedCallCostUsd, "projectedCallCostUsd");
        }
    }
}
