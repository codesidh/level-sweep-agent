package com.levelsweep.execution.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.execution.replay.ExecutionScenarios.ExecutionScenario;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeProposed;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Replay-parity harness for the Phase 3 execution-service pipeline. Per the
 * architecture-spec §21.1 row 3 acceptance, "5+ paper sessions match replay
 * within ±2%" — this PR ships the harness shell that future soak runs feed
 * real recordings into.
 *
 * <h3>Three responsibilities</h3>
 *
 * <ol>
 *   <li><strong>Determinism contract</strong> — two runs of the same fixture
 *       produce byte-identical {@link TradeProposed} capture sequences. This
 *       is the core promise of the {@code replay-parity} skill applied to the
 *       execution side.
 *   <li><strong>Hand-labeled outcomes</strong> — for each of the 3 named
 *       scenarios we know what should happen (a happy-path long, a stop-hit,
 *       and an order rejection). Today only the {@link TradeProposed}-level
 *       assertion is live; the order/fill/stop/EOD assertions become live as
 *       S2/S3/S5/S6 land on follow-up PRs.
 *   <li><strong>Reproducibility from fixture builder</strong> — different
 *       scenarios produce different captures (sanity: the builder isn't
 *       accidentally constant); same scenario produces identical captures.
 * </ol>
 *
 * <p>Per the {@code replay-parity} skill, no live external calls (Mongo /
 * MS SQL / Kafka / Anthropic / Alpaca) and no {@code Instant.now()} — all
 * timestamps come from {@link ExecutionScenarios} literals.
 */
class ExecutionReplayHarnessTest {

    /** Method source for the parameterized determinism test. */
    static List<ExecutionScenario> allScenarios() {
        return ExecutionScenarios.all();
    }

    @ParameterizedTest(name = "deterministic across runs: {0}")
    @MethodSource("allScenarios")
    void deterministicAcrossRuns(ExecutionScenario scenario) {
        ExecutionReplayPipeline first = new ExecutionReplayPipeline();
        ExecutionReplayPipeline second = new ExecutionReplayPipeline();

        first.onScenario(scenario);
        second.onScenario(scenario);

        // Records use structural equality; this is the byte-equality contract.
        assertThat(first.capturedTrades())
                .as("captured trades must be byte-equal across runs for %s", scenario.name())
                .isEqualTo(second.capturedTrades());
        assertThat(first.capturedOrderResponses())
                .as("captured order responses must be byte-equal across runs for %s", scenario.name())
                .isEqualTo(second.capturedOrderResponses());
        assertThat(first.capturedFills()).isEqualTo(second.capturedFills());
        assertThat(first.capturedStopBreaches()).isEqualTo(second.capturedStopBreaches());
        assertThat(first.capturedEodTriggers()).isEqualTo(second.capturedEodTriggers());
    }

    @Test
    void happyPathLongCapturesProposedAndFill() {
        ExecutionScenario scenario = ExecutionScenarios.happyPathLong();
        ExecutionReplayPipeline pipeline = new ExecutionReplayPipeline();
        pipeline.onScenario(scenario);

        // Live today: TradeProposed propagated to the router.
        assertThat(pipeline.capturedTrades())
                .as("happy path produces exactly 1 captured TradeProposed")
                .containsExactly(scenario.input());

        // Live today via the in-test event recording: order accepted + 1 fill.
        // (Becomes a real production-router assertion once S2 + S3 land.)
        assertThat(pipeline.capturedOrderResponses())
                .as("happy path observes 1 accepted order response")
                .hasSize(1)
                .first()
                .matches(SimulatedEvent.OrderSubmissionResponse::isAccepted, "isAccepted");
        assertThat(pipeline.capturedFills())
                .as("happy path observes 1 fill frame")
                .hasSize(1);
        assertThat(pipeline.capturedEodTriggers())
                .as("happy path observes 1 EOD trigger (profitable flatten exit)")
                .hasSize(1);
        assertThat(pipeline.capturedStopBreaches())
                .as("happy path has no stop breach")
                .isEmpty();
    }

    @Test
    void orderRejectedCapturesProposedButNoFill() {
        ExecutionScenario scenario = ExecutionScenarios.orderRejected();
        ExecutionReplayPipeline pipeline = new ExecutionReplayPipeline();
        pipeline.onScenario(scenario);

        // The proposed event was forwarded — that's what S1 wires up today.
        assertThat(pipeline.capturedTrades())
                .as("rejected scenario still captures the proposed event")
                .containsExactly(scenario.input());

        // The synthetic 422 response is recorded; no fill follows.
        assertThat(pipeline.capturedOrderResponses())
                .as("rejected scenario observes 1 rejected response")
                .hasSize(1)
                .first()
                .matches(r -> !r.isAccepted(), "rejected")
                .extracting(SimulatedEvent.OrderSubmissionResponse::rejectStatus)
                .isEqualTo(422);
        assertThat(pipeline.capturedFills())
                .as("rejected scenario produces 0 fill frames")
                .isEmpty();
        assertThat(pipeline.capturedStopBreaches()).isEmpty();
        assertThat(pipeline.capturedEodTriggers()).isEmpty();
    }

    @Test
    void stopHitLongCapturesBreachAndNoEod() {
        ExecutionScenario scenario = ExecutionScenarios.stopHitLong();
        ExecutionReplayPipeline pipeline = new ExecutionReplayPipeline();
        pipeline.onScenario(scenario);

        assertThat(pipeline.capturedTrades()).containsExactly(scenario.input());
        assertThat(pipeline.capturedOrderResponses()).hasSize(1);
        assertThat(pipeline.capturedFills()).hasSize(1);
        assertThat(pipeline.capturedStopBreaches())
                .as("stop-hit scenario observes 1 stop breach event")
                .hasSize(1);
        assertThat(pipeline.capturedEodTriggers())
                .as("stop-hit exits before EOD — no EOD trigger fires")
                .isEmpty();
    }

    @Test
    void scenariosAreDistinct() {
        // Sanity: catch a fixture-builder regression where two scenarios were
        // accidentally constructed with identical inputs under different names.
        List<ExecutionScenario> scenarios = ExecutionScenarios.all();
        assertThat(scenarios).as("expected exactly 3 scenarios").hasSize(3);

        for (int i = 0; i < scenarios.size(); i++) {
            for (int j = i + 1; j < scenarios.size(); j++) {
                ExecutionScenario a = scenarios.get(i);
                ExecutionScenario b = scenarios.get(j);
                assertThat(a.name()).as("scenarios share a name").isNotEqualTo(b.name());
                assertThat(a.input())
                        .as("%s and %s share an identical TradeProposed input", a.name(), b.name())
                        .isNotEqualTo(b.input());
                // Stronger: their event timelines also differ because each
                // scenario probes a different execution path. We compare the
                // raw event lists, not full pipeline output, because pipeline
                // dispatch is a deterministic function of input.events().
                assertThat(a.events())
                        .as("%s and %s produce identical event timelines", a.name(), b.name())
                        .isNotEqualTo(b.events());
            }
        }
    }

    @Test
    void tradeProposedHasStableEqualityForReplay() {
        // Records use structural equality; verifying it explicitly here pins
        // the contract — if anyone refactors TradeProposed to a class without
        // overriding equals(), the byte-equal assert in deterministicAcrossRuns
        // would silently regress.
        TradeProposed a = ExecutionScenarios.happyPathLong().input();
        TradeProposed b = new TradeProposed(
                a.tenantId(),
                a.tradeId(),
                a.sessionDate(),
                a.proposedAt(),
                a.underlying(),
                a.side(),
                a.contractSymbol(),
                a.entryNbboBid(),
                a.entryNbboAsk(),
                a.entryMid(),
                a.impliedVolatility(),
                a.delta(),
                a.correlationId(),
                a.signalReasons());

        assertThat(a)
                .as("two TradeProposed records with identical fields must be .equals()")
                .isEqualTo(b);
        assertThat(a.hashCode())
                .as("structural equality implies hashCode parity")
                .isEqualTo(b.hashCode());

        // Sanity: the same record is NOT equal to one with a different field —
        // catches accidental record-canonicalization that would equate distinct
        // trades and break the determinism contract.
        TradeProposed differentTrade = new TradeProposed(
                a.tenantId(),
                "trade-different",
                a.sessionDate(),
                a.proposedAt(),
                a.underlying(),
                OptionSide.PUT,
                a.contractSymbol(),
                a.entryNbboBid(),
                a.entryNbboAsk(),
                a.entryMid(),
                Optional.<BigDecimal>empty(),
                Optional.<BigDecimal>empty(),
                "corr-different",
                List.of("different_reason"));
        assertThat(a).isNotEqualTo(differentTrade);
    }
}
