package com.levelsweep.decision.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.signal.SignalAction;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import com.levelsweep.shared.domain.signal.SweptLevel;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link SentinelGate}. The {@link SentinelClient} is mocked
 * so no network IO occurs; tests verify the gate's outcome mapping, counter
 * emission, and fail-OPEN posture per ADR-0007 §3.
 */
class SentinelGateTest {

    private static final String TENANT = "OWNER";
    private static final String TRADE = "trade-1";
    private static final String SIGNAL = "sig-1";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final Instant CLOSE = Instant.parse("2026-04-30T14:00:00Z");
    private static final LocalDate SESSION = CLOSE.atZone(ET).toLocalDate();

    private SentinelClient client;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(SentinelClient.class);
        registry = new SimpleMeterRegistry();
    }

    // ---- Flag OFF ----------------------------------------------------------

    @Test
    void flagOffSkipsCallAndIncrementsSkippedCounter() {
        SentinelGate gate = newGate(/* enabled */ false);

        SentinelGate.Outcome outcome =
                gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        assertThat(outcome).isInstanceOf(SentinelGate.Outcome.Skip.class);
        assertThat(((SentinelGate.Outcome.Skip) outcome).reason()).isEqualTo("flag_off");
        verify(client, never()).evaluate(any());
        assertThat(registry.counter(SentinelGate.COUNTER_SKIPPED, Tags.of("tenant_id", TENANT, "reason", "flag_off"))
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void flagOffMakesGateANoOpForReplayParity() {
        // Replay parity contract (ADR-0007 §5): with the flag OFF, the gate
        // produces zero observable side effects beyond the skip counter.
        // No SentinelClient interaction → byte-identical to the pre-Sentinel
        // saga state graph.
        SentinelGate gate = newGate(false);

        gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());
        gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        verify(client, never()).evaluate(any());
        assertThat(registry.counter(SentinelGate.COUNTER_SKIPPED, Tags.of("tenant_id", TENANT, "reason", "flag_off"))
                        .count())
                .isEqualTo(2.0);
    }

    // ---- Flag ON + Allow ---------------------------------------------------

    @Test
    void flagOnAllowProceedsAndIncrementsAllowCounter() {
        when(client.evaluate(any()))
                .thenReturn(new SentinelClientResult.Allow(
                        "req_a",
                        new BigDecimal("0.42"),
                        SentinelClientResult.ReasonCode.STRUCTURE_MATCH,
                        "ok",
                        120L,
                        SentinelClientResult.DecisionPath.EXPLICIT_ALLOW));
        SentinelGate gate = newGate(true);

        SentinelGate.Outcome outcome =
                gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        assertThat(outcome).isInstanceOf(SentinelGate.Outcome.Proceed.class);
        SentinelGate.Outcome.Proceed proceed = (SentinelGate.Outcome.Proceed) outcome;
        assertThat(proceed.decisionPath()).isEqualTo(SentinelClientResult.DecisionPath.EXPLICIT_ALLOW);
        assertThat(registry.counter(
                                SentinelGate.COUNTER_ALLOW,
                                Tags.of("tenant_id", TENANT, "level_swept", "PDL", "decision_path", "explicit_allow"))
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void flagOnLowConfidenceVetoOverriddenIsAllowWithSpecialDecisionPath() {
        // The parser layer (in ai-agent-service) demotes < 0.85 vetoes to
        // Allow with decision_path=LOW_CONFIDENCE_VETO_OVERRIDDEN. The gate
        // sees only Allow at this point — confirm the dimension propagates
        // to the counter.
        when(client.evaluate(any()))
                .thenReturn(new SentinelClientResult.Allow(
                        "req_lcvo",
                        new BigDecimal("0.30"),
                        SentinelClientResult.ReasonCode.STRUCTURE_DIVERGENCE,
                        "weak veto",
                        120L,
                        SentinelClientResult.DecisionPath.LOW_CONFIDENCE_VETO_OVERRIDDEN));
        SentinelGate gate = newGate(true);

        SentinelGate.Outcome outcome =
                gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        assertThat(outcome).isInstanceOf(SentinelGate.Outcome.Proceed.class);
        assertThat(registry.counter(
                                SentinelGate.COUNTER_ALLOW,
                                Tags.of(
                                        "tenant_id",
                                        TENANT,
                                        "level_swept",
                                        "PDL",
                                        "decision_path",
                                        "low_confidence_veto_overridden"))
                        .count())
                .isEqualTo(1.0);
    }

    // ---- Flag ON + Veto ----------------------------------------------------

    @Test
    void flagOnVetoCompensatesAndIncrementsVetoAppliedCounter() {
        when(client.evaluate(any()))
                .thenReturn(new SentinelClientResult.Veto(
                        "req_v",
                        new BigDecimal("0.92"),
                        SentinelClientResult.ReasonCode.STRUCTURE_DIVERGENCE,
                        "regime fights signal",
                        145L));
        SentinelGate gate = newGate(true);

        SentinelGate.Outcome outcome =
                gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        assertThat(outcome).isInstanceOf(SentinelGate.Outcome.VetoCompensate.class);
        SentinelGate.Outcome.VetoCompensate v = (SentinelGate.Outcome.VetoCompensate) outcome;
        assertThat(v.reasonCode()).isEqualTo(SentinelClientResult.ReasonCode.STRUCTURE_DIVERGENCE);
        assertThat(v.reasonText()).isEqualTo("regime fights signal");
        assertThat(registry.counter(
                                SentinelGate.COUNTER_VETO_APPLIED, Tags.of("tenant_id", TENANT, "level_swept", "PDL"))
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void flagOnVetoAtThresholdIsHonored() {
        // ADR-0007 §2: confidence 0.85 is the inclusive threshold. The
        // SentinelClientResult.Veto compact constructor enforces ≥ 0.85
        // (a lower-confidence Veto is unrepresentable here — the parser
        // demotes it to Allow upstream). Just confirm that 0.85 reaches
        // the saga as a VetoCompensate, not a Proceed.
        when(client.evaluate(any()))
                .thenReturn(new SentinelClientResult.Veto(
                        "req_v_threshold",
                        new BigDecimal("0.85"),
                        SentinelClientResult.ReasonCode.RECENT_LOSSES,
                        "two losses",
                        130L));
        SentinelGate gate = newGate(true);

        SentinelGate.Outcome outcome =
                gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        assertThat(outcome).isInstanceOf(SentinelGate.Outcome.VetoCompensate.class);
    }

    // ---- Flag ON + Fallback ------------------------------------------------

    @Test
    void flagOnFallbackProceedsAndIncrementsFallbackCounter() {
        when(client.evaluate(any()))
                .thenReturn(new SentinelClientResult.Fallback("", SentinelClientResult.FallbackReason.TIMEOUT, 750L));
        SentinelGate gate = newGate(true);

        SentinelGate.Outcome outcome =
                gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        // Fail-OPEN — the saga proceeds, NOT compensates.
        assertThat(outcome).isInstanceOf(SentinelGate.Outcome.Proceed.class);
        SentinelGate.Outcome.Proceed proceed = (SentinelGate.Outcome.Proceed) outcome;
        assertThat(proceed.decisionPath()).isEqualTo(SentinelClientResult.DecisionPath.FALLBACK_ALLOW);
        assertThat(registry.counter(SentinelGate.COUNTER_FALLBACK, Tags.of("tenant_id", TENANT, "reason", "timeout"))
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void flagOnFallbackTransportProceedsToo() {
        when(client.evaluate(any()))
                .thenReturn(new SentinelClientResult.Fallback("", SentinelClientResult.FallbackReason.TRANSPORT, 250L));
        SentinelGate gate = newGate(true);

        SentinelGate.Outcome outcome =
                gate.evaluate(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        assertThat(outcome).isInstanceOf(SentinelGate.Outcome.Proceed.class);
        assertThat(registry.counter(SentinelGate.COUNTER_FALLBACK, Tags.of("tenant_id", TENANT, "reason", "transport"))
                        .count())
                .isEqualTo(1.0);
    }

    // ---- Request build determinism -----------------------------------------

    @Test
    void buildRequestThreadsTenantTradeSignalAndLevelThrough() {
        SentinelGate gate = newGate(true);

        SentinelDecisionRequest req =
                gate.buildRequest(TENANT, TRADE, SIGNAL, twoMinBar(), bullishSnapshot(), levels(), callSignal());

        assertThat(req.tenantId()).isEqualTo(TENANT);
        assertThat(req.tradeId()).isEqualTo(TRADE);
        assertThat(req.signalId()).isEqualTo(SIGNAL);
        assertThat(req.direction()).isEqualTo(SentinelDecisionRequest.Direction.LONG_CALL);
        assertThat(req.levelSwept()).isEqualTo(SentinelDecisionRequest.LevelSwept.PDL);
        assertThat(req.indicatorSnapshot().regime()).isEqualTo("UNKNOWN");
        // Indicator passthrough — saga indicator carries EMA13/48/200/ATR; rsi2
        // is a defensive zero default per the gate config.
        assertThat(req.indicatorSnapshot().ema13()).isEqualByComparingTo("595.00");
        assertThat(req.indicatorSnapshot().rsi2()).isEqualByComparingTo("0");
    }

    // ---- Compensation ------------------------------------------------------

    @Test
    void compensateIsNoOp() {
        // SentinelGate is read-only on saga state. The compensate() contract
        // is a documented no-op per the saga-compensation skill rule.
        SentinelGate gate = newGate(true);
        gate.compensate(); // no exception, no side effect.
    }

    // ---- Helpers -----------------------------------------------------------

    private SentinelGate newGate(boolean enabled) {
        return new SentinelGate(
                client, Clock.fixed(CLOSE, ZoneOffset.UTC), registry, enabled, BigDecimal.ZERO, "UNKNOWN");
    }

    private static Bar twoMinBar() {
        Instant open = CLOSE.minus(Timeframe.TWO_MIN.duration());
        return new Bar(
                "SPY",
                Timeframe.TWO_MIN,
                open,
                CLOSE,
                new BigDecimal("590.10"),
                new BigDecimal("590.30"),
                new BigDecimal("589.50"),
                new BigDecimal("590.20"),
                1_000L,
                10L);
    }

    private static IndicatorSnapshot bullishSnapshot() {
        return new IndicatorSnapshot(
                "SPY",
                CLOSE,
                new BigDecimal("595.00"),
                new BigDecimal("594.00"),
                new BigDecimal("593.00"),
                new BigDecimal("1.00"));
    }

    private static Levels levels() {
        return new Levels(
                TENANT,
                "SPY",
                SESSION,
                new BigDecimal("600.00"),
                new BigDecimal("590.00"),
                new BigDecimal("598.00"),
                new BigDecimal("592.00"));
    }

    private static SignalEvaluation callSignal() {
        return SignalEvaluation.enter(
                TENANT,
                "SPY",
                CLOSE,
                SignalAction.ENTER_LONG,
                SweptLevel.PDL,
                OptionSide.CALL,
                new BigDecimal("590.00"),
                List.of("sweep:PDL", "ema_stack:LONG_OK"));
    }
}
