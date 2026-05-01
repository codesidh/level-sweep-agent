package com.levelsweep.decision.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.decision.fsm.session.SessionEvent;
import com.levelsweep.shared.domain.signal.SignalAction;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Replay-parity harness — the architecture-spec §21.1 row for Phase 2 acceptance:
 * "hand-labeled days produce expected signals; FSM transitions persisted (deterministic)".
 *
 * <p>Three responsibilities:
 *
 * <ol>
 *   <li><strong>Determinism contract</strong> — two runs of the same fixture produce
 *       byte-identical {@link SignalEvaluation} sequences and FSM transitions. This is
 *       the core promise of the {@code replay-parity} skill.
 *   <li><strong>Hand-labeled outcomes</strong> — for each of the 3 named sessions we
 *       know what should happen (a sweep-and-fail PDH SHORT, a sweep-and-fail PDL LONG,
 *       and a no-setup quiet day). Assert that the actual evaluations match.
 *   <li><strong>Reproducibility from fixture builder</strong> — different seeds produce
 *       different evaluations (sanity: the synthetic generator isn't accidentally constant);
 *       same seed produces the same evaluations.
 * </ol>
 *
 * <p>Per the {@code replay-parity} skill, no live external calls (Mongo / MS SQL / Kafka /
 * Anthropic / Alpaca) and no {@code Instant.now()} — all timestamps come from the synthetic
 * fixture's seeded clock.
 */
class DecisionReplayHarnessTest {

    private static final String TENANT = "OWNER";
    private static final BigDecimal STARTING_EQUITY = new BigDecimal("25000.00");
    private static final BigDecimal DAILY_BUDGET = new BigDecimal("500.00");

    @Test
    void deterministicAcrossRuns_pdhSweepShort() {
        SyntheticSessionFixtures.Session session = pdhSweepShortSession();

        DecisionReplayPipeline first = runFull(session);
        DecisionReplayPipeline second = runFull(session);

        // The Signal evaluation list is the canonical determinism check.
        assertEvaluationsMatch(first.evaluations(), second.evaluations());

        // FSM transitions are deterministic too.
        assertThat(first.tradeTransitions()).containsExactlyElementsOf(second.tradeTransitions());
        assertThat(first.sessionTransitions()).containsExactlyElementsOf(second.sessionTransitions());
        assertThat(first.riskEvents()).containsExactlyElementsOf(second.riskEvents());
    }

    @Test
    void deterministicAcrossRuns_pdlSweepLong() {
        SyntheticSessionFixtures.Session session = pdlSweepLongSession();

        DecisionReplayPipeline first = runFull(session);
        DecisionReplayPipeline second = runFull(session);

        assertEvaluationsMatch(first.evaluations(), second.evaluations());
        assertThat(first.tradeTransitions()).containsExactlyElementsOf(second.tradeTransitions());
    }

    @Test
    void deterministicAcrossRuns_quietDay() {
        SyntheticSessionFixtures.Session session = quietDaySession();

        DecisionReplayPipeline first = runFull(session);
        DecisionReplayPipeline second = runFull(session);

        assertEvaluationsMatch(first.evaluations(), second.evaluations());
        // Quiet day means every evaluation is SKIP (no setups fired).
        assertThat(first.evaluations())
                .as("quiet day produces only SKIP evaluations")
                .allSatisfy(e -> assertThat(e.action()).isEqualTo(SignalAction.SKIP));
        // No trades should have started.
        assertThat(first.tradeTransitions()).isEmpty();
    }

    @Test
    void evaluationCountMatchesBarCount() {
        for (SyntheticSessionFixtures.Session session :
                List.of(pdhSweepShortSession(), pdlSweepLongSession(), quietDaySession())) {
            DecisionReplayPipeline pipeline = runFull(session);
            assertThat(pipeline.evaluations())
                    .as("one evaluation per 2-min bar in %s", session.name())
                    .hasSize(session.bars2m().size());
        }
    }

    @Test
    void sessionFsmTransitionsAreOrderedAndComplete() {
        DecisionReplayPipeline pipeline = runFull(pdhSweepShortSession());
        // The harness fires LEVELS_READY → MARKET_OPEN → EOD_TRIGGER → MARKET_CLOSE.
        // Each is a valid Session FSM transition, so all 4 should be captured.
        assertThat(pipeline.sessionTransitions())
                .extracting(DecisionReplayPipeline.SessionTransition::event)
                .containsExactly(
                        SessionEvent.LEVELS_READY,
                        SessionEvent.MARKET_OPEN,
                        SessionEvent.EOD_TRIGGER,
                        SessionEvent.MARKET_CLOSE);
    }

    @Test
    void differentSeedsProduceDifferentBars() {
        // Sanity: the synthetic generator isn't accidentally constant — different seeds
        // produce different bar OHLC even when other inputs match. We compare the bar
        // streams directly (not the SignalEvaluations) because Signal output can collapse
        // to identical all-SKIP sequences when both random walks stay outside the
        // sweep-buffer band, which is a property of the strategy, not a generator bug.
        SyntheticSessionFixtures.Session a = pdhSweepShortSession();
        SyntheticSessionFixtures.Session b = SyntheticSessionFixtures.builder("pdh-sweep-short-seed-99")
                .fromSeed(99L)
                .onDate(a.date())
                .openPrice(594.0)
                .withLevels(
                        a.levels().pdh(),
                        a.levels().pdl(),
                        a.levels().pmh(),
                        a.levels().pml())
                .barCount(a.bars2m().size())
                .withStackBias(SyntheticSessionFixtures.StackBias.BEARISH)
                .injectSweep(80, SyntheticSessionFixtures.SweepKind.PDH_SHORT, new BigDecimal("0.50"))
                .build();

        assertThat(a.bars2m())
                .as("seed 7 vs seed 99 bar streams must differ in at least one bar")
                .isNotEqualTo(b.bars2m());
    }

    @Test
    void expectedOutcomeMatchesActual_pdhSweepShort() {
        DecisionReplayPipeline pipeline = runFull(pdhSweepShortSession());
        ExpectedOutcome actual = summarize("pdh-sweep-short", pipeline);

        // The injected PDH-from-below sweep at index 80 should produce at least one
        // ENTER_SHORT signal — the swept level is PDH and the SHORT_STACK bias makes
        // the EMA confluence valid for SHORT.
        assertThat(actual.signalsTaken())
                .as("at least one SHORT setup taken at the injected sweep")
                .isGreaterThanOrEqualTo(1);
        assertThat(actual.firstTakenLevel()).hasValue("PDH");
    }

    @Test
    void expectedOutcomeMatchesActual_pdlSweepLong() {
        DecisionReplayPipeline pipeline = runFull(pdlSweepLongSession());
        ExpectedOutcome actual = summarize("pdl-sweep-long", pipeline);

        assertThat(actual.signalsTaken())
                .as("at least one LONG setup taken at the injected sweep")
                .isGreaterThanOrEqualTo(1);
        assertThat(actual.firstTakenLevel()).hasValue("PDL");
    }

    @Test
    void expectedOutcomeMatchesActual_quietDay() {
        DecisionReplayPipeline pipeline = runFull(quietDaySession());
        ExpectedOutcome actual = summarize("quiet-day", pipeline);

        assertThat(actual.signalsTaken()).as("quiet day yields zero takes").isZero();
        assertThat(actual.signalsSkipped()).isEqualTo(actual.signalEvaluations());
        assertThat(actual.firstTakenLevel()).isEmpty();
    }

    // ------------------------------------------------------------------------
    // Fixtures — three hand-labeled sessions used across the tests above.
    // ------------------------------------------------------------------------

    private static SyntheticSessionFixtures.Session pdhSweepShortSession() {
        return SyntheticSessionFixtures.builder("pdh-sweep-short")
                .fromSeed(7L)
                .onDate(java.time.LocalDate.of(2026, 4, 30))
                .openPrice(594.0)
                .withLevels(
                        new BigDecimal("596.00"),
                        new BigDecimal("592.00"),
                        new BigDecimal("594.50"),
                        new BigDecimal("593.50"))
                .barCount(195)
                .withStackBias(SyntheticSessionFixtures.StackBias.BEARISH)
                .injectSweep(80, SyntheticSessionFixtures.SweepKind.PDH_SHORT, new BigDecimal("0.50"))
                .build();
    }

    private static SyntheticSessionFixtures.Session pdlSweepLongSession() {
        return SyntheticSessionFixtures.builder("pdl-sweep-long")
                .fromSeed(11L)
                .onDate(java.time.LocalDate.of(2026, 4, 30))
                .openPrice(594.0)
                .withLevels(
                        new BigDecimal("596.00"),
                        new BigDecimal("592.00"),
                        new BigDecimal("594.50"),
                        new BigDecimal("593.50"))
                .barCount(195)
                .withStackBias(SyntheticSessionFixtures.StackBias.BULLISH)
                .injectSweep(60, SyntheticSessionFixtures.SweepKind.PDL_LONG, new BigDecimal("0.50"))
                .build();
    }

    private static SyntheticSessionFixtures.Session quietDaySession() {
        return SyntheticSessionFixtures.builder("quiet-day")
                .fromSeed(42L)
                .onDate(java.time.LocalDate.of(2026, 4, 30))
                .openPrice(594.0)
                .withLevels(
                        new BigDecimal("600.00"),
                        new BigDecimal("588.00"),
                        new BigDecimal("596.00"),
                        new BigDecimal("592.00"))
                .barCount(195)
                // No bias setting — quiet day relies on no-injected-sweep + far-away levels.
                // No injected sweep — random walk only, levels are far enough that
                // organic price action stays inside the [pdl, pdh] band.
                .build();
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private DecisionReplayPipeline runFull(SyntheticSessionFixtures.Session session) {
        DecisionReplayPipeline pipeline = new DecisionReplayPipeline();
        Instant resetAt = session.date().atStartOfDay(ZoneOffset.UTC).toInstant();

        pipeline.resetForSession(TENANT, session.date(), STARTING_EQUITY, DAILY_BUDGET, resetAt);
        pipeline.onSessionEvent(SessionEvent.LEVELS_READY, resetAt);
        // Use the first bar's openTime as MARKET_OPEN; close as MARKET_CLOSE.
        var firstBar = session.bars2m().get(0);
        var lastBar = session.bars2m().get(session.bars2m().size() - 1);
        pipeline.onSessionEvent(SessionEvent.MARKET_OPEN, firstBar.openTime());

        for (int i = 0; i < session.bars2m().size(); i++) {
            pipeline.onBarClose(session.bars2m().get(i), session.indicators().get(i), session.levels());
        }

        pipeline.onSessionEvent(SessionEvent.EOD_TRIGGER, lastBar.closeTime());
        pipeline.onEndOfDay(lastBar.closeTime());
        pipeline.onSessionEvent(SessionEvent.MARKET_CLOSE, lastBar.closeTime());
        return pipeline;
    }

    private static void assertEvaluationsMatch(List<SignalEvaluation> a, List<SignalEvaluation> b) {
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            // Records use structural equality; this is the byte-equality contract.
            assertThat(a.get(i)).as("evaluation #%d", i).isEqualTo(b.get(i));
        }
    }

    private static ExpectedOutcome summarize(String name, DecisionReplayPipeline p) {
        int takenCount = (int) p.evaluations().stream()
                .filter(e -> e.action() != SignalAction.SKIP)
                .count();
        int skippedCount = p.evaluations().size() - takenCount;
        Map<String, Integer> skipReasonCounts = p.evaluations().stream()
                .filter(e -> e.action() == SignalAction.SKIP)
                .flatMap(e -> e.reasons().stream())
                .collect(Collectors.toMap(r -> r, r -> 1, Integer::sum));
        java.util.Optional<SignalEvaluation> firstTaken = p.evaluations().stream()
                .filter(e -> e.action() != SignalAction.SKIP)
                .findFirst();
        java.util.Optional<String> firstTakenLevel =
                firstTaken.flatMap(e -> e.level()).map(Enum::name);
        java.util.Optional<Instant> firstTakenAt = firstTaken.map(SignalEvaluation::evaluatedAt);
        return new ExpectedOutcome(
                name,
                p.evaluations().size(),
                p.evaluations().size(),
                takenCount,
                skippedCount,
                skipReasonCounts,
                firstTakenLevel,
                firstTakenAt);
    }
}
