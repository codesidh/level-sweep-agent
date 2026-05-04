package com.levelsweep.aiagent.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.connection.AnthropicConnectionMonitor;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link AnthropicClient}. Mirrors
 * {@code AlpacaTradingClientTest} — exercises the {@link AnthropicClient.Fetcher}
 * seam against canned JSON, no real HTTP traffic, no real Anthropic key.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>200 with valid Messages API JSON → {@link AnthropicResponse.Success}
 *       (text, tool calls, tokens, computed cost)</li>
 *   <li>429 → {@link AnthropicResponse.RateLimited}</li>
 *   <li>529 → {@link AnthropicResponse.Overloaded}</li>
 *   <li>503 → {@link AnthropicResponse.Overloaded}</li>
 *   <li>400 → {@link AnthropicResponse.InvalidRequest}</li>
 *   <li>{@link IOException} → {@link AnthropicResponse.TransportFailure}</li>
 *   <li>Cost cap pre-flight breach → {@link AnthropicResponse.CostCapBreached}
 *       and NO HTTP call made</li>
 *   <li>{@code x-api-key} header present, value not in body</li>
 *   <li>{@code anthropic-version} pinned at 2023-06-01</li>
 *   <li>{@code anthropic-beta} prompt-caching header present when config flag true</li>
 *   <li>Prompt-caching header absent when config flag false</li>
 * </ul>
 */
class AnthropicClientTest {

    private static final String BASE_URL = "https://api.anthropic.com";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-02T13:32:30Z"), ZoneOffset.UTC);
    private static final BigDecimal PROJECTED_COST = new BigDecimal("0.0050");

    private DailyCostTracker tracker;
    private AnthropicConnectionMonitor connectionMonitor;
    private AiAgentMetrics metrics;

    @BeforeEach
    void setUp() {
        // Default tracker: ample budget remaining; pre-flight check returns false.
        tracker = mock(DailyCostTracker.class);
        when(tracker.wouldExceedCap(any(), any(), any(), any())).thenReturn(false);
        when(tracker.today()).thenReturn(LocalDate.of(2026, 5, 2));
        when(tracker.capFor(any())).thenReturn(new BigDecimal("1.00"));
        when(tracker.currentSpend(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        // Connection FSM defaults to HEALTHY for the existing tests; the
        // FSM-specific behavior is exercised in AnthropicClientConnectionFsmTest.
        connectionMonitor = new AnthropicConnectionMonitor(FIXED_CLOCK);
        metrics = AiAgentMetrics.noop();
    }

    @Test
    void twoHundredWithValidJsonProducesSuccess() {
        String body =
                """
                {
                  "id": "msg_01ABC",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-haiku-4-5",
                  "content": [
                    {"type": "text", "text": "ALLOW: signal looks clean."}
                  ],
                  "usage": {
                    "input_tokens": 1500,
                    "output_tokens": 50,
                    "cache_read_input_tokens": 0
                  }
                }
                """;
        AnthropicClient client = newClient(req -> okResponse(body));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("evaluate signal X"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.Success.class);
        AnthropicResponse.Success s = (AnthropicResponse.Success) outcome;
        assertThat(s.responseText()).isEqualTo("ALLOW: signal looks clean.");
        assertThat(s.toolCalls()).isEmpty();
        assertThat(s.inputTokens()).isEqualTo(1500);
        assertThat(s.outputTokens()).isEqualTo(50);
        assertThat(s.cachedTokens()).isEqualTo(0);
        // Haiku 4.5: 1500/1M × $1 + 50/1M × $5 = $0.0015 + $0.00025 → $0.0018 at 4dp HALF_UP
        assertThat(s.costUsd()).isEqualByComparingTo(new BigDecimal("0.0018"));
        assertThat(s.role()).isEqualTo(Role.SENTINEL);
        assertThat(s.model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void twoHundredWithToolUseProducesSuccessWithToolCalls() {
        String body =
                """
                {
                  "content": [
                    {"type": "text", "text": "calling tool now"},
                    {"type": "tool_use", "id": "tu_1", "name": "veto_signal", "input": {"reason": "x"}}
                  ],
                  "usage": {"input_tokens": 100, "output_tokens": 10, "cache_read_input_tokens": 0}
                }
                """;
        AnthropicClient client = newClient(req -> okResponse(body));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.Success.class);
        AnthropicResponse.Success s = (AnthropicResponse.Success) outcome;
        assertThat(s.toolCalls()).containsExactly("veto_signal");
        assertThat(s.responseText()).isEqualTo("calling tool now");
    }

    @Test
    void cachedTokensAreReportedAndPriced() {
        String body =
                """
                {
                  "content": [{"type": "text", "text": "ok"}],
                  "usage": {"input_tokens": 200, "output_tokens": 50, "cache_read_input_tokens": 1800}
                }
                """;
        AnthropicClient client = newClient(req -> okResponse(body));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("review"));

        AnthropicResponse.Success s = (AnthropicResponse.Success) outcome;
        assertThat(s.inputTokens()).isEqualTo(200);
        assertThat(s.cachedTokens()).isEqualTo(1800);
        // 200 × $1 + 1800 × $0.10 + 50 × $5 = 200 + 180 + 250 = 630 / 1_000_000 = 0.00063 → 0.0006 at 4dp HALF_UP
        assertThat(s.costUsd()).isEqualByComparingTo(new BigDecimal("0.0006"));
    }

    @Test
    void fourTwentyNineProducesRateLimited() {
        AnthropicClient client = newClient(req -> response(429, "{\"type\":\"error\",\"message\":\"rate limited\"}"));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.RateLimited.class);
        AnthropicResponse.RateLimited rl = (AnthropicResponse.RateLimited) outcome;
        assertThat(rl.responseBodySnippet()).contains("rate limited");
    }

    @Test
    void fiveTwentyNineProducesOverloaded() {
        AnthropicClient client = newClient(req -> response(529, "overloaded"));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.Overloaded.class);
        AnthropicResponse.Overloaded o = (AnthropicResponse.Overloaded) outcome;
        assertThat(o.httpStatus()).isEqualTo(529);
    }

    @Test
    void fiveZeroThreeAlsoProducesOverloaded() {
        AnthropicClient client = newClient(req -> response(503, "service unavailable"));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.Overloaded.class);
        assertThat(((AnthropicResponse.Overloaded) outcome).httpStatus()).isEqualTo(503);
    }

    @Test
    void fourHundredProducesInvalidRequest() {
        AnthropicClient client =
                newClient(req -> response(400, "{\"type\":\"error\",\"message\":\"prompt_too_long\"}"));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.InvalidRequest.class);
        AnthropicResponse.InvalidRequest ir = (AnthropicResponse.InvalidRequest) outcome;
        assertThat(ir.httpStatus()).isEqualTo(400);
        assertThat(ir.responseBodySnippet()).contains("prompt_too_long");
    }

    @Test
    void ioExceptionProducesTransportFailure() {
        AnthropicClient.Fetcher fetcher = req -> {
            throw new IOException("connection reset");
        };
        AnthropicClient client = newClientWithFetcher(fetcher);

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.TransportFailure.class);
        AnthropicResponse.TransportFailure tf = (AnthropicResponse.TransportFailure) outcome;
        assertThat(tf.exceptionMessage()).contains("connection reset");
    }

    @Test
    void emptyBodyOn2xxIsTreatedAsTransportFailure() {
        AnthropicClient client = newClient(req -> okResponse(""));

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.TransportFailure.class);
    }

    @Test
    void costCapBreachShortCircuitsBeforeHttpCall() {
        when(tracker.wouldExceedCap(eq("OWNER"), eq(Role.SENTINEL), any(), eq(PROJECTED_COST)))
                .thenReturn(true);
        when(tracker.currentSpend(eq("OWNER"), eq(Role.SENTINEL), any())).thenReturn(new BigDecimal("0.99"));
        when(tracker.capFor(Role.SENTINEL)).thenReturn(new BigDecimal("1.00"));

        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AnthropicClient.Fetcher fetcher = req -> {
            captured.set(req);
            return okResponse("{}");
        };
        AnthropicClient client = newClientWithFetcher(fetcher);

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.CostCapBreached.class);
        AnthropicResponse.CostCapBreached b = (AnthropicResponse.CostCapBreached) outcome;
        assertThat(b.capUsd()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(b.currentSpendUsd()).isEqualByComparingTo(new BigDecimal("0.99"));
        assertThat(b.projectedCallCostUsd()).isEqualByComparingTo(PROJECTED_COST);
        // Critical: NO HTTP call was made.
        assertThat(captured.get()).isNull();
        verify(tracker).wouldExceedCap(eq("OWNER"), eq(Role.SENTINEL), any(), eq(PROJECTED_COST));
        // No recordCost should be called either.
        verify(tracker, never()).recordCost(any(), any(), any(), any());
    }

    @Test
    void requestCarriesAuthAndApiVersionHeaders() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AnthropicClient.Fetcher fetcher = req -> {
            captured.set(req);
            return okResponse(minimalSuccessBody());
        };
        AnthropicClient client = newClientWithFetcher(fetcher);

        client.submit(haikuSentinelRequest("decide"));

        HttpRequest req = captured.get();
        assertThat(req).isNotNull();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.uri().toString()).isEqualTo(BASE_URL + "/v1/messages");
        assertThat(req.headers().firstValue("x-api-key")).hasValue("AKtest");
        assertThat(req.headers().firstValue("anthropic-version")).hasValue("2023-06-01");
        assertThat(req.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void promptCachingHeaderPresentWhenEnabled() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AnthropicClient client = new AnthropicClient(
                BASE_URL,
                Optional.of("AKtest"),
                /* promptCachingEnabled */ true,
                FIXED_CLOCK,
                new ObjectMapper(),
                req -> {
                    captured.set(req);
                    return okResponse(minimalSuccessBody());
                },
                tracker,
                connectionMonitor,
                metrics);

        client.submit(haikuSentinelRequest("decide"));

        assertThat(captured.get().headers().firstValue("anthropic-beta")).hasValue("prompt-caching-2024-07-31");
    }

    @Test
    void promptCachingHeaderAbsentWhenDisabled() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AnthropicClient client = new AnthropicClient(
                BASE_URL,
                Optional.of("AKtest"),
                /* promptCachingEnabled */ false,
                FIXED_CLOCK,
                new ObjectMapper(),
                req -> {
                    captured.set(req);
                    return okResponse(minimalSuccessBody());
                },
                tracker,
                connectionMonitor,
                metrics);

        client.submit(haikuSentinelRequest("decide"));

        assertThat(captured.get().headers().firstValue("anthropic-beta")).isEmpty();
    }

    @Test
    void apiKeyNeverAppearsInSerializedBody() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AnthropicClient.Fetcher fetcher = req -> {
            capturedBody.set(extractBody(req));
            return okResponse(minimalSuccessBody());
        };
        AnthropicClient client = newClientWithFetcher(fetcher);

        client.submit(haikuSentinelRequest("decide"));

        // The redaction guarantee: never under any circumstance does the JSON
        // body include the credentials. They are HTTP headers — not payload.
        String body = capturedBody.get();
        assertThat(body).doesNotContain("AKtest");
        // Sanity: the body was actually populated.
        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.get("model").asText()).isEqualTo("claude-haiku-4-5");
        assertThat(root.get("system").asText()).contains("Sentinel");
        assertThat(root.get("max_tokens").asInt()).isEqualTo(300);
        assertThat(root.get("temperature").asDouble()).isEqualTo(0.0);
        assertThat(root.get("messages").isArray()).isTrue();
    }

    @Test
    void missingApiKeyStillAttemptsRequest() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AnthropicClient.Fetcher fetcher = req -> {
            captured.set(req);
            return response(401, "{\"message\":\"unauthorized\"}");
        };
        AnthropicClient client = new AnthropicClient(
                BASE_URL,
                Optional.empty(), /* promptCaching */
                true,
                FIXED_CLOCK,
                new ObjectMapper(),
                fetcher,
                tracker,
                connectionMonitor,
                metrics);

        AnthropicResponse outcome = client.submit(haikuSentinelRequest("decide"));

        assertThat(outcome).isInstanceOf(AnthropicResponse.InvalidRequest.class);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().firstValue("x-api-key")).isEmpty();
    }

    // --- helpers --------------------------------------------------------------

    private AnthropicClient newClient(AnthropicClient.Fetcher fetcher) {
        return newClientWithFetcher(fetcher);
    }

    private AnthropicClient newClientWithFetcher(AnthropicClient.Fetcher fetcher) {
        return new AnthropicClient(
                BASE_URL,
                Optional.of("AKtest"), /* promptCaching */
                true,
                FIXED_CLOCK,
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

    /**
     * Extract the {@code POST} body from the JDK {@link HttpRequest}. Same shape
     * as {@code AlpacaTradingClientTest#extractBody} — the {@link HttpRequest.BodyPublisher}
     * delivers bytes via a reactive {@link java.util.concurrent.Flow.Subscriber}; we
     * synthesize a tiny one that concatenates the chunks. {@code BodyPublishers.ofString}
     * is fully synchronous so the result is populated before the method returns.
     */
    private static String extractBody(HttpRequest req) {
        Optional<HttpRequest.BodyPublisher> bp = req.bodyPublisher();
        if (bp.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        bp.get().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] arr = new byte[item.remaining()];
                item.get(arr);
                sb.append(new String(arr, java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable throwable) {
                // ignore — test seam, errors surface elsewhere
            }

            @Override
            public void onComplete() {
                // no-op
            }
        });
        return sb.toString();
    }
}
