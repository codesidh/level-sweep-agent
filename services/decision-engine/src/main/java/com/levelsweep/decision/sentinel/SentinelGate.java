package com.levelsweep.decision.sentinel;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import com.levelsweep.shared.domain.signal.SweptLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trade Saga step that calls the remote Pre-Trade Sentinel between RiskGate
 * and StrikeSelector (ADR-0007 §1). Owns translation from saga state into
 * a {@link SentinelDecisionRequest}, the decision-tree mapping a
 * {@link SentinelClientResult} into a saga {@link Outcome}, and the four
 * counters published in {@code architecture-spec.md} §4.4 / ADR-0007 §3.
 *
 * <h2>Feature flag</h2>
 *
 * <p>Default OFF ({@code levelsweep.sentinel.enabled=false}, ADR-0007 §7).
 * When OFF, {@link #evaluate} short-circuits with {@link Outcome.Skip} and
 * increments {@code decision.sentinel.skipped{reason="flag_off"}}. The
 * saga's state graph is byte-identical to the pre-Sentinel one — so
 * existing replay parity tests still pass.
 *
 * <h2>Saga compensation contract</h2>
 *
 * <p>SentinelGate is read-only from the saga's perspective: no state is
 * mutated before the call returns. There is therefore no compensation step
 * to execute on rollback — the gate's no-op {@code compensate()} is
 * documented here per the {@code saga-compensation} skill rule.
 *
 * <h2>Fail-OPEN posture</h2>
 *
 * <p>{@link SentinelClientResult.Allow} → {@link Outcome.Proceed} (with
 * the {@code decision_path} threaded into the counter dimension).
 * {@link SentinelClientResult.Veto} ({@code confidence ≥ 0.85}) →
 * {@link Outcome.VetoCompensate}. {@link SentinelClientResult.Fallback}
 * → {@link Outcome.Proceed} per ADR-0007 §3 (a Sentinel outage does NOT
 * silently halt entries; that's the deterministic Risk FSM's job).
 */
@ApplicationScoped
public class SentinelGate {

    private static final Logger LOG = LoggerFactory.getLogger(SentinelGate.class);

    public static final String COUNTER_SKIPPED = "decision.sentinel.skipped";
    public static final String COUNTER_ALLOW = "decision.sentinel.allow";
    public static final String COUNTER_VETO_APPLIED = "decision.sentinel.veto_applied";
    public static final String COUNTER_FALLBACK = "decision.sentinel.fallback";
    public static final String TIMER_DURATION = "decision.sentinel.gate.duration";

    static final String COMPENSATION_REASON = "sentinel_veto";

    private final SentinelClient client;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final BigDecimal vixClosePrevDefault;
    private final String regimeDefault;

    @Inject
    public SentinelGate(
            SentinelClient client,
            Clock clock,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "levelsweep.sentinel.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "levelsweep.sentinel.vix-close-prev-default", defaultValue = "0")
                    BigDecimal vixClosePrevDefault,
            @ConfigProperty(name = "levelsweep.sentinel.regime-default", defaultValue = "UNKNOWN")
                    String regimeDefault) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.enabled = enabled;
        this.vixClosePrevDefault = Objects.requireNonNull(vixClosePrevDefault, "vixClosePrevDefault");
        this.regimeDefault = Objects.requireNonNull(regimeDefault, "regimeDefault");
    }

    /**
     * Run the gate for a saga in flight. Synchronous, fail-OPEN. Never
     * throws — every transport / parse / validation failure becomes
     * {@link Outcome.Proceed} (with a fallback counter increment).
     *
     * @param tenantId saga tenant scope
     * @param tradeId saga correlation key
     * @param signalId Decision Engine signal evaluator key
     * @param bar bar that triggered the saga (for nowUtc anchor)
     * @param snapshot indicator snapshot at signal time
     * @param levels reference levels (sweep target context)
     * @param eval signal evaluation (for direction + level swept)
     */
    public Outcome evaluate(
            String tenantId,
            String tradeId,
            String signalId,
            Bar bar,
            IndicatorSnapshot snapshot,
            Levels levels,
            SignalEvaluation eval) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(signalId, "signalId");
        Objects.requireNonNull(bar, "bar");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(levels, "levels");
        Objects.requireNonNull(eval, "eval");

        if (!enabled) {
            counter(COUNTER_SKIPPED, Tags.of("tenant_id", tenantId, "reason", "flag_off"))
                    .increment();
            LOG.debug("sentinel skipped (flag off) tenantId={} tradeId={} signalId={}", tenantId, tradeId, signalId);
            return new Outcome.Skip("flag_off");
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        SentinelClientResult result;
        try {
            SentinelDecisionRequest request = buildRequest(tenantId, tradeId, signalId, bar, snapshot, levels, eval);
            result = client.evaluate(request);
        } catch (RuntimeException e) {
            // SentinelClient is contractually fail-OPEN; if a request build
            // step throws (defensive missing-data case), still proceed with a
            // fallback so the saga never silently halts on Sentinel internals.
            LOG.warn("sentinel gate request-build threw tenantId={} tradeId={}: {}", tenantId, tradeId, e.toString());
            sample.stop(meterRegistry.timer(TIMER_DURATION, Tags.of("outcome", "request_build_error")));
            counter(COUNTER_FALLBACK, Tags.of("tenant_id", tenantId, "reason", "request_build_error"))
                    .increment();
            return new Outcome.Proceed(SentinelClientResult.DecisionPath.FALLBACK_ALLOW);
        }

        Outcome outcome = mapOutcome(tenantId, tradeId, signalId, eval, result);
        sample.stop(meterRegistry.timer(TIMER_DURATION, Tags.of("outcome", outcome.timerOutcomeLabel())));
        return outcome;
    }

    private Outcome mapOutcome(
            String tenantId, String tradeId, String signalId, SignalEvaluation eval, SentinelClientResult result) {
        String levelSwept = sweptLevelLabel(eval);
        return switch (result) {
            case SentinelClientResult.Allow allow -> {
                String dpLabel = decisionPathLabel(allow.decisionPath());
                counter(
                                COUNTER_ALLOW,
                                Tags.of("tenant_id", tenantId, "level_swept", levelSwept, "decision_path", dpLabel))
                        .increment();
                LOG.info(
                        "sentinel allow tenantId={} tradeId={} signalId={} levelSwept={} decisionPath={} latencyMs={}",
                        tenantId,
                        tradeId,
                        signalId,
                        levelSwept,
                        dpLabel,
                        allow.latencyMs());
                yield new Outcome.Proceed(allow.decisionPath());
            }
            case SentinelClientResult.Veto veto -> {
                counter(COUNTER_VETO_APPLIED, Tags.of("tenant_id", tenantId, "level_swept", levelSwept))
                        .increment();
                LOG.info(
                        "sentinel veto applied tenantId={} tradeId={} signalId={} levelSwept={} confidence={} latencyMs={}",
                        tenantId,
                        tradeId,
                        signalId,
                        levelSwept,
                        veto.confidence(),
                        veto.latencyMs());
                yield new Outcome.VetoCompensate(veto.reasonCode(), veto.reasonText());
            }
            case SentinelClientResult.Fallback fallback -> {
                String reasonLabel = fallbackReasonLabel(fallback.reason());
                counter(COUNTER_FALLBACK, Tags.of("tenant_id", tenantId, "reason", reasonLabel))
                        .increment();
                LOG.info(
                        "sentinel fallback tenantId={} tradeId={} signalId={} reason={} latencyMs={}",
                        tenantId,
                        tradeId,
                        signalId,
                        reasonLabel,
                        fallback.latencyMs());
                yield new Outcome.Proceed(SentinelClientResult.DecisionPath.FALLBACK_ALLOW);
            }
        };
    }

    /**
     * Build the wire-format request from saga state. Most fields are direct
     * passthroughs; the indicator snapshot needs a defensive bridge — the
     * shared {@link IndicatorSnapshot} carries only EMA/ATR (rsi2 + regime
     * + recentBars are not yet wired in decision-engine), so the gate
     * threads sensible defaults that the Sentinel prompt absorbs without
     * tripping the LLM into reasoning over zero values.
     */
    SentinelDecisionRequest buildRequest(
            String tenantId,
            String tradeId,
            String signalId,
            Bar bar,
            IndicatorSnapshot snapshot,
            Levels levels,
            SignalEvaluation eval) {
        SentinelDecisionRequest.Direction direction = directionFromSignal(eval);
        SentinelDecisionRequest.LevelSwept levelSwept = levelFromSignal(eval);
        SentinelDecisionRequest.IndicatorSnapshot wireSnapshot = new SentinelDecisionRequest.IndicatorSnapshot(
                nullToZero(snapshot.ema13()),
                nullToZero(snapshot.ema48()),
                nullToZero(snapshot.ema200()),
                nullToZero(snapshot.atr14()),
                BigDecimal.ZERO,
                regimeDefault,
                List.of(new SentinelDecisionRequest.Bar(bar.closeTime(), bar.close(), bar.volume())));
        Instant nowUtc = bar.closeTime() != null ? bar.closeTime() : clock.instant();
        return new SentinelDecisionRequest(
                tenantId,
                tradeId,
                signalId,
                direction,
                levelSwept,
                wireSnapshot,
                List.of(),
                vixClosePrevDefault,
                nowUtc);
    }

    private static SentinelDecisionRequest.Direction directionFromSignal(SignalEvaluation eval) {
        OptionSide side =
                eval.optionSide().orElseThrow(() -> new IllegalArgumentException("signal missing optionSide"));
        return side == OptionSide.CALL
                ? SentinelDecisionRequest.Direction.LONG_CALL
                : SentinelDecisionRequest.Direction.LONG_PUT;
    }

    private static SentinelDecisionRequest.LevelSwept levelFromSignal(SignalEvaluation eval) {
        SweptLevel lvl = eval.level().orElseThrow(() -> new IllegalArgumentException("signal missing sweptLevel"));
        return switch (lvl) {
            case PDH -> SentinelDecisionRequest.LevelSwept.PDH;
            case PDL -> SentinelDecisionRequest.LevelSwept.PDL;
            case PMH -> SentinelDecisionRequest.LevelSwept.PMH;
            case PML -> SentinelDecisionRequest.LevelSwept.PML;
        };
    }

    private static String sweptLevelLabel(SignalEvaluation eval) {
        return eval.level().map(SweptLevel::name).orElse("UNKNOWN");
    }

    private static String decisionPathLabel(SentinelClientResult.DecisionPath path) {
        return switch (path) {
            case EXPLICIT_ALLOW -> "explicit_allow";
            case LOW_CONFIDENCE_VETO_OVERRIDDEN -> "low_confidence_veto_overridden";
            case FALLBACK_ALLOW -> "fallback_allow";
        };
    }

    private static String fallbackReasonLabel(SentinelClientResult.FallbackReason reason) {
        return switch (reason) {
            case TRANSPORT -> "transport";
            case TIMEOUT -> "timeout";
            case PARSE -> "parse";
            case RATE_LIMIT -> "rate_limit";
            case COST_CAP -> "cost_cap";
            case CB_OPEN -> "cb_open";
        };
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private Counter counter(String name, Tags tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    /** Test seam: feature flag state. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Saga-facing outcome for the SentinelGate step. Three terminal states;
     * the saga pattern-matches and either continues to StrikeSelector or
     * emits a TradeSkipped with stage {@code SENTINEL_VETO}.
     */
    public sealed interface Outcome {

        /** Logical timer-tag label — kept short for Prometheus cardinality. */
        String timerOutcomeLabel();

        /**
         * Saga proceeds to StrikeSelector. Carries the decision path so the
         * saga's audit trail records why we proceeded (explicit allow vs
         * fail-OPEN).
         */
        record Proceed(SentinelClientResult.DecisionPath decisionPath) implements Outcome {
            public Proceed {
                Objects.requireNonNull(decisionPath, "decisionPath");
            }

            @Override
            public String timerOutcomeLabel() {
                return decisionPath == SentinelClientResult.DecisionPath.FALLBACK_ALLOW ? "fallback" : "allow";
            }
        }

        /** Saga compensates — no order placed. */
        record VetoCompensate(SentinelClientResult.ReasonCode reasonCode, String reasonText) implements Outcome {
            public VetoCompensate {
                Objects.requireNonNull(reasonCode, "reasonCode");
                Objects.requireNonNull(reasonText, "reasonText");
            }

            @Override
            public String timerOutcomeLabel() {
                return "veto";
            }
        }

        /** Feature-flag short-circuit — no Sentinel call made. */
        record Skip(String reason) implements Outcome {
            public Skip {
                Objects.requireNonNull(reason, "reason");
            }

            @Override
            public String timerOutcomeLabel() {
                return "skip";
            }
        }
    }

    /**
     * No-op compensation — SentinelGate is read-only on saga state. Documented
     * per the {@code saga-compensation} skill rule (every saga step has an
     * explicit compensation contract, even if it's a no-op).
     */
    public void compensate() {
        // Nothing to undo — no state was mutated.
    }
}
