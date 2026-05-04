package com.levelsweep.aiagent.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.connection.AnthropicConnectionMonitor;
import com.levelsweep.aiagent.connection.ConnectionMonitor;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-style tests that prove {@link AnthropicClient} short-circuits
 * the Anthropic Messages API call when the {@link AnthropicConnectionMonitor}
 * says the circuit breaker is open (ADR-0007 §3 fail-OPEN {@code cb_open}),
 * and that successful probes restore the FSM to HEALTHY.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>5 consecutive transport failures → 6th call short-circuits with a
 *       TransportFailure carrying reason {@code circuit_breaker_open}; the
 *       {@code Fetcher} is NEVER invoked for the short-circuited call.</li>
 *   <li>After the probe interval elapses, exactly one probe is admitted; on
 *       success the state returns to HEALTHY and subsequent calls go through
 *       normally.</li>
 *   <li>A 2xx success after some errors clears the error window so the next
 *       round of errors must accumulate from zero.</li>
 * </ul>
 */
class AnthropicClientConnectionFsmTest {

    private static final String BASE_URL = "https://api.anthropic.com";
    private static final BigDecimal PROJECTED_COST = new BigDecimal("0.0050");

    /** Mutable clock so we can step past the 15s probe interval deterministically. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;

        TestClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    private TestClock clock;
    private DailyCostTracker tracker;
    private AnthropicConnectionMonitor connectionMonitor;
    private AiAgentMetrics metrics;

    @BeforeEach
    void setUp() {
        clock = new TestClock(Instant.parse("2026-05-04T13:30:00Z"));
        tracker = mock(DailyCostTracker.class);
        when(tracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(tracker.today()).thenReturn(LocalDate.of(2026, 5, 4));
        when(tracker.capFor(any())).thenReturn(new BigDecimal("1.00"));
        when(tracker.currentSpend(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        connectionMonitor = new AnthropicConnectionMonitor(clock);
        metrics = AiAgentMetrics.noop();
    }

    @Test
    void fiveConsecutiveTransportFailuresOpenCircuitAndShortCircuitNextCall() {
        AtomicInteger fetcherInvocations = new AtomicInteger();
        AnthropicClient.Fetcher fetcher = req -> {
            fetcherInvocations.incrementAndGet();
            throw new IOException("connection reset");
        };
        AnthropicClient client = newClient(fetcher);

        // Drive 5 transport failures — fetcher fires each time.
        for (int i = 0; i < 5; i++) {
            AnthropicResponse outcome = client.submit(haikuSentinelRequest("attempt-" + i));
            assertThat(outcome).isInstanceOf(AnthropicResponse.TransportFailure.class);
        }
        assertThat(fetcherInvocations.get()).isEqualTo(5);
        assertThat(connectionMonitor.state()).isEqualTo(ConnectionMonitor.State.UNHEALTHY);
        assertThat(connectionMonitor.shouldShortCircuit()).isTrue();

        // 6th call: must short-circuit BEFORE the fetcher runs. Reason matches
        // the cb_open contract Sentinel reads in ADR-0007 §3.
        AnthropicResponse outcome = client.submit(haikuSentinelRequest("attempt-6"));
        assertThat(outcome).isInstanceOf(AnthropicResponse.TransportFailure.class);
        AnthropicResponse.TransportFailure tf = (AnthropicResponse.TransportFailure) outcome;
        assertThat(tf.exceptionMessage()).isEqualTo("circuit_breaker_open");
        assertThat(fetcherInvocations.get()).isEqualTo(5); // fetcher NOT called again
    }

    @Test
    void probeAdmittedAfterIntervalAndSuccessRestoresHealthy() {
        AtomicInteger fetcherInvocations = new AtomicInteger();
        AtomicReference<HttpResponse<String>> nextResponse = new AtomicReference<>();
        AtomicReference<IOException> nextException = new AtomicReference<>(new IOException("connection reset"));

        AnthropicClient.Fetcher fetcher = req -> {
            fetcherInvocations.incrementAndGet();
            IOException e = nextException.get();
            if (e != null) {
                throw e;
            }
            return nextResponse.get();
        };
        AnthropicClient client = newClient(fetcher);

        // Open the circuit with 5 failures.
        for (int i = 0; i < 5; i++) {
            client.submit(haikuSentinelRequest("err-" + i));
        }
        assertThat(connectionMonitor.state()).isEqualTo(ConnectionMonitor.State.UNHEALTHY);
        assertThat(connectionMonitor.shouldShortCircuit()).isTrue();

        // Inside the probe interval — 6th call short-circuits, fetcher NOT invoked.
        client.submit(haikuSentinelRequest("blocked"));
        assertThat(fetcherInvocations.get()).isEqualTo(5);

        // Advance past the 15s probe interval; arm the fetcher to return success.
        clock.advance(Duration.ofSeconds(20));
        nextException.set(null);
        nextResponse.set(okResponse(minimalSuccessBody()));

        AnthropicResponse probeOutcome = client.submit(haikuSentinelRequest("probe"));

        assertThat(probeOutcome).isInstanceOf(AnthropicResponse.Success.class);
        assertThat(fetcherInvocations.get()).isEqualTo(6); // probe DID hit the fetcher
        assertThat(connectionMonitor.state()).isEqualTo(ConnectionMonitor.State.HEALTHY);
        assertThat(connectionMonitor.shouldShortCircuit()).isFalse();

        // Subsequent call goes through normally — confirms HEALTHY is sticky.
        AnthropicResponse healthyOutcome = client.submit(haikuSentinelRequest("post-recovery"));
        assertThat(healthyOutcome).isInstanceOf(AnthropicResponse.Success.class);
        assertThat(fetcherInvocations.get()).isEqualTo(7);
    }

    @Test
    void successAfterSomeErrorsClearsErrorWindow() {
        AtomicInteger fetcherInvocations = new AtomicInteger();
        AtomicReference<IOException> nextException = new AtomicReference<>(new IOException("connection reset"));
        AtomicReference<HttpResponse<String>> nextResponse = new AtomicReference<>();

        AnthropicClient.Fetcher fetcher = req -> {
            fetcherInvocations.incrementAndGet();
            IOException e = nextException.get();
            if (e != null) {
                throw e;
            }
            return nextResponse.get();
        };
        AnthropicClient client = newClient(fetcher);

        // Three failures move us to DEGRADED.
        for (int i = 0; i < 3; i++) {
            client.submit(haikuSentinelRequest("err-" + i));
        }
        assertThat(connectionMonitor.state()).isEqualTo(ConnectionMonitor.State.DEGRADED);

        // Next call succeeds → window cleared, state HEALTHY.
        nextException.set(null);
        nextResponse.set(okResponse(minimalSuccessBody()));
        AnthropicResponse outcome = client.submit(haikuSentinelRequest("ok"));
        assertThat(outcome).isInstanceOf(AnthropicResponse.Success.class);
        assertThat(connectionMonitor.state()).isEqualTo(ConnectionMonitor.State.HEALTHY);

        // Two more errors after recovery should NOT trip DEGRADED — the window
        // was reset by recordSuccess.
        nextException.set(new IOException("transient"));
        client.submit(haikuSentinelRequest("err-A"));
        client.submit(haikuSentinelRequest("err-B"));
        assertThat(connectionMonitor.state()).isEqualTo(ConnectionMonitor.State.HEALTHY);
    }

    @Test
    void shortCircuitDoesNotConsultCostTrackerCapBreach() {
        // Open the circuit first.
        AnthropicClient.Fetcher errFetcher = req -> {
            throw new IOException("connection reset");
        };
        AnthropicClient client = newClient(errFetcher);
        for (int i = 0; i < 5; i++) {
            client.submit(haikuSentinelRequest("err-" + i));
        }

        // Cap pre-flight runs first; FSM short-circuit only after. We reset the
        // mock here so the count of wouldExceedCap calls equals 1 for the next
        // submission only.
        org.mockito.Mockito.reset(tracker);
        when(tracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(tracker.today()).thenReturn(LocalDate.of(2026, 5, 4));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("blocked"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.TransportFailure.class);
        // The cost cap pre-flight WAS consulted (it runs before CB), but no
        // recordCost happened (no HTTP call).
        verify(tracker, atLeastOnce()).wouldExceedCap(any(), any(), any(), any());
        verify(tracker, never()).recordCost(any(), any(), any(), any());
    }

    // --- helpers --------------------------------------------------------------

    private AnthropicClient newClient(AnthropicClient.Fetcher fetcher) {
        return new AnthropicClient(
                BASE_URL,
                Optional.of("AKtest"),
                /* promptCachingEnabled */ true,
                clock,
                new ObjectMapper(),
                fetcher,
                tracker,
                connectionMonitor,
                metrics);
    }

    private static AnthropicRequest haikuSentinelRequest(String userMessage) {
        return new AnthropicRequest(
                "claude-haiku-4-5",
                "You are the Pre-Trade Sentinel. Reply ALLOW or VETO.",
                List.of(AnthropicMessage.user(userMessage)),
                List.of(),
                300,
                0.0d,
                "OWNER",
                Role.SENTINEL,
                PROJECTED_COST);
    }

    private static String minimalSuccessBody() {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"cache_read_input_tokens\":0}}";
    }

    private static HttpResponse<String> okResponse(String body) {
        return response(200, body);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> resp = (HttpResponse<String>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }
}
