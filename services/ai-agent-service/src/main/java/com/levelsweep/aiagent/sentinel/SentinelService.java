package com.levelsweep.aiagent.sentinel;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.audit.AiCallAuditWriter;
import com.levelsweep.aiagent.connection.AnthropicConnectionMonitor;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Allow;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.DecisionPath;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Fallback;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.FallbackReason;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.ReasonCode;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Veto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-Trade Sentinel orchestrator (ADR-0007). Sole synchronous entry point
 * called by the saga between RiskGate and StrikeSelector — wires the prompt
 * builder, hand-rolled Anthropic client, response parser, audit writer, and
 * metrics under a hard 750ms wall-clock budget per ADR-0007 §4.
 *
 * <p><b>Fail-OPEN</b> (ADR-0007 §3): every failure mode — transport, rate
 * limit, cost cap, parse error, timeout, CB-open — coerces to a
 * {@link Fallback} which the saga interprets as ALLOW. The system never
 * silently halts entries on a Sentinel outage; the deterministic Risk FSM is
 * the fail-closed mechanism, not Sentinel.
 *
 * <p><b>Single attempt, no retry</b>: per ADR-0007 §4 Sentinel is invoked
 * with {@code retryEnabled=false}; the latency budget is binary (one shot
 * or default ALLOW). Narrator/Reviewer continue to use retry.
 *
 * <p><b>Feature flag</b>: {@code levelsweep.sentinel.enabled} defaults
 * {@code false} (ADR-0007 §7). When OFF, {@link #evaluate} returns an
 * immediate {@link Allow} with {@link DecisionPath#FALLBACK_ALLOW},
 * increments {@code ai.sentinel.skipped{reason="flag_off"}}, and skips the
 * audit row entirely (no Anthropic spend).
 */
@ApplicationScoped
public class SentinelService {

    private static final Logger LOG = LoggerFactory.getLogger(SentinelService.class);

    /**
     * Wall-clock timeout for the Anthropic submit (ADR-0007 §4 — 750ms hard
     * timeout, fail-OPEN past this).
     */
    public static final Duration HTTP_TIMEOUT = Duration.ofMillis(750);

    private static final String SKIPPED_REASON_FLAG_OFF = "flag_off";
    private static final String FALLBACK_DISABLED_REASON_TEXT = "sentinel disabled by feature flag";

    private final SentinelPromptBuilder promptBuilder;
    private final SentinelResponseParser responseParser;
    private final AnthropicClient anthropicClient;
    private final AnthropicConnectionMonitor connectionMonitor;
    private final AiCallAuditWriter auditWriter;
    private final AiAgentMetrics metrics;
    private final Clock clock;
    private final boolean enabled;
    private final Executor executor;
    private final Duration httpTimeout;

    @Inject
    public SentinelService(
            SentinelPromptBuilder promptBuilder,
            SentinelResponseParser responseParser,
            AnthropicClient anthropicClient,
            AnthropicConnectionMonitor connectionMonitor,
            AiCallAuditWriter auditWriter,
            AiAgentMetrics metrics,
            Clock clock,
            @ConfigProperty(name = "levelsweep.sentinel.enabled", defaultValue = "false") boolean enabled) {
        this(
                promptBuilder,
                responseParser,
                anthropicClient,
                connectionMonitor,
                auditWriter,
                metrics,
                clock,
                enabled,
                ForkJoinPool.commonPool(),
                HTTP_TIMEOUT);
    }

    /** Test seam — pin the executor + timeout for deterministic timing assertions. */
    SentinelService(
            SentinelPromptBuilder promptBuilder,
            SentinelResponseParser responseParser,
            AnthropicClient anthropicClient,
            AnthropicConnectionMonitor connectionMonitor,
            AiCallAuditWriter auditWriter,
            AiAgentMetrics metrics,
            Clock clock,
            boolean enabled,
            Executor executor,
            Duration httpTimeout) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.responseParser = Objects.requireNonNull(responseParser, "responseParser");
        this.anthropicClient = Objects.requireNonNull(anthropicClient, "anthropicClient");
        this.connectionMonitor = Objects.requireNonNull(connectionMonitor, "connectionMonitor");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.enabled = enabled;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.httpTimeout = Objects.requireNonNull(httpTimeout, "httpTimeout");
    }

    /**
     * Evaluate one proposed trade. Always returns a non-null
     * {@link SentinelDecisionResponse}; never throws and never propagates an
     * Anthropic-side exception. The saga's exhaustive switch on the variant
     * is the only place the outcome is interpreted.
     */
    public SentinelDecisionResponse evaluate(SentinelDecisionRequest request) {
        Objects.requireNonNull(request, "request");

        // 1. Feature-flag short-circuit. No Anthropic call, no audit row.
        if (!enabled) {
            metrics.sentinelSkipped(request.tenantId(), SKIPPED_REASON_FLAG_OFF);
            Allow disabled = new Allow(
                    /* clientRequestId */ "",
                    BigDecimal.ZERO,
                    ReasonCode.OTHER,
                    FALLBACK_DISABLED_REASON_TEXT,
                    /* latencyMs */ 0L,
                    DecisionPath.FALLBACK_ALLOW);
            LOG.info(
                    "sentinel skipped (flag off) tenantId={} tradeId={} signalId={} levelSwept={}",
                    request.tenantId(),
                    request.tradeId(),
                    request.signalId(),
                    request.levelSwept());
            return disabled;
        }

        // 2. Build the prompt. We hold a reference to the AnthropicRequest so
        //    the audit writer can persist it on every variant (Success or
        //    Fallback).
        AnthropicRequest anthropicRequest = promptBuilder.build(request);

        // 3. Submit with a 750ms wall-clock timeout. Sentinel is single-attempt
        //    (ADR-0007 §4) — no retry. We capture nanoTime up-front so the
        //    latency on a TimeoutException is measured against the actual
        //    elapsed time, not the timeout constant.
        long startNs = System.nanoTime();
        AnthropicResponse anthropicResponse;
        try {
            anthropicResponse = submitWithTimeout(() -> anthropicClient.submit(anthropicRequest, /* retry */ false));
        } catch (TimeoutException e) {
            long latencyMs = elapsedMs(startNs);
            String levelSwept = request.levelSwept().name();
            Fallback timeoutFallback = new Fallback(/* clientRequestId */ "", FallbackReason.TIMEOUT, latencyMs);
            // Connection FSM state is read AFTER the timeout so the audit/log
            // captures the post-timeout state — useful when the same client is
            // routinely tipping into UNHEALTHY under sustained latency.
            LOG.warn(
                    "sentinel timeout > {}ms tenantId={} tradeId={} signalId={} levelSwept={}"
                            + " latencyMs={} connectionState={}",
                    httpTimeout.toMillis(),
                    request.tenantId(),
                    request.tradeId(),
                    request.signalId(),
                    levelSwept,
                    latencyMs,
                    connectionMonitor.state());
            // Audit + metrics still fire on timeout (ADR-0007 §3 — every
            // outcome has an audit row).
            safeRecordAudit(
                    anthropicRequest,
                    AnthropicResponse.TransportFailure.class,
                    null,
                    new AnthropicResponse.TransportFailure(
                            "", anthropicRequest.role(), anthropicRequest.model(), latencyMs, "sentinel_timeout"));
            recordMetricsAndLog(request, timeoutFallback);
            return timeoutFallback;
        }

        long latencyMs = elapsedMs(startNs);
        SentinelDecisionResponse decision =
                responseParser.parse(anthropicResponse, anthropicResponse.clientRequestId(), latencyMs);

        // 4. Audit row — fire once per evaluation. The writer accepts any
        //    AnthropicResponse variant (Success or failure) per ADR-0006
        //    audit pattern.
        safeRecordAudit(
                anthropicRequest, anthropicResponse.getClass(), anthropicResponse.clientRequestId(), anthropicResponse);

        // 5. Metrics + INFO log. The decision-path / reason-text labels match
        //    the ADR-0007 §3 + §6 published taxonomy.
        recordMetricsAndLog(request, decision);
        return decision;
    }

    /**
     * Submit the Anthropic call asynchronously and impose a hard
     * {@link #httpTimeout} ceiling. Returns the {@link AnthropicResponse}
     * variant on completion or throws {@link TimeoutException} if the
     * supplier did not produce a value in time.
     *
     * <p>Two failure modes are normalized here:
     *
     * <ul>
     *   <li>{@link TimeoutException} from {@code orTimeout} — propagated for
     *       the caller to translate to {@link FallbackReason#TIMEOUT}.</li>
     *   <li>Any other completion exception (the underlying Anthropic call
     *       should never throw, but defensive coverage matters) is wrapped as
     *       a transport-failure {@link AnthropicResponse.TransportFailure} so
     *       the parser maps it cleanly to {@link FallbackReason#TRANSPORT}.</li>
     * </ul>
     */
    private AnthropicResponse submitWithTimeout(Supplier<AnthropicResponse> supplier) throws TimeoutException {
        CompletableFuture<AnthropicResponse> future = CompletableFuture.supplyAsync(supplier, executor)
                .orTimeout(httpTimeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Surface as transport failure so the parser maps to TRANSPORT.
            return new AnthropicResponse.TransportFailure("", null, "", 0L, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException te) {
                throw te;
            }
            // Defensive: AnthropicClient.submit catches RuntimeException internally
            // and returns a TransportFailure. If anything else escapes, log + map.
            LOG.warn("sentinel anthropic call threw unexpectedly: {}", cause == null ? "null" : cause.toString());
            return new AnthropicResponse.TransportFailure(
                    "", null, "", 0L, cause == null ? "unknown" : cause.toString());
        }
    }

    /** Wrap audit writes so a Mongo blip never propagates into the saga thread. */
    private void safeRecordAudit(
            AnthropicRequest aReq,
            Class<? extends AnthropicResponse> variantClass,
            String clientRequestIdHint,
            AnthropicResponse aResp) {
        try {
            auditWriter.record(aReq, aResp, /* traceId */ "");
        } catch (RuntimeException e) {
            // Audit failures are advisory — the saga decision is what matters.
            LOG.warn(
                    "sentinel audit write failed tenantId={} variant={} clientRequestId={}: {}",
                    aReq.tenantId(),
                    variantClass.getSimpleName(),
                    clientRequestIdHint == null ? "" : clientRequestIdHint,
                    e.toString());
        }
    }

    /**
     * Increment the right counter for the decision variant and emit a single
     * INFO log. Metrics emit failures must never block the saga, so each call
     * is wrapped — the system trades observability degradation for liveness.
     */
    private void recordMetricsAndLog(SentinelDecisionRequest request, SentinelDecisionResponse decision) {
        String tenantId = request.tenantId();
        String levelSwept = request.levelSwept().name();
        switch (decision) {
            case Allow allow -> {
                String dpLabel = decisionPathLabel(allow.decisionPath());
                safeIncrement(() -> metrics.sentinelAllow(tenantId, levelSwept, dpLabel));
                LOG.info(
                        "sentinel decision tenantId={} tradeId={} signalId={} levelSwept={}"
                                + " decision=ALLOW confidence={} decisionPath={} latencyMs={} fallbackReason=",
                        tenantId,
                        request.tradeId(),
                        request.signalId(),
                        levelSwept,
                        allow.confidence(),
                        dpLabel,
                        allow.latencyMs());
            }
            case Veto veto -> {
                safeIncrement(() -> metrics.sentinelVetoApplied(tenantId, levelSwept));
                LOG.info(
                        "sentinel decision tenantId={} tradeId={} signalId={} levelSwept={}"
                                + " decision=VETO confidence={} decisionPath= latencyMs={} fallbackReason=",
                        tenantId,
                        request.tradeId(),
                        request.signalId(),
                        levelSwept,
                        veto.confidence(),
                        veto.latencyMs());
            }
            case Fallback fallback -> {
                String reasonLabel = fallbackReasonLabel(fallback.reason());
                safeIncrement(() -> metrics.sentinelFallback(tenantId, reasonLabel));
                LOG.info(
                        "sentinel decision tenantId={} tradeId={} signalId={} levelSwept={}"
                                + " decision=FALLBACK confidence=0.00 decisionPath=fallback_allow"
                                + " latencyMs={} fallbackReason={}",
                        tenantId,
                        request.tradeId(),
                        request.signalId(),
                        levelSwept,
                        fallback.latencyMs(),
                        reasonLabel);
            }
        }
    }

    private void safeIncrement(Runnable inc) {
        try {
            inc.run();
        } catch (RuntimeException e) {
            LOG.warn("sentinel metrics emit failed: {}", e.toString());
        }
    }

    private static String decisionPathLabel(DecisionPath path) {
        return switch (path) {
            case EXPLICIT_ALLOW -> "explicit_allow";
            case LOW_CONFIDENCE_VETO_OVERRIDDEN -> "low_confidence_veto_overridden";
            case FALLBACK_ALLOW -> "fallback_allow";
        };
    }

    private static String fallbackReasonLabel(FallbackReason reason) {
        return switch (reason) {
            case TRANSPORT -> "transport";
            case RATE_LIMIT -> "rate_limit";
            case COST_CAP -> "cost_cap";
            case PARSE -> "parse";
            case TIMEOUT -> "timeout";
            case CB_OPEN -> "cb_open";
        };
    }

    private static long elapsedMs(long startNs) {
        return Duration.ofNanos(System.nanoTime() - startNs).toMillis();
    }

    /** Test-seam access: was the feature flag enabled at construction time? */
    boolean enabled() {
        return enabled;
    }

    /** Test-seam: the configured wall-clock timeout. */
    Duration httpTimeout() {
        return httpTimeout;
    }

    /** Currently unused — placeholder for future wall-clock-driven decisions. */
    Clock clock() {
        return clock;
    }
}
