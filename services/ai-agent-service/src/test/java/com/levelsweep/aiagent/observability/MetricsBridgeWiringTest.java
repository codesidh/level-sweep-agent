package com.levelsweep.aiagent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.audit.AiCallAuditWriter;
import com.levelsweep.aiagent.cost.DailyCostMongoRepository;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.levelsweep.aiagent.narrator.NarrationPromptBuilder;
import com.levelsweep.aiagent.narrator.NarrationRequest;
import com.levelsweep.aiagent.narrator.TradeNarrator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Integration-style test that verifies the {@link AiAgentMetrics} bridge is
 * wired into the three production call-sites (DailyCostTracker.recordCost,
 * TradeNarrator.narrate skip + fire branches, DailyReviewerScheduler.runOnce
 * completion). Uses a {@link SimpleMeterRegistry} backing instance and
 * inspects meter counts after each call.
 *
 * <p>Phase 4 alerts in {@code infra/modules/observability/alerts.tf} key off
 * these meter names — this test is the unit-level guard against a refactor
 * silently breaking the alert hooks.
 */
class MetricsBridgeWiringTest {

    private static final String TENANT = "OWNER";
    private static final String TRADE_ID = "TR_2026-05-02_001";
    private static final Instant NOW = Instant.parse("2026-05-02T20:30:00Z");
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ET);

    @Test
    void recordCostUpdatesGauge() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AiAgentMetrics metrics = new AiAgentMetrics(registry);
        DailyCostMongoRepository repo = mock(DailyCostMongoRepository.class);
        when(repo.sumByDay(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        DailyCostTracker tracker = new DailyCostTracker(
                repo,
                Clock.fixed(NOW, ET),
                metrics,
                new BigDecimal("1.00"),
                new BigDecimal("1.00"),
                new BigDecimal("5.00"),
                new BigDecimal("2.00"));

        tracker.recordCost(TENANT, Role.NARRATOR, TODAY, new BigDecimal("0.30"));

        Double gauge = registry.find(AiAgentMetrics.METER_COST_DAILY)
                .tag("tenant_id", TENANT)
                .tag("role", "narrator")
                .gauge()
                .value();
        assertThat(gauge).isEqualTo(0.30d);
    }

    @Test
    void narratorSkippedOnCostCap() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AiAgentMetrics metrics = new AiAgentMetrics(registry);
        DailyCostTracker tracker = mock(DailyCostTracker.class);
        when(tracker.today()).thenReturn(TODAY);
        when(tracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(true);
        when(tracker.capFor(Role.NARRATOR)).thenReturn(new BigDecimal("1.00"));
        when(tracker.currentSpend(any(), any(), any())).thenReturn(new BigDecimal("0.99"));

        TradeNarrator narrator = new TradeNarrator(
                mock(AnthropicClient.class),
                tracker,
                mock(AiCallAuditWriter.class),
                metrics,
                Clock.fixed(NOW, ET),
                "claude-sonnet-4-6");

        Optional<?> result = narrator.narrate(new NarrationRequest(
                TENANT, NarrationPromptBuilder.EVENT_FILL, "contract=SPY 2026-05-02 100 C", TRADE_ID, NOW));

        assertThat(result).isEmpty();
        double count = registry.find(AiAgentMetrics.METER_NARRATOR_SKIPPED)
                .tag("tenant_id", TENANT)
                .tag("reason", "cost_cap")
                .counter()
                .count();
        assertThat(count).isEqualTo(1.0d);
    }

    @Test
    void narratorSkippedOnAnthropicFailure() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AiAgentMetrics metrics = new AiAgentMetrics(registry);
        DailyCostTracker tracker = mock(DailyCostTracker.class);
        when(tracker.today()).thenReturn(TODAY);
        when(tracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);

        AnthropicClient client = mock(AnthropicClient.class);
        when(client.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.RateLimited("req-1", Role.NARRATOR, "claude-sonnet-4-6", 100L, "rate"));

        TradeNarrator narrator = new TradeNarrator(
                client,
                tracker,
                mock(AiCallAuditWriter.class),
                metrics,
                Clock.fixed(NOW, ET),
                "claude-sonnet-4-6");

        narrator.narrate(new NarrationRequest(
                TENANT, NarrationPromptBuilder.EVENT_FILL, "contract=SPY 2026-05-02 100 C", TRADE_ID, NOW));

        double count = registry.find(AiAgentMetrics.METER_NARRATOR_SKIPPED)
                .tag("tenant_id", TENANT)
                .tag("reason", "anthropic_failure")
                .counter()
                .count();
        assertThat(count).isEqualTo(1.0d);
    }

    @Test
    void narratorFiredOnSuccess() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AiAgentMetrics metrics = new AiAgentMetrics(registry);
        DailyCostTracker tracker = mock(DailyCostTracker.class);
        when(tracker.today()).thenReturn(TODAY);
        when(tracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);

        AnthropicClient client = mock(AnthropicClient.class);
        when(client.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(new AnthropicResponse.Success(
                        "req-2",
                        Role.NARRATOR,
                        "claude-sonnet-4-6",
                        120L,
                        "Trade filled at $1.20.",
                        List.of(),
                        500,
                        50,
                        0,
                        new BigDecimal("0.0035")));

        TradeNarrator narrator = new TradeNarrator(
                client,
                tracker,
                mock(AiCallAuditWriter.class),
                metrics,
                Clock.fixed(NOW, ET),
                "claude-sonnet-4-6");

        Optional<?> result = narrator.narrate(new NarrationRequest(
                TENANT, NarrationPromptBuilder.EVENT_FILL, "contract=SPY 2026-05-02 100 C", TRADE_ID, NOW));

        assertThat(result).isPresent();
        double count = registry.find(AiAgentMetrics.METER_NARRATOR_FIRED)
                .tag("tenant_id", TENANT)
                .counter()
                .count();
        assertThat(count).isEqualTo(1.0d);
    }

    // Reviewer-scheduler metric wiring is exercised inside the reviewer/
    // package via DailyReviewerSchedulerTest (which has access to the
    // package-private runOnce() seam). Cross-package assertions here would
    // require widening DailyReviewerScheduler's visibility, which the agent
    // pattern in this repo deliberately avoids.
}
