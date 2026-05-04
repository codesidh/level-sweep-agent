package com.levelsweep.aiagent.narrator;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicMessage;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.anthropic.CostCalculator;
import com.levelsweep.aiagent.audit.AiCallAuditWriter;
import com.levelsweep.aiagent.audit.PromptHasher;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trade Narrator (architecture-spec §4.3.2 + ADR-0006). Post-trade explanation
 * for the trader dashboard + journal — Phase 4 Step 2.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li><b>Model</b>: {@code claude-sonnet-4-6} (config key
 *       {@code anthropic.models.narrator}).</li>
 *   <li><b>Temperature</b>: 0 — replay parity (Principle #2).</li>
 *   <li><b>Output cap</b>: 200 tokens (1-3 sentences per architecture-spec
 *       §4.3.2 Output row).</li>
 *   <li><b>Pre-flight cost cap</b>: HARD check via
 *       {@link DailyCostTracker#wouldExceedCap}. Below cap → call; above → log
 *       INFO and return empty (architecture-spec §4.8 + §4.9 +
 *       {@code ai-prompt-management} skill rule #4).</li>
 *   <li><b>Failure posture</b>: any non-Success outcome (RateLimited,
 *       Overloaded, InvalidRequest, TransportFailure, CostCapBreached) returns
 *       {@code Optional.empty}. Never throws. The narrator is advisory; a
 *       failed narration MUST never block the trade FSM, the saga, or any
 *       order path (CLAUDE.md guardrail #2-#3).</li>
 *   <li><b>Audit</b>: every call (success OR failure) writes a row to
 *       {@code audit_log.ai_calls} via {@link AiCallAuditWriter#record}, so
 *       the audit trail is complete and the cost cap can be reconciled
 *       post-hoc.</li>
 *   <li><b>Cost recording</b>: on Success, post-call cost is appended to the
 *       per-(tenant, role, day) bucket via
 *       {@link DailyCostTracker#recordCost}. The next pre-flight check sees
 *       the new total.</li>
 * </ul>
 *
 * <p>The narrator does NOT mutate any trade / risk / FSM state — it writes
 * only to {@code trade_narratives} (via {@link TradeNarrativeRepository}) and
 * to {@code audit_log.ai_calls}. CLAUDE.md guardrail #2 ("the AI cannot place
 * orders") + architecture-spec §4.4 ("agent CANNOT mutate Trade FSM").
 */
@ApplicationScoped
public class TradeNarrator {

    private static final Logger LOG = LoggerFactory.getLogger(TradeNarrator.class);

    /** 1-3 sentences fit comfortably in 200 tokens. */
    public static final int MAX_OUTPUT_TOKENS = 200;

    /**
     * Pre-flight projected cost. The narrator's typical call is small (~1.5K
     * input + 200 output tokens — see architecture-spec §4.8); we use a
     * conservative upper bound for the cap pre-check. Sonnet 4.6 pricing:
     * $3 / MTok input + $15 / MTok output.
     *
     * <p>Concretely, projected = {@code CostCalculator.compute(model, 3000,
     * MAX_OUTPUT_TOKENS, 0)} ≈ $0.0120, leaving generous headroom under the
     * default $1/day cap. Tests pin a fixed projection.
     */
    private static final int PROJECTED_INPUT_TOKENS_FOR_CAP_CHECK = 3000;

    private final AnthropicClient anthropicClient;
    private final DailyCostTracker costTracker;
    private final AiCallAuditWriter auditWriter;
    private final AiAgentMetrics metrics;
    private final Clock clock;
    private final String model;

    @Inject
    public TradeNarrator(
            AnthropicClient anthropicClient,
            DailyCostTracker costTracker,
            AiCallAuditWriter auditWriter,
            AiAgentMetrics metrics,
            Clock clock,
            @ConfigProperty(name = "anthropic.models.narrator", defaultValue = "claude-sonnet-4-6") String model) {
        this.anthropicClient = Objects.requireNonNull(anthropicClient, "anthropicClient");
        this.costTracker = Objects.requireNonNull(costTracker, "costTracker");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Build a 1-3 sentence narrative for one inbound trade event.
     *
     * <p>Returns {@link Optional#empty()} on:
     * <ul>
     *   <li>Cost cap pre-flight breach (logged INFO {@code "narrator skipped
     *       (cost cap)"}). The {@link AnthropicClient} ALSO performs a hard
     *       pre-flight check; we duplicate it here so we can emit a more
     *       descriptive log line and skip the audit-row write entirely.
     *       Belt-and-braces — defense in depth on the cap.</li>
     *   <li>Any non-Success {@link AnthropicResponse} variant (logged WARN with
     *       outcome label). Audit row still written for the failure.</li>
     *   <li>Empty response text (would never validate against the
     *       {@link TradeNarrative} record). Audit row still written.</li>
     * </ul>
     */
    public Optional<TradeNarrative> narrate(NarrationRequest request) {
        Objects.requireNonNull(request, "request");

        // Belt-and-braces cost-cap pre-check. The AnthropicClient also enforces
        // this, but checking here lets us:
        //   (a) skip the audit-row + log a more descriptive "narrator skipped"
        //       message (vs. the client's generic "cap breached pre-flight"),
        //   (b) avoid building the full prompt body when we already know the
        //       call won't go out.
        BigDecimal projectedCost = projectedCallCostUsd();
        if (costTracker.wouldExceedCap(request.tenantId(), Role.NARRATOR, costTracker.today(), projectedCost)) {
            BigDecimal cap = costTracker.capFor(Role.NARRATOR);
            BigDecimal current = costTracker.currentSpend(request.tenantId(), Role.NARRATOR, costTracker.today());
            LOG.info(
                    "narrator skipped (cost cap) tenantId={} tradeId={} eventType={}"
                            + " capUsd={} currentSpendUsd={} projectedCostUsd={}",
                    request.tenantId(),
                    request.tradeId(),
                    request.eventType(),
                    cap,
                    current,
                    projectedCost);
            safeNarratorSkipped(request.tenantId(), AiAgentMetrics.NarratorSkipReason.COST_CAP);
            return Optional.empty();
        }

        AnthropicRequest aReq = new AnthropicRequest(
                model,
                NarrationPromptBuilder.systemPrompt(),
                List.of(AnthropicMessage.user(NarrationPromptBuilder.userMessage(request))),
                List.of(),
                MAX_OUTPUT_TOKENS,
                /* temperature */ 0.0d,
                request.tenantId(),
                Role.NARRATOR,
                projectedCost);
        String promptHash = PromptHasher.hash(aReq);

        // Narrator is a non-hot-path role, but the client's retry-enabled
        // parameter is wired here per architecture-spec §4.9 ("Narrator/Reviewer
        // queue with exponential backoff" — the client ignores retryEnabled in
        // S1 but the contract is in place for the Phase 7 retry-library wire).
        AnthropicResponse response = anthropicClient.submit(aReq, /* retryEnabled */ true);

        // Audit — every variant gets a row, success or failure. Trace ID is
        // not yet wired through the listener path; pass empty string (the
        // audit writer accepts and stores ""). The OTel propagation lands in
        // Phase 6 when Strimzi is real.
        try {
            auditWriter.record(aReq, response, /* traceId */ "");
        } catch (RuntimeException e) {
            // Audit failures must never propagate — narrator is advisory.
            LOG.warn(
                    "narrator audit write failed tenantId={} tradeId={}: {}",
                    request.tenantId(),
                    request.tradeId(),
                    e.toString());
        }

        if (!(response instanceof AnthropicResponse.Success success)) {
            LOG.warn(
                    "narrator anthropic call non-success tenantId={} tradeId={} eventType={} outcome={}",
                    request.tenantId(),
                    request.tradeId(),
                    request.eventType(),
                    response.getClass().getSimpleName());
            safeNarratorSkipped(request.tenantId(), AiAgentMetrics.NarratorSkipReason.ANTHROPIC_FAILURE);
            return Optional.empty();
        }

        // Reconcile the post-call cost back into the tracker so subsequent
        // cap checks see the actual spend (not just the projection).
        costTracker.recordCost(request.tenantId(), Role.NARRATOR, costTracker.today(), success.costUsd());

        String narrative =
                success.responseText() == null ? "" : success.responseText().trim();
        if (narrative.isEmpty()) {
            LOG.warn(
                    "narrator received empty response text tenantId={} tradeId={} eventType={}",
                    request.tenantId(),
                    request.tradeId(),
                    request.eventType());
            safeNarratorSkipped(request.tenantId(), AiAgentMetrics.NarratorSkipReason.EMPTY_RESPONSE);
            return Optional.empty();
        }

        safeNarratorFired(request.tenantId());
        return Optional.of(new TradeNarrative(
                request.tenantId(), request.tradeId(), narrative, Instant.now(clock), model, promptHash));
    }

    /** Best-effort metric emit — a meter failure must never block narration. */
    private void safeNarratorSkipped(String tenantId, AiAgentMetrics.NarratorSkipReason reason) {
        try {
            metrics.narratorSkipped(tenantId, reason);
        } catch (RuntimeException e) {
            LOG.warn("narrator metrics emit failed (skipped/{}): {}", reason, e.toString());
        }
    }

    /** Best-effort metric emit — a meter failure must never block narration. */
    private void safeNarratorFired(String tenantId) {
        try {
            metrics.narratorFired(tenantId);
        } catch (RuntimeException e) {
            LOG.warn("narrator metrics emit failed (fired): {}", e.toString());
        }
    }

    /**
     * Conservative pre-flight cost estimate. Uses
     * {@link CostCalculator#compute} with a generous input-token assumption so
     * the narrator never starves itself with too-tight projections, while
     * still respecting the configured cap.
     */
    private BigDecimal projectedCallCostUsd() {
        if (CostCalculator.knowsModel(model)) {
            return CostCalculator.compute(model, PROJECTED_INPUT_TOKENS_FOR_CAP_CHECK, MAX_OUTPUT_TOKENS, 0);
        }
        // Unknown model — projecting zero would BYPASS the cap. Be conservative
        // and project a value that will trip the cap (so an unknown-model
        // misconfiguration is loud, not silent).
        return new BigDecimal("999.99");
    }
}
