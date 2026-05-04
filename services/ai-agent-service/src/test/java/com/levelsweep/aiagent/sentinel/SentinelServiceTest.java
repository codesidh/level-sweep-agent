package com.levelsweep.aiagent.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicMessage;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import com.levelsweep.aiagent.audit.AiCallAuditWriter;
import com.levelsweep.aiagent.connection.AnthropicConnectionMonitor;
import com.levelsweep.aiagent.connection.ConnectionMonitor;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Bar;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Direction;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.IndicatorSnapshot;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.LevelSwept;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Allow;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.DecisionPath;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Fallback;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.FallbackReason;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.ReasonCode;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Veto;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link SentinelService} — the Phase 5 / S3 orchestrator.
 * Coverage spans the seven outcome variants (flag-off, Allow×2, Veto,
 * Fallback×4) plus audit + latency-recording properties; the orchestrator's
 * fail-OPEN posture under ADR-0007 §3 means every failure mode must coerce
 * to a {@link Fallback} and never propagate an exception.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SentinelServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-04T15:00:00Z"), ZoneOffset.UTC);
    private static final String MODEL = "claude-haiku-4-5";
    private static final String TENANT = "OWNER";
    private static final SentinelPromptBuilder PROMPT_BUILDER = new SentinelPromptBuilder(MODEL);
    private static final SentinelResponseParser PARSER = new SentinelResponseParser();
    private static final Duration TEST_TIMEOUT = Duration.ofMillis(750);

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private AnthropicConnectionMonitor connectionMonitor;

    @Mock
    private AiCallAuditWriter auditWriter;

    private MeterRegistry registry;
    private AiAgentMetrics metrics;
    private Executor executor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiAgentMetrics(registry);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sentinel-test-executor");
            t.setDaemon(true);
            return t;
        });
        when(connectionMonitor.state()).thenReturn(ConnectionMonitor.State.HEALTHY);
    }

    @AfterEach
    void tearDown() {
        if (executor instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
    }

    // ---------- Flag off ----------

    @Test
    void flagOff_returnsAllowFallbackPath_noAnthropicCall_noAudit() {
        SentinelService service = service(/* enabled */ false, TEST_TIMEOUT);

        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertThat(out).isInstanceOf(Allow.class);
        Allow allow = (Allow) out;
        assertThat(allow.decisionPath()).isEqualTo(DecisionPath.FALLBACK_ALLOW);
        assertThat(allow.reasonCode()).isEqualTo(ReasonCode.OTHER);
        assertThat(allow.reasonText()).isEqualTo("sentinel disabled by feature flag");
        assertThat(allow.confidence()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(allow.latencyMs()).isZero();
        assertThat(allow.clientRequestId()).isEmpty();

        // No AnthropicClient call, no audit row.
        verifyNoInteractions(anthropicClient);
        verifyNoInteractions(auditWriter);

        // The skipped meter increments with reason=flag_off.
        double skipped = registry.find(AiAgentMetrics.METER_SENTINEL_SKIPPED)
                .tag("tenant_id", TENANT)
                .tag("reason", "flag_off")
                .counter()
                .count();
        assertThat(skipped).isEqualTo(1.0d);
    }

    // ---------- Veto ----------

    @Test
    void veto_incrementsVetoApplied_andAudits() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(success("{\"decision\":\"VETO\",\"confidence\":0.92,"
                        + "\"reason_code\":\"STRUCTURE_DIVERGENCE\",\"reason_text\":\"EMAs diverging\"}"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertThat(out).isInstanceOf(Veto.class);
        Veto v = (Veto) out;
        assertThat(v.confidence()).isEqualByComparingTo("0.92");
        assertThat(v.reasonCode()).isEqualTo(ReasonCode.STRUCTURE_DIVERGENCE);

        double vetoCount = registry.find(AiAgentMetrics.METER_SENTINEL_VETO_APPLIED)
                .tag("tenant_id", TENANT)
                .tag("level_swept", "PDH")
                .counter()
                .count();
        assertThat(vetoCount).isEqualTo(1.0d);

        // Audit row written with the Success variant (retry disabled).
        verify(auditWriter, times(1)).record(any(AnthropicRequest.class), any(AnthropicResponse.Success.class), eq(""));
        verify(anthropicClient, times(1)).submit(any(AnthropicRequest.class), eq(false));
    }

    // ---------- Allow EXPLICIT ----------

    @Test
    void explicitAllow_incrementsAllowExplicitPath_andAudits() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(success("{\"decision\":\"ALLOW\",\"confidence\":0.78,"
                        + "\"reason_code\":\"STRUCTURE_MATCH\",\"reason_text\":\"clean trend\"}"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertThat(out).isInstanceOf(Allow.class);
        Allow a = (Allow) out;
        assertThat(a.decisionPath()).isEqualTo(DecisionPath.EXPLICIT_ALLOW);
        assertThat(a.confidence()).isEqualByComparingTo("0.78");

        double explicitAllow = registry.find(AiAgentMetrics.METER_SENTINEL_ALLOW)
                .tag("tenant_id", TENANT)
                .tag("level_swept", "PDH")
                .tag("decision_path", "explicit_allow")
                .counter()
                .count();
        assertThat(explicitAllow).isEqualTo(1.0d);
        verify(auditWriter, times(1)).record(any(AnthropicRequest.class), any(AnthropicResponse.Success.class), eq(""));
    }

    // ---------- Allow LOW_CONFIDENCE override ----------

    @Test
    void lowConfidenceVeto_demotedToAllow_overriddenPath_andAudits() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(success("{\"decision\":\"VETO\",\"confidence\":0.50,"
                        + "\"reason_code\":\"REGIME_MISALIGNED\",\"reason_text\":\"counter-trend\"}"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertThat(out).isInstanceOf(Allow.class);
        Allow a = (Allow) out;
        assertThat(a.decisionPath()).isEqualTo(DecisionPath.LOW_CONFIDENCE_VETO_OVERRIDDEN);

        double overridden = registry.find(AiAgentMetrics.METER_SENTINEL_ALLOW)
                .tag("tenant_id", TENANT)
                .tag("level_swept", "PDH")
                .tag("decision_path", "low_confidence_veto_overridden")
                .counter()
                .count();
        assertThat(overridden).isEqualTo(1.0d);
        verify(auditWriter, times(1)).record(any(AnthropicRequest.class), any(AnthropicResponse.Success.class), eq(""));
    }

    // ---------- Fallback CB_OPEN ----------

    @Test
    void cbOpen_returnsFallbackCbOpen_andAudits() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(new AnthropicResponse.TransportFailure(
                        "REQ_CB", Role.SENTINEL, MODEL, 5L, "circuit_breaker_open"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertFallbackReason(out, FallbackReason.CB_OPEN);
        assertFallbackMetric("cb_open");
        verify(auditWriter, times(1))
                .record(any(AnthropicRequest.class), any(AnthropicResponse.TransportFailure.class), eq(""));
    }

    // ---------- Fallback TIMEOUT ----------

    @Test
    void timeout_returnsFallbackTimeout_doesNotThrow_andAudits() {
        // Block the underlying call long enough to trip the wall-clock timeout.
        // Use a tight timeout so the test stays fast.
        Duration tightTimeout = Duration.ofMillis(50);
        CountDownLatch release = new CountDownLatch(1);
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false))).thenAnswer(inv -> {
            // Block; the orTimeout cancels the future, but the supplier
            // continues running on its executor thread. We release after the
            // test asserts so the executor can shut down cleanly.
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(
                    "{\"decision\":\"ALLOW\",\"confidence\":0.5," + "\"reason_code\":\"OTHER\",\"reason_text\":\"x\"}");
        });

        SentinelService service = service(/* enabled */ true, tightTimeout);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertFallbackReason(out, FallbackReason.TIMEOUT);
        assertFallbackMetric("timeout");
        // Audit row STILL written for the timeout (synthetic transport failure).
        verify(auditWriter, times(1))
                .record(any(AnthropicRequest.class), any(AnthropicResponse.TransportFailure.class), eq(""));
        release.countDown();
    }

    // ---------- Fallback COST_CAP ----------

    @Test
    void costCapBreached_returnsFallbackCostCap_andAudits() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(new AnthropicResponse.CostCapBreached(
                        "REQ_CC",
                        Role.SENTINEL,
                        MODEL,
                        2L,
                        new BigDecimal("0.50"),
                        new BigDecimal("0.49"),
                        new BigDecimal("0.02")));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertFallbackReason(out, FallbackReason.COST_CAP);
        assertFallbackMetric("cost_cap");
        verify(auditWriter, times(1))
                .record(any(AnthropicRequest.class), any(AnthropicResponse.CostCapBreached.class), eq(""));
    }

    // ---------- Fallback TRANSPORT ----------

    @Test
    void transportFailure_returnsFallbackTransport_andAudits() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(new AnthropicResponse.TransportFailure(
                        "REQ_T", Role.SENTINEL, MODEL, 30L, "ConnectException: refused"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertFallbackReason(out, FallbackReason.TRANSPORT);
        assertFallbackMetric("transport");
        verify(auditWriter, times(1))
                .record(any(AnthropicRequest.class), any(AnthropicResponse.TransportFailure.class), eq(""));
    }

    // ---------- Fallback PARSE ----------

    @Test
    void parseFailure_returnsFallbackParse_andAudits() {
        // Malformed JSON in the Success body — parser maps to Fallback(PARSE).
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false))).thenReturn(success("not json"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertFallbackReason(out, FallbackReason.PARSE);
        assertFallbackMetric("parse");
        verify(auditWriter, times(1)).record(any(AnthropicRequest.class), any(AnthropicResponse.Success.class), eq(""));
    }

    // ---------- Fallback RATE_LIMIT ----------

    @Test
    void rateLimited_returnsFallbackRateLimit_andAudits() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(new AnthropicResponse.RateLimited("REQ_RL", Role.SENTINEL, MODEL, 18L, "rate limited"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertFallbackReason(out, FallbackReason.RATE_LIMIT);
        assertFallbackMetric("rate_limit");
        verify(auditWriter, times(1))
                .record(any(AnthropicRequest.class), any(AnthropicResponse.RateLimited.class), eq(""));
    }

    // ---------- Audit-row + latency properties ----------

    @Test
    void submittedAnthropicRequest_carriesSentinelRoleAndModelAndRetryDisabled() {
        when(anthropicClient.submit(any(AnthropicRequest.class), anyBoolean()))
                .thenReturn(success("{\"decision\":\"ALLOW\",\"confidence\":0.7,"
                        + "\"reason_code\":\"STRUCTURE_MATCH\",\"reason_text\":\"x\"}"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        service.evaluate(longCallPdh());

        ArgumentCaptor<AnthropicRequest> aReqCap = ArgumentCaptor.forClass(AnthropicRequest.class);
        ArgumentCaptor<Boolean> retryCap = ArgumentCaptor.forClass(Boolean.class);
        verify(anthropicClient).submit(aReqCap.capture(), retryCap.capture());

        AnthropicRequest captured = aReqCap.getValue();
        assertThat(captured.role()).isEqualTo(Role.SENTINEL);
        assertThat(captured.model()).isEqualTo(MODEL);
        assertThat(captured.tenantId()).isEqualTo(TENANT);
        assertThat(captured.maxTokens()).isEqualTo(SentinelPromptBuilder.MAX_TOKENS);
        assertThat(captured.temperature()).isEqualTo(0.0d);
        // ADR-0007 §4 — Sentinel is single-attempt, retry MUST be false.
        assertThat(retryCap.getValue()).isFalse();
    }

    @Test
    void latencyRecorded_isMonotonicAndNonNegative() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(success("{\"decision\":\"ALLOW\",\"confidence\":0.7,"
                        + "\"reason_code\":\"STRUCTURE_MATCH\",\"reason_text\":\"x\"}"));

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        assertThat(out).isInstanceOf(Allow.class);
        // Latency comes from elapsedMs(startNs) — non-negative on every JVM.
        assertThat(out.latencyMs()).isGreaterThanOrEqualTo(0L);
        // Even with a stub mock the latency must stay well under the configured timeout.
        assertThat(out.latencyMs()).isLessThan(TEST_TIMEOUT.toMillis());
    }

    @Test
    void neverThrows_evenWhenAuditWriterThrows() {
        when(anthropicClient.submit(any(AnthropicRequest.class), eq(false)))
                .thenReturn(success("{\"decision\":\"ALLOW\",\"confidence\":0.7,"
                        + "\"reason_code\":\"STRUCTURE_MATCH\",\"reason_text\":\"x\"}"));
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down"))
                .when(auditWriter)
                .record(any(), any(), any());

        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        SentinelDecisionResponse out = service.evaluate(longCallPdh());

        // Audit failure must never propagate — the saga thread relies on the
        // orchestrator returning a decision.
        assertThat(out).isInstanceOf(Allow.class);
    }

    @Test
    void rejectsNullRequest() {
        SentinelService service = service(/* enabled */ true, TEST_TIMEOUT);
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> service.evaluate(null))
                .withMessageContaining("request");
    }

    // ---------- helpers ----------

    private SentinelService service(boolean enabled, Duration timeout) {
        return new SentinelService(
                PROMPT_BUILDER,
                PARSER,
                anthropicClient,
                connectionMonitor,
                auditWriter,
                metrics,
                FIXED_CLOCK,
                enabled,
                executor,
                timeout);
    }

    private void assertFallbackReason(SentinelDecisionResponse out, FallbackReason expected) {
        assertThat(out).isInstanceOf(Fallback.class);
        Fallback f = (Fallback) out;
        assertThat(f.reason()).isEqualTo(expected);
    }

    private void assertFallbackMetric(String reasonLabel) {
        double count = registry.find(AiAgentMetrics.METER_SENTINEL_FALLBACK)
                .tag("tenant_id", TENANT)
                .tag("reason", reasonLabel)
                .counter()
                .count();
        assertThat(count).isEqualTo(1.0d);
    }

    private static AnthropicResponse.Success success(String text) {
        return new AnthropicResponse.Success(
                "REQ_OK", Role.SENTINEL, MODEL, 42L, text, List.of(), 100, 50, 0, BigDecimal.ZERO);
    }

    /** Minimal SentinelDecisionRequest fixture (LONG_CALL / PDH) — same shape as the parser test. */
    private static SentinelDecisionRequest longCallPdh() {
        Instant t = Instant.parse("2026-05-04T15:00:00Z");
        return new SentinelDecisionRequest(
                TENANT,
                "TR_001",
                "SIG_001",
                Direction.LONG_CALL,
                LevelSwept.PDH,
                new IndicatorSnapshot(
                        new BigDecimal("500.00"),
                        new BigDecimal("498.50"),
                        new BigDecimal("495.00"),
                        new BigDecimal("1.20"),
                        new BigDecimal("65.50"),
                        "BULL",
                        List.of(new Bar(t.minusSeconds(120), new BigDecimal("499.50"), 12_000L))),
                List.of(),
                new BigDecimal("14.50"),
                t);
    }

    /** Reference equality on AnthropicMessage to keep imports tidy in IDEs that prune. */
    @SuppressWarnings("unused")
    private static AnthropicMessage referencedForImport() {
        return null;
    }
}
