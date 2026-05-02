package com.levelsweep.aiagent.reviewer;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicMessage;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.anthropic.CostCalculator;
import com.levelsweep.aiagent.audit.AiCallAuditWriter;
import com.levelsweep.aiagent.audit.PromptHasher;
import com.levelsweep.aiagent.cost.DailyCostTracker;
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
 * Daily Reviewer (architecture-spec §4.3.4 + ADR-0006). EOD batch summarizer
 * for the trader journal — Phase 4 Step 3.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li><b>Model</b>: {@code claude-opus-4-7} (config key
 *       {@code anthropic.models.reviewer}).</li>
 *   <li><b>Temperature</b>: 0 — replay parity (Principle #2).</li>
 *   <li><b>Output cap</b>: 1500 tokens — architecture-spec §4.8 cost row
 *       (~10K in / 1.5K out per call).</li>
 *   <li><b>Pre-flight cost cap</b>: HARD check via
 *       {@link DailyCostTracker#wouldExceedCap}. Below cap → call; above → log
 *       INFO and return empty (architecture-spec §4.8 + §4.9 +
 *       {@code ai-prompt-management} skill rule #4). The scheduler still writes
 *       a {@code SKIPPED_COST_CAP} report for audit consistency.</li>
 *   <li><b>Retry posture</b>: SINGLE attempt — if Anthropic is down at 16:30
 *       ET, today's review is skipped and tomorrow's run picks up. The
 *       reviewer is advisory and not on any order path; latency / availability
 *       trade-offs favor "no retry, never block the next day's run". The
 *       AnthropicClient's {@code retryEnabled} parameter is set to {@code false}.</li>
 *   <li><b>Failure posture</b>: any non-Success outcome (RateLimited,
 *       Overloaded, InvalidRequest, TransportFailure, CostCapBreached) returns
 *       {@code Optional.empty}. Never throws. The reviewer is advisory; a
 *       failed review MUST never block the trade FSM, the saga, or any order
 *       path (CLAUDE.md guardrail #2-#3, architecture-spec §4.4).</li>
 *   <li><b>Audit</b>: every call (success OR failure) writes a row to
 *       {@code audit_log.ai_calls} via {@link AiCallAuditWriter#record}, so
 *       the audit trail is complete and the cost cap can be reconciled
 *       post-hoc.</li>
 *   <li><b>Cost recording</b>: on Success, post-call cost is appended to the
 *       per-(tenant, role, day) bucket via
 *       {@link DailyCostTracker#recordCost}.</li>
 * </ul>
 *
 * <p>The reviewer does NOT mutate any trade / risk / FSM state, and it does
 * NOT modify config — even if it proposes one, the proposal is advisory and
 * the user reviews + decides (Phase A: proposals list is empty on persisted
 * reports; Phase B unlocks user-approval flow per architecture-spec §22 #10).
 */
@ApplicationScoped
public class DailyReviewer {

    private static final Logger LOG = LoggerFactory.getLogger(DailyReviewer.class);

    /**
     * Output token cap. Architecture-spec §4.8 budgets ~1500 output tokens for
     * the reviewer's structured report (summary + anomalies + optional proposal).
     */
    public static final int MAX_OUTPUT_TOKENS = 1500;

    /**
     * Pre-flight projected input tokens for the cap check. Per
     * architecture-spec §4.8 the typical reviewer call carries ~10K input
     * tokens. We project that as the upper bound so the cap check is faithful
     * even on a busy session.
     */
    private static final int PROJECTED_INPUT_TOKENS_FOR_CAP_CHECK = 10_000;

    private final AnthropicClient anthropicClient;
    private final DailyCostTracker costTracker;
    private final AiCallAuditWriter auditWriter;
    private final Clock clock;
    private final String model;

    @Inject
    public DailyReviewer(
            AnthropicClient anthropicClient,
            DailyCostTracker costTracker,
            AiCallAuditWriter auditWriter,
            Clock clock,
            @ConfigProperty(name = "anthropic.models.reviewer", defaultValue = "claude-opus-4-7") String model) {
        this.anthropicClient = Objects.requireNonNull(anthropicClient, "anthropicClient");
        this.costTracker = Objects.requireNonNull(costTracker, "costTracker");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Produce one {@link DailyReport} for the supplied session inputs.
     *
     * <p>Returns {@link Optional#empty()} on:
     * <ul>
     *   <li>Cost cap pre-flight breach (logged INFO {@code "daily reviewer
     *       skipped (cost cap)"}). The {@link AnthropicClient} also performs
     *       its own pre-flight check; we duplicate it here so the scheduler
     *       can persist a {@code SKIPPED_COST_CAP} stub report and we can
     *       skip the full prompt-build cost.</li>
     *   <li>Any non-Success {@link AnthropicResponse} variant (logged WARN with
     *       outcome label). Audit row still written for the failure.</li>
     *   <li>Empty response text (would never validate against the
     *       {@link DailyReport} record). Audit row still written.</li>
     * </ul>
     */
    public Optional<DailyReport> review(ReviewRequest request) {
        Objects.requireNonNull(request, "request");

        // Belt-and-braces cost-cap pre-check. The AnthropicClient also enforces
        // this, but checking here lets us skip the full prompt build + emit
        // a more descriptive log line and let the scheduler persist a stub
        // SKIPPED_COST_CAP report.
        BigDecimal projectedCost = projectedCallCostUsd();
        if (costTracker.wouldExceedCap(request.tenantId(), Role.REVIEWER, costTracker.today(), projectedCost)) {
            BigDecimal cap = costTracker.capFor(Role.REVIEWER);
            BigDecimal current = costTracker.currentSpend(request.tenantId(), Role.REVIEWER, costTracker.today());
            LOG.info(
                    "daily reviewer skipped (cost cap) tenantId={} sessionDate={}"
                            + " capUsd={} currentSpendUsd={} projectedCostUsd={}",
                    request.tenantId(),
                    request.sessionDate(),
                    cap,
                    current,
                    projectedCost);
            return Optional.empty();
        }

        AnthropicRequest aReq = new AnthropicRequest(
                model,
                ReviewerPromptBuilder.systemPrompt(),
                List.of(AnthropicMessage.user(ReviewerPromptBuilder.userMessage(request))),
                List.of(),
                MAX_OUTPUT_TOKENS,
                /* temperature */ 0.0d,
                request.tenantId(),
                Role.REVIEWER,
                projectedCost);
        String promptHash = PromptHasher.hash(aReq);

        // Single attempt — if Anthropic is down at 16:30 ET, today's review is
        // skipped and tomorrow's run picks up. The reviewer is advisory; the
        // retry-library wire is reserved for the Sentinel/Narrator hot/warm
        // paths where queue+backoff is needed. Per architecture-spec §4.9
        // "Reviewer queue with exponential backoff" is the ASPIRATIONAL Phase
        // 7 design; Phase 4 ships single-attempt and a Phase 7 follow-up
        // wires the retry library if 16:30 ET availability proves unreliable.
        AnthropicResponse response = anthropicClient.submit(aReq, /* retryEnabled */ false);

        // Audit — every variant gets a row, success or failure. Trace ID is
        // not yet wired through the scheduler path; pass empty string. The
        // OTel propagation lands in Phase 6 when Strimzi is real.
        try {
            auditWriter.record(aReq, response, /* traceId */ "");
        } catch (RuntimeException e) {
            // Audit failures must never propagate — reviewer is advisory.
            LOG.warn(
                    "daily reviewer audit write failed tenantId={} sessionDate={}: {}",
                    request.tenantId(),
                    request.sessionDate(),
                    e.toString());
        }

        if (!(response instanceof AnthropicResponse.Success success)) {
            LOG.warn(
                    "daily reviewer anthropic call non-success tenantId={} sessionDate={} outcome={}",
                    request.tenantId(),
                    request.sessionDate(),
                    response.getClass().getSimpleName());
            return Optional.empty();
        }

        // Reconcile post-call cost into the tracker so subsequent cap checks
        // see the actual spend (not just the projection).
        costTracker.recordCost(request.tenantId(), Role.REVIEWER, costTracker.today(), success.costUsd());

        String summary =
                success.responseText() == null ? "" : success.responseText().trim();
        if (summary.isEmpty()) {
            LOG.warn(
                    "daily reviewer received empty response text tenantId={} sessionDate={}",
                    request.tenantId(),
                    request.sessionDate());
            return Optional.empty();
        }

        // Phase A boundary: persisted report carries empty proposals + empty
        // anomalies lists. Opus's free-text summary is preserved verbatim;
        // structured anomaly + proposal extraction lands in Phase B once the
        // user-approval UI flow is in place (architecture-spec §22 #10 +
        // ai-prompt-management skill MUST NOT #4).
        long totalTokens = (long) success.inputTokens() + (long) success.outputTokens() + (long) success.cachedTokens();
        return Optional.of(new DailyReport(
                request.tenantId(),
                request.sessionDate(),
                summary,
                List.of(),
                List.of(),
                DailyReport.Outcome.COMPLETED,
                Instant.now(clock),
                model,
                promptHash,
                totalTokens,
                success.costUsd()));
    }

    /**
     * Conservative pre-flight cost estimate. Uses
     * {@link CostCalculator#compute} with a generous input-token assumption so
     * the reviewer never starves itself with too-tight projections, while
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
