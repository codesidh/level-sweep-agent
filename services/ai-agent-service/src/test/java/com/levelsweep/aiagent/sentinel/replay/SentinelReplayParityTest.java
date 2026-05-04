package com.levelsweep.aiagent.sentinel.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicClientReplayFactory;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.audit.AiCallAuditWriter;
import com.levelsweep.aiagent.audit.PromptHasher;
import com.levelsweep.aiagent.connection.AnthropicConnectionMonitor;
import com.levelsweep.aiagent.connection.ConnectionMonitor;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse;
import com.levelsweep.aiagent.sentinel.SentinelPromptBuilder;
import com.levelsweep.aiagent.sentinel.SentinelResponseParser;
import com.levelsweep.aiagent.sentinel.SentinelService;
import com.levelsweep.aiagent.sentinel.SentinelServiceTestFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Replay-parity harness for the Pre-Trade Sentinel (ADR-0007 §5 +
 * CLAUDE.md guardrail #5).
 *
 * <p>For every fixture under {@code src/test/resources/sentinel/replay/*.json},
 * the test:
 *
 * <ol>
 *   <li>Constructs a fresh {@link SentinelService} wired to a
 *       {@link FixtureBackedFetcher} that replays the recorded HTTP outcome.</li>
 *   <li>Invokes {@link SentinelService#evaluate(SentinelDecisionRequest)} with
 *       the recorded request.</li>
 *   <li>Asserts the response matches the recorded {@code expected} block on
 *       the deterministic fields (variant, decision_path, fallback_reason,
 *       confidence, reason_code, reason_text). {@code clientRequestId} and
 *       {@code latencyMs} are non-deterministic by construction and excluded.</li>
 * </ol>
 *
 * <p>Parity contract: ≥99% on 30 historical sessions. With a hand-curated
 * corpus we expect 100% — any divergence names the offending fixture and the
 * delta. The corpus is grown by replaying real Anthropic calls against the
 * paper-soak environment (see {@code sentinel/replay/README.md}).
 */
class SentinelReplayParityTest {

    /**
     * Pinned trading-clock anchor used by all fixtures. Fixed so the prompt
     * bytes are byte-identical across runs.
     */
    private static final Instant FIXED_NOW = Instant.parse("2026-05-04T13:35:00Z");

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final Duration TIMEOUT = Duration.ofMillis(750);
    private static final String MODEL = "claude-haiku-4-5";

    private DailyCostTracker costTracker;
    private AnthropicConnectionMonitor connectionMonitor;
    private AiAgentMetrics metrics;
    private AiCallAuditWriter auditWriter;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        // Cost tracker — never breach during replay (the cost-cap fixture
        // exercises the breach via fixture status semantics if we ever add
        // one; today's COST_CAP path is not represented because the saga
        // fail-OPEN behavior is identical to the others).
        costTracker = mock(DailyCostTracker.class);
        when(costTracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(costTracker.today()).thenReturn(LocalDate.of(2026, 5, 4));
        when(costTracker.capFor(any())).thenReturn(new BigDecimal("0.50"));
        when(costTracker.currentSpend(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        // Connection monitor — replay treats Anthropic as healthy except where
        // a fixture explicitly emits a "circuit_breaker_open" transport
        // failure marker (tested via the fetcher).
        connectionMonitor = mock(AnthropicConnectionMonitor.class);
        when(connectionMonitor.state()).thenReturn(ConnectionMonitor.State.HEALTHY);
        when(connectionMonitor.shouldShortCircuit()).thenReturn(false);
        when(connectionMonitor.dependency()).thenReturn("anthropic");

        metrics = AiAgentMetrics.noop();
        // Audit writer is a no-op — the replay harness asserts saga decisions,
        // not Mongo round-trips. AiCallAuditWriter has CDI-only DI so we mock.
        auditWriter = mock(AiCallAuditWriter.class);

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sentinel-replay-executor");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    static List<SentinelReplayFixture> allFixtures() {
        return SentinelReplayFixture.loadAll();
    }

    @Test
    @DisplayName("fixture corpus has at least 10 entries (ADR-0007 §5 minimum)")
    void corpusHasMinimumSize() {
        List<SentinelReplayFixture> fixtures = SentinelReplayFixture.loadAll();
        assertThat(fixtures)
                .as("replay corpus under src/test/resources/sentinel/replay/")
                .hasSizeGreaterThanOrEqualTo(10);
        // No duplicate names — sanity that the loader's filename ordering
        // didn't accidentally collapse two fixtures.
        Set<String> names = new HashSet<>();
        for (SentinelReplayFixture f : fixtures) {
            assertThat(names.add(f.name()))
                    .as("duplicate fixture name: %s", f.name())
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "replay parity: {0}")
    @MethodSource("allFixtures")
    void replayMatchesRecordedDecision(SentinelReplayFixture fixture) {
        FixtureBackedFetcher fetcher = new FixtureBackedFetcher(fixture);
        SentinelService service = newService(fetcher, fixture);

        SentinelDecisionResponse actual = service.evaluate(fixture.request());

        fixture.expected().assertMatches(actual, fixture.name());

        // Determinism reinforcement #1 (ADR-0007 §5): the Fetcher MUST be
        // invoked at most once per evaluation. CB_OPEN short-circuits before
        // the fetcher runs (production parity); every other path runs it
        // exactly once. A second call would imply accidental retry inside
        // Sentinel — a contract regression.
        int expectedInvocations = isCbOpenFixture(fixture) ? 0 : 1;
        assertThat(fetcher.invocations())
                .as(
                        "fixture[%s] fetcher invocations: expected=%d (single-attempt or CB short-circuit)",
                        fixture.name(), expectedInvocations)
                .isEqualTo(expectedInvocations);
    }

    @Test
    @DisplayName("≥99% parity across the corpus (CLAUDE.md guardrail #5)")
    void parityAcrossCorpus() {
        List<SentinelReplayFixture> fixtures = SentinelReplayFixture.loadAll();
        int passed = 0;
        int failed = 0;
        StringBuilder failures = new StringBuilder();
        for (SentinelReplayFixture fixture : fixtures) {
            FixtureBackedFetcher fetcher = new FixtureBackedFetcher(fixture);
            SentinelService service = newService(fetcher, fixture);
            try {
                SentinelDecisionResponse actual = service.evaluate(fixture.request());
                fixture.expected().assertMatches(actual, fixture.name());
                passed++;
            } catch (AssertionError e) {
                failed++;
                failures.append(System.lineSeparator()).append("  - ").append(e.getMessage());
            }
        }
        double parity = fixtures.isEmpty() ? 0.0d : (double) passed / fixtures.size();
        assertThat(parity)
                .as("replay parity %d/%d:%s", passed, fixtures.size(), failures)
                .isGreaterThanOrEqualTo(0.99d);
        // Hard zero on failures — the corpus is hand-curated.
        assertThat(failed)
                .as("expected zero replay divergences, got %d:%s", failed, failures)
                .isZero();
    }

    /**
     * A CB_OPEN fixture is encoded as {@code anthropic.status=0} with the magic
     * body {@code "circuit_breaker_open"}. Production parity requires the
     * Connection FSM (not the Fetcher) to short-circuit, so the test stubs
     * {@code shouldShortCircuit() -> true} only for these fixtures and Sentinel
     * never reaches the fetcher.
     */
    private static boolean isCbOpenFixture(SentinelReplayFixture fixture) {
        return fixture.anthropicHttpStatus() == 0
                && FixtureBackedFetcher.CIRCUIT_BREAKER_OPEN_MARKER.equals(fixture.anthropicResponseBody());
    }

    @Test
    @DisplayName("prompt-hash key is stable: 100 builds of the same request hash to one digest")
    void promptHashStable_acrossManyBuilds() {
        List<SentinelReplayFixture> fixtures = SentinelReplayFixture.loadAll();
        assertThat(fixtures).isNotEmpty();
        SentinelReplayFixture fixture = fixtures.get(0);
        SentinelPromptBuilder promptBuilder = new SentinelPromptBuilder(MODEL);

        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            AnthropicRequest req = promptBuilder.build(fixture.request());
            hashes.add(PromptHasher.hash(req));
        }
        assertThat(hashes)
                .as("100 builds of fixture[%s] must hash to a single digest", fixture.name())
                .hasSize(1);
    }

    @Test
    @DisplayName("prompt-hash key is stable across all fixtures (no two distinct fixtures share a hash)")
    void promptHashUniquePerFixture() {
        List<SentinelReplayFixture> fixtures = SentinelReplayFixture.loadAll();
        SentinelPromptBuilder promptBuilder = new SentinelPromptBuilder(MODEL);

        // Two semantically-distinct requests MUST produce different digests —
        // otherwise the fixture lookup-by-hash strategy in §5 collides.
        Set<String> hashes = new HashSet<>();
        for (SentinelReplayFixture fixture : fixtures) {
            AnthropicRequest req = promptBuilder.build(fixture.request());
            String hash = PromptHasher.hash(req);
            // Fixtures CAN share a hash if they're identical apart from the
            // expected outcome (transport vs success differ on the response,
            // not the request). The cb_open / transport / parse / rate_limit
            // failure-mode fixtures may legitimately share the same request.
            // The corpus today keeps them request-unique by varying tradeId.
            assertThat(hashes.add(hash))
                    .as(
                            "fixture[%s] prompt-hash collides with another fixture — "
                                    + "vary request fields (e.g. tradeId) so each fixture has a distinct key",
                            fixture.name())
                    .isTrue();
        }
    }

    private SentinelService newService(AnthropicClient.Fetcher fetcher, SentinelReplayFixture fixture) {
        // Per-fixture connection-monitor stubbing: CB_OPEN fixtures must
        // short-circuit at the FSM (no fetcher call), every other path passes
        // through. Re-stub before each call so the parameterized test isn't
        // contaminated by prior iterations.
        if (isCbOpenFixture(fixture)) {
            when(connectionMonitor.shouldShortCircuit()).thenReturn(true);
        } else {
            when(connectionMonitor.shouldShortCircuit()).thenReturn(false);
        }
        AnthropicClient anthropicClient =
                AnthropicClientReplayFactory.withFetcher(fetcher, costTracker, connectionMonitor, metrics, FIXED_CLOCK);
        SentinelPromptBuilder promptBuilder = new SentinelPromptBuilder(MODEL);
        SentinelResponseParser parser = new SentinelResponseParser();
        return SentinelServiceTestFactory.create(
                promptBuilder,
                parser,
                anthropicClient,
                connectionMonitor,
                auditWriter,
                metrics,
                FIXED_CLOCK,
                /* enabled */ true,
                executor,
                TIMEOUT);
    }
}
