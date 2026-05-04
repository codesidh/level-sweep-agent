package com.levelsweep.aiagent.observability;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.connection.ConnectionMonitor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Micrometer bridge for ai-agent-service. Translates internal AI-call state
 * (cost cap accumulator, narrator skip/fire decisions, reviewer cron
 * completion) into Micrometer meters so the App Insights Java agent ships
 * them as {@code customMetrics} rows. The Phase 4 alerts defined in
 * {@code infra/modules/observability/alerts.tf} key off these meter names.
 *
 * <p>Naming follows the same snake-case convention as
 * {@code services/market-data-service/.../observability/MetricsBinding.java}.
 * App Insights translates dots to underscores in {@code customMetrics} names,
 * so the alert KQL queries match {@code ai_cost_daily_total_usd},
 * {@code ai_narrator_skipped}, {@code ai_narrator_fired}, and
 * {@code ai_reviewer_run_complete}.
 *
 * <p>Meters exposed:
 *
 * <ul>
 *   <li><b>{@code ai.cost.daily_total_usd}</b> — gauge per
 *       {@code (tenant_id, role)}. Updated on every
 *       {@code DailyCostTracker#recordCost}. The alert reads the maximum value
 *       seen in a 1-hour window and compares against the per-role cap (which
 *       is also published as the {@code cap_usd} dimension for the alert KQL
 *       to threshold against).</li>
 *   <li><b>{@code ai.narrator.skipped}</b> — counter tagged
 *       {@code (tenant_id, reason)}. Incremented when
 *       {@code TradeNarrator#narrate} returns empty (cost-cap, anthropic
 *       failure, empty response).</li>
 *   <li><b>{@code ai.narrator.fired}</b> — counter tagged
 *       {@code (tenant_id)}. Incremented on every successful narration
 *       (Optional.of returned). The alert ratio is
 *       {@code skipped / (skipped + fired)} over a 1-hour window.</li>
 *   <li><b>{@code ai.reviewer.run.complete}</b> — counter tagged
 *       {@code (tenant_id, outcome)}. Incremented once per scheduler fire
 *       (success OR stub), regardless of substantive content. The alert
 *       fires when the count is {@code 0} during the 16:30-17:00 ET window
 *       per tenant.</li>
 * </ul>
 *
 * <p>The cost gauge uses a per-key {@link AtomicReference} for the value the
 * gauge reads. {@link Gauge.builder} with a strong {@link AtomicReference}
 * reference and {@link AtomicReference#get} as the value function is the
 * canonical Micrometer pattern for an externally-driven gauge — see the
 * Micrometer concepts doc. Each {@code (tenant_id, role)} pair gets its own
 * registered gauge on first observation; subsequent updates only mutate the
 * underlying reference (no re-registration).
 *
 * <p>Cap dimension: the per-role daily cap is constant (read once from
 * configuration at {@link com.levelsweep.aiagent.cost.DailyCostTracker}
 * construction). We do NOT publish it as a separate meter because the alert
 * KQL hard-codes the per-role threshold via {@code case()} for the time
 * being. A Phase 5 follow-up wires the cap as a dimension once
 * multi-tenant config exists.
 *
 * <p>{@link AiAgentMetrics#noop()} returns an instance backed by an
 * isolated {@link SimpleMeterRegistry} — used by unit tests that don't care
 * about meter assertions but still need to instantiate
 * {@link com.levelsweep.aiagent.cost.DailyCostTracker},
 * {@link com.levelsweep.aiagent.narrator.TradeNarrator},
 * {@link com.levelsweep.aiagent.reviewer.DailyReviewer}, etc.
 */
@ApplicationScoped
public class AiAgentMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(AiAgentMetrics.class);

    /** Counter / gauge name for the cost gauge — see class javadoc. */
    public static final String METER_COST_DAILY = "ai.cost.daily_total_usd";

    public static final String METER_NARRATOR_SKIPPED = "ai.narrator.skipped";
    public static final String METER_NARRATOR_FIRED = "ai.narrator.fired";
    public static final String METER_REVIEWER_RUN_COMPLETE = "ai.reviewer.run.complete";

    /**
     * Connection FSM gauge — per-dependency lifecycle state ordinal. The Phase 5
     * S1 alert {@code anthropic_cb_unhealthy} (alert #11) keys off
     * {@code customMetrics.connection_state} where
     * {@code customDimensions.dependency == "anthropic"} and {@code value >= 2}.
     * Phase 1 / Phase 3 use the same name for their own dependencies, so the
     * dimension is the routing key.
     */
    public static final String METER_CONNECTION_STATE = "connection.state";

    private final MeterRegistry registry;

    /**
     * Per-{@code (tenantId, role)} cost holder. The {@link AtomicReference}
     * is the source-of-truth value the gauge reads. We keep a strong
     * reference to it in the map so Micrometer's weak-reference gauge
     * registration does not GC it.
     */
    private final ConcurrentHashMap<CostKey, AtomicReference<BigDecimal>> costRefs = new ConcurrentHashMap<>();

    /**
     * Per-dependency Connection FSM ordinal holder. Same per-key
     * {@link AtomicInteger} pattern as {@link #costRefs} — register the gauge
     * once per dependency, mutate the ref on subsequent observations.
     */
    private final ConcurrentHashMap<String, AtomicInteger> connectionStateRefs = new ConcurrentHashMap<>();

    @Inject
    public AiAgentMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Test-only no-op factory. Returns an instance backed by an isolated
     * {@link SimpleMeterRegistry} that no agent observes; meters can still
     * be inspected via the registry returned by {@link #registry()}.
     */
    public static AiAgentMetrics noop() {
        return new AiAgentMetrics(new SimpleMeterRegistry());
    }

    /** Exposed for test assertions only. */
    public MeterRegistry registry() {
        return registry;
    }

    /**
     * Update the per-{@code (tenant, role)} cost gauge. Called from
     * {@link com.levelsweep.aiagent.cost.DailyCostTracker#recordCost} after
     * each successful Anthropic call's cost is reconciled. The gauge value
     * is the in-memory accumulated spend for the current ET-local day; on
     * date rollover the new day's bucket starts at zero (the previous day's
     * gauge keeps reporting its last value until the JVM rolls — alerts
     * only ever look at the most recent 1-hour window so historical values
     * don't pollute the signal).
     *
     * <p>{@code date} is captured into the gauge for tracing only; the
     * App Insights agent does not key on it. The current day's value is
     * always what production runtime asserts.
     */
    public void recordCostUpdate(String tenantId, Role role, LocalDate date, BigDecimal currentSpendUsd) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(currentSpendUsd, "currentSpendUsd");

        CostKey key = new CostKey(tenantId, role);
        AtomicReference<BigDecimal> ref = costRefs.computeIfAbsent(key, k -> {
            AtomicReference<BigDecimal> r = new AtomicReference<>(BigDecimal.ZERO);
            // Register the gauge once per (tenant, role). Micrometer dedupes on
            // identity tags, but computeIfAbsent's atomic semantics mean the
            // builder runs at most once per key anyway.
            Gauge.builder(METER_COST_DAILY, r, ar -> ar.get().doubleValue())
                    .description("Per-(tenant, role) AI daily cost accumulator (USD), reset at 00:00 ET.")
                    .tags(Tags.of("tenant_id", k.tenantId(), "role", k.role().configKey()))
                    .register(registry);
            return r;
        });
        ref.set(currentSpendUsd);
    }

    /**
     * Update the per-dependency Connection FSM gauge. Called from the FSM
     * wrapper (e.g.
     * {@link com.levelsweep.aiagent.connection.AnthropicConnectionMonitor})
     * whenever the underlying state transitions. Value is the ordinal:
     * HEALTHY=0, DEGRADED=1, UNHEALTHY=2, RECOVERING=3 — matches the alert KQL
     * threshold {@code value >= 2}.
     *
     * <p>Same per-key gauge-registration pattern as {@link #recordCostUpdate}:
     * {@link AtomicInteger} held strongly in {@link #connectionStateRefs} so
     * Micrometer's weak ref does not GC it; gauge registered exactly once per
     * dependency.
     */
    public void recordConnectionState(String dependency, ConnectionMonitor.State state) {
        Objects.requireNonNull(dependency, "dependency");
        Objects.requireNonNull(state, "state");

        AtomicInteger ref = connectionStateRefs.computeIfAbsent(dependency, k -> {
            AtomicInteger r = new AtomicInteger(state.ordinal());
            Gauge.builder(METER_CONNECTION_STATE, r, AtomicInteger::get)
                    .description(
                            "Per-dependency Connection FSM ordinal: HEALTHY=0, DEGRADED=1, UNHEALTHY=2, RECOVERING=3.")
                    .tags(Tags.of("dependency", k))
                    .register(registry);
            return r;
        });
        ref.set(state.ordinal());
    }

    /** Increment the narrator-skipped counter. {@code reason} is one of the labels in {@link NarratorSkipReason}. */
    public void narratorSkipped(String tenantId, NarratorSkipReason reason) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(reason, "reason");
        Counter.builder(METER_NARRATOR_SKIPPED)
                .description("Narrator calls that returned empty (cost-cap, anthropic failure, empty response).")
                .tags(Tags.of("tenant_id", tenantId, "reason", reason.label()))
                .register(registry)
                .increment();
    }

    /** Increment the narrator-fired counter. */
    public void narratorFired(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Counter.builder(METER_NARRATOR_FIRED)
                .description("Narrator calls that produced a substantive narrative.")
                .tags(Tags.of("tenant_id", tenantId))
                .register(registry)
                .increment();
    }

    /**
     * Increment the reviewer-run-complete counter. Fired once per scheduler
     * invocation (success OR stub). The alert fires when the count is 0 in
     * the 16:30-17:00 ET window per tenant — so a single increment per
     * scheduler fire is all the alert needs.
     */
    public void reviewerRunComplete(String tenantId, ReviewerRunOutcome outcome) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(outcome, "outcome");
        Counter.builder(METER_REVIEWER_RUN_COMPLETE)
                .description("Daily reviewer scheduler completed — one increment per fire (success or stub).")
                .tags(Tags.of("tenant_id", tenantId, "outcome", outcome.label()))
                .register(registry)
                .increment();
        LOG.debug("ai-agent metrics: reviewer.run.complete tenantId={} outcome={}", tenantId, outcome.label());
    }

    /** Stable label values for the {@code reason} dimension on {@link #METER_NARRATOR_SKIPPED}. */
    public enum NarratorSkipReason {
        COST_CAP("cost_cap"),
        ANTHROPIC_FAILURE("anthropic_failure"),
        EMPTY_RESPONSE("empty_response");

        private final String label;

        NarratorSkipReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Stable label values for the {@code outcome} dimension on {@link #METER_REVIEWER_RUN_COMPLETE}. */
    public enum ReviewerRunOutcome {
        COMPLETED("completed"),
        SKIPPED("skipped");

        private final String label;

        ReviewerRunOutcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Composite map key for the cost gauge. */
    private record CostKey(String tenantId, Role role) {
        CostKey {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(role, "role");
        }
    }
}
