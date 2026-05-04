package com.levelsweep.aiagent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.connection.ConnectionMonitor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AiAgentMetrics}. Verifies the four meters that the
 * Phase 4 alerts in {@code infra/modules/observability/alerts.tf} key off:
 *
 * <ul>
 *   <li>{@code ai.cost.daily_total_usd} — gauge per (tenant, role)</li>
 *   <li>{@code ai.narrator.skipped} — counter tagged (tenant, reason)</li>
 *   <li>{@code ai.narrator.fired} — counter tagged (tenant)</li>
 *   <li>{@code ai.reviewer.run.complete} — counter tagged (tenant, outcome)</li>
 * </ul>
 *
 * <p>Tests run against a {@link SimpleMeterRegistry}; the production wiring
 * uses the Quarkus Micrometer Prometheus extension which delegates to the same
 * Micrometer abstractions, so meter shape is identical.
 */
class AiAgentMetricsTest {

    private static final String TENANT = "OWNER";
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 2);

    private MeterRegistry registry;
    private AiAgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiAgentMetrics(registry);
    }

    @Test
    void recordCostUpdateRegistersGaugePerTenantRole() {
        metrics.recordCostUpdate(TENANT, Role.NARRATOR, TODAY, new BigDecimal("0.42"));

        Double value = registry.find(AiAgentMetrics.METER_COST_DAILY)
                .tag("tenant_id", TENANT)
                .tag("role", "narrator")
                .gauge()
                .value();
        assertThat(value).isEqualTo(0.42d);
    }

    @Test
    void recordCostUpdateMutatesExistingGaugeOnSecondCall() {
        metrics.recordCostUpdate(TENANT, Role.NARRATOR, TODAY, new BigDecimal("0.10"));
        metrics.recordCostUpdate(TENANT, Role.NARRATOR, TODAY, new BigDecimal("0.55"));

        Double value = registry.find(AiAgentMetrics.METER_COST_DAILY)
                .tag("tenant_id", TENANT)
                .tag("role", "narrator")
                .gauge()
                .value();
        assertThat(value).isEqualTo(0.55d);

        // Single registered gauge for this (tenant, role) — second update did
        // not register a duplicate.
        long matchingGauges = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(AiAgentMetrics.METER_COST_DAILY))
                .filter(m -> TENANT.equals(m.getId().getTag("tenant_id")))
                .filter(m -> "narrator".equals(m.getId().getTag("role")))
                .count();
        assertThat(matchingGauges).isEqualTo(1);
    }

    @Test
    void recordCostUpdateScopesGaugeByRole() {
        metrics.recordCostUpdate(TENANT, Role.NARRATOR, TODAY, new BigDecimal("0.10"));
        metrics.recordCostUpdate(TENANT, Role.REVIEWER, TODAY, new BigDecimal("0.20"));

        Double narratorValue = registry.find(AiAgentMetrics.METER_COST_DAILY)
                .tag("tenant_id", TENANT)
                .tag("role", "narrator")
                .gauge()
                .value();
        Double reviewerValue = registry.find(AiAgentMetrics.METER_COST_DAILY)
                .tag("tenant_id", TENANT)
                .tag("role", "reviewer")
                .gauge()
                .value();
        assertThat(narratorValue).isEqualTo(0.10d);
        assertThat(reviewerValue).isEqualTo(0.20d);
    }

    @Test
    void narratorSkippedIncrementsCounterTaggedByReason() {
        metrics.narratorSkipped(TENANT, AiAgentMetrics.NarratorSkipReason.COST_CAP);
        metrics.narratorSkipped(TENANT, AiAgentMetrics.NarratorSkipReason.COST_CAP);
        metrics.narratorSkipped(TENANT, AiAgentMetrics.NarratorSkipReason.ANTHROPIC_FAILURE);

        double costCapCount = registry.find(AiAgentMetrics.METER_NARRATOR_SKIPPED)
                .tag("tenant_id", TENANT)
                .tag("reason", "cost_cap")
                .counter()
                .count();
        double anthropicCount = registry.find(AiAgentMetrics.METER_NARRATOR_SKIPPED)
                .tag("tenant_id", TENANT)
                .tag("reason", "anthropic_failure")
                .counter()
                .count();
        assertThat(costCapCount).isEqualTo(2.0d);
        assertThat(anthropicCount).isEqualTo(1.0d);
    }

    @Test
    void narratorFiredIncrementsCounter() {
        metrics.narratorFired(TENANT);
        metrics.narratorFired(TENANT);

        double count = registry.find(AiAgentMetrics.METER_NARRATOR_FIRED)
                .tag("tenant_id", TENANT)
                .counter()
                .count();
        assertThat(count).isEqualTo(2.0d);
    }

    @Test
    void reviewerRunCompleteIncrementsCounterTaggedByOutcome() {
        metrics.reviewerRunComplete(TENANT, AiAgentMetrics.ReviewerRunOutcome.COMPLETED);
        metrics.reviewerRunComplete(TENANT, AiAgentMetrics.ReviewerRunOutcome.SKIPPED);

        double completedCount = registry.find(AiAgentMetrics.METER_REVIEWER_RUN_COMPLETE)
                .tag("tenant_id", TENANT)
                .tag("outcome", "completed")
                .counter()
                .count();
        double skippedCount = registry.find(AiAgentMetrics.METER_REVIEWER_RUN_COMPLETE)
                .tag("tenant_id", TENANT)
                .tag("outcome", "skipped")
                .counter()
                .count();
        assertThat(completedCount).isEqualTo(1.0d);
        assertThat(skippedCount).isEqualTo(1.0d);
    }

    @Test
    void noopFactoryReturnsUsableInstance() {
        AiAgentMetrics noop = AiAgentMetrics.noop();
        // Should not throw, and the underlying registry should record the meters.
        noop.narratorFired(TENANT);
        noop.recordCostUpdate(TENANT, Role.NARRATOR, TODAY, new BigDecimal("0.01"));
        assertThat(noop.registry().find(AiAgentMetrics.METER_NARRATOR_FIRED).counter())
                .isNotNull();
        assertThat(noop.registry().find(AiAgentMetrics.METER_COST_DAILY).gauge())
                .isNotNull();
    }

    @Test
    void rejectsNullArgs() {
        assertThatNullPointerException()
                .isThrownBy(() -> metrics.recordCostUpdate(null, Role.NARRATOR, TODAY, BigDecimal.ZERO));
        assertThatNullPointerException()
                .isThrownBy(() -> metrics.recordCostUpdate(TENANT, null, TODAY, BigDecimal.ZERO));
        assertThatNullPointerException()
                .isThrownBy(() -> metrics.recordCostUpdate(TENANT, Role.NARRATOR, null, BigDecimal.ZERO));
        assertThatNullPointerException().isThrownBy(() -> metrics.recordCostUpdate(TENANT, Role.NARRATOR, TODAY, null));
        assertThatNullPointerException()
                .isThrownBy(() -> metrics.narratorSkipped(null, AiAgentMetrics.NarratorSkipReason.COST_CAP));
        assertThatNullPointerException().isThrownBy(() -> metrics.narratorSkipped(TENANT, null));
        assertThatNullPointerException().isThrownBy(() -> metrics.narratorFired(null));
        assertThatNullPointerException()
                .isThrownBy(() -> metrics.reviewerRunComplete(null, AiAgentMetrics.ReviewerRunOutcome.COMPLETED));
        assertThatNullPointerException().isThrownBy(() -> metrics.reviewerRunComplete(TENANT, null));
        assertThatNullPointerException()
                .isThrownBy(() -> metrics.recordConnectionState(null, ConnectionMonitor.State.HEALTHY));
        assertThatNullPointerException().isThrownBy(() -> metrics.recordConnectionState("anthropic", null));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelAllow(null, "PDH", "explicit_allow"));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelAllow(TENANT, null, "explicit_allow"));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelAllow(TENANT, "PDH", null));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelVetoApplied(null, "PDH"));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelVetoApplied(TENANT, null));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelFallback(null, "timeout"));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelFallback(TENANT, null));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelSkipped(null, "flag_off"));
        assertThatNullPointerException().isThrownBy(() -> metrics.sentinelSkipped(TENANT, null));
    }

    // ---------- Phase 5 / S3 — Sentinel meters (ADR-0007 §3 + §6) ----------

    @Test
    void sentinelAllow_incrementsCounterTaggedByTenantLevelDecisionPath() {
        metrics.sentinelAllow(TENANT, "PDH", "explicit_allow");
        metrics.sentinelAllow(TENANT, "PDH", "explicit_allow");
        metrics.sentinelAllow(TENANT, "PDH", "low_confidence_veto_overridden");
        metrics.sentinelAllow(TENANT, "PDL", "fallback_allow");

        double explicit = registry.find(AiAgentMetrics.METER_SENTINEL_ALLOW)
                .tag("tenant_id", TENANT)
                .tag("level_swept", "PDH")
                .tag("decision_path", "explicit_allow")
                .counter()
                .count();
        double overridden = registry.find(AiAgentMetrics.METER_SENTINEL_ALLOW)
                .tag("tenant_id", TENANT)
                .tag("level_swept", "PDH")
                .tag("decision_path", "low_confidence_veto_overridden")
                .counter()
                .count();
        double fallbackPdl = registry.find(AiAgentMetrics.METER_SENTINEL_ALLOW)
                .tag("tenant_id", TENANT)
                .tag("level_swept", "PDL")
                .tag("decision_path", "fallback_allow")
                .counter()
                .count();
        assertThat(explicit).isEqualTo(2.0d);
        assertThat(overridden).isEqualTo(1.0d);
        assertThat(fallbackPdl).isEqualTo(1.0d);
    }

    @Test
    void sentinelVetoApplied_incrementsCounterTaggedByTenantLevel() {
        metrics.sentinelVetoApplied(TENANT, "PMH");
        metrics.sentinelVetoApplied(TENANT, "PMH");

        double count = registry.find(AiAgentMetrics.METER_SENTINEL_VETO_APPLIED)
                .tag("tenant_id", TENANT)
                .tag("level_swept", "PMH")
                .counter()
                .count();
        assertThat(count).isEqualTo(2.0d);
    }

    @Test
    void sentinelFallback_incrementsCounterPerReasonLabel() {
        metrics.sentinelFallback(TENANT, "transport");
        metrics.sentinelFallback(TENANT, "rate_limit");
        metrics.sentinelFallback(TENANT, "cost_cap");
        metrics.sentinelFallback(TENANT, "parse");
        metrics.sentinelFallback(TENANT, "timeout");
        metrics.sentinelFallback(TENANT, "cb_open");

        for (String reason : new String[] {"transport", "rate_limit", "cost_cap", "parse", "timeout", "cb_open"}) {
            double count = registry.find(AiAgentMetrics.METER_SENTINEL_FALLBACK)
                    .tag("tenant_id", TENANT)
                    .tag("reason", reason)
                    .counter()
                    .count();
            assertThat(count).as("fallback{reason=%s}", reason).isEqualTo(1.0d);
        }
    }

    @Test
    void sentinelSkipped_incrementsCounterTaggedByTenantReason() {
        metrics.sentinelSkipped(TENANT, "flag_off");
        metrics.sentinelSkipped(TENANT, "flag_off");

        double count = registry.find(AiAgentMetrics.METER_SENTINEL_SKIPPED)
                .tag("tenant_id", TENANT)
                .tag("reason", "flag_off")
                .counter()
                .count();
        assertThat(count).isEqualTo(2.0d);
    }

    @Test
    void recordConnectionStateRegistersGaugePerDependency() {
        metrics.recordConnectionState("anthropic", ConnectionMonitor.State.HEALTHY);

        Double value = registry.find(AiAgentMetrics.METER_CONNECTION_STATE)
                .tag("dependency", "anthropic")
                .gauge()
                .value();
        assertThat(value).isEqualTo(0.0d);
    }

    @Test
    void recordConnectionStateMutatesExistingGaugeOnSecondCall() {
        metrics.recordConnectionState("anthropic", ConnectionMonitor.State.HEALTHY);
        metrics.recordConnectionState("anthropic", ConnectionMonitor.State.UNHEALTHY);

        Double value = registry.find(AiAgentMetrics.METER_CONNECTION_STATE)
                .tag("dependency", "anthropic")
                .gauge()
                .value();
        assertThat(value).isEqualTo(2.0d);

        // Single registered gauge for this dependency — second update did not
        // register a duplicate.
        long matchingGauges = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(AiAgentMetrics.METER_CONNECTION_STATE))
                .filter(m -> "anthropic".equals(m.getId().getTag("dependency")))
                .count();
        assertThat(matchingGauges).isEqualTo(1);
    }

    @Test
    void recordConnectionStateOrdinalMappingMatchesAlertContract() {
        // HEALTHY=0, DEGRADED=1, UNHEALTHY=2, RECOVERING=3 — alert #11 fires at value >= 2.
        metrics.recordConnectionState("anthropic", ConnectionMonitor.State.HEALTHY);
        assertThat(gaugeValue("anthropic")).isEqualTo(0.0d);

        metrics.recordConnectionState("anthropic", ConnectionMonitor.State.DEGRADED);
        assertThat(gaugeValue("anthropic")).isEqualTo(1.0d);

        metrics.recordConnectionState("anthropic", ConnectionMonitor.State.UNHEALTHY);
        assertThat(gaugeValue("anthropic")).isEqualTo(2.0d);

        metrics.recordConnectionState("anthropic", ConnectionMonitor.State.RECOVERING);
        assertThat(gaugeValue("anthropic")).isEqualTo(3.0d);
    }

    @Test
    void recordConnectionStateScopesGaugeByDependency() {
        metrics.recordConnectionState("anthropic", ConnectionMonitor.State.HEALTHY);
        metrics.recordConnectionState("alpaca-stocks", ConnectionMonitor.State.UNHEALTHY);

        assertThat(gaugeValue("anthropic")).isEqualTo(0.0d);
        assertThat(gaugeValue("alpaca-stocks")).isEqualTo(2.0d);
    }

    private double gaugeValue(String dependency) {
        return registry.find(AiAgentMetrics.METER_CONNECTION_STATE)
                .tag("dependency", dependency)
                .gauge()
                .value();
    }
}
