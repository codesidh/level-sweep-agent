package com.levelsweep.aiagent.sentinel.replay;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.audit.PromptHasher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;

/**
 * Fixture-backed {@link AnthropicClient.Fetcher} (ADR-0007 §5).
 *
 * <p>Returns one of three on every {@link #fetch(HttpRequest)} call:
 *
 * <ul>
 *   <li>HTTP 2xx with the recorded body when the fixture status is in the
 *       2xx range.</li>
 *   <li>HTTP non-2xx with the recorded snippet when the fixture status is
 *       4xx/5xx (e.g. 429 → {@code AnthropicResponse.RateLimited}).</li>
 *   <li>An {@link IOException} when the fixture status is {@code 0} —
 *       simulates a transport failure. The magic body
 *       {@code "circuit_breaker_open"} surfaces in the
 *       {@code AnthropicResponse.TransportFailure.exceptionMessage} so the
 *       sentinel response parser routes to {@code FallbackReason.CB_OPEN}.</li>
 * </ul>
 *
 * <p>Determinism reinforcement: the fetcher is bound to ONE fixture per
 * instance. {@link #invocations()} exposes the call count so the test can
 * assert exactly one call per fixture (the saga is single-attempt, ADR-0007
 * §4 — any second call is a contract regression).
 */
final class FixtureBackedFetcher implements AnthropicClient.Fetcher {

    /** Magic body string the fetcher looks for when status is {@code 0}. */
    static final String CIRCUIT_BREAKER_OPEN_MARKER = "circuit_breaker_open";

    private final SentinelReplayFixture fixture;
    private final AtomicInteger invocations = new AtomicInteger();

    FixtureBackedFetcher(SentinelReplayFixture fixture) {
        this.fixture = Objects.requireNonNull(fixture, "fixture");
    }

    @Override
    public HttpResponse<String> fetch(HttpRequest request) throws IOException {
        invocations.incrementAndGet();
        int status = fixture.anthropicHttpStatus();
        if (status == 0) {
            // Simulate a transport failure. The body field of the fixture
            // double-purposes as the exception class hint:
            //   - "circuit_breaker_open" → AnthropicConnectionMonitor would have
            //     short-circuited before us; the response parser maps the
            //     resulting TransportFailure(exceptionMessage="circuit_breaker_open")
            //     to FallbackReason.CB_OPEN.
            //   - anything else → generic IOException → FallbackReason.TRANSPORT.
            String marker = fixture.anthropicResponseBody() == null ? "io_error" : fixture.anthropicResponseBody();
            throw new IOException(marker);
        }
        return new ReplayHttpResponse(
                request, status, fixture.anthropicResponseBody() == null ? "" : fixture.anthropicResponseBody());
    }

    /** Stable per-fixture key — not used by the runner today but useful for debugging. */
    String requestKey(com.levelsweep.aiagent.anthropic.AnthropicRequest req) {
        return PromptHasher.hash(req);
    }

    int invocations() {
        return invocations.get();
    }

    /** Minimal {@link HttpResponse} shim — the production parser only reads {@code statusCode()} + {@code body()}. */
    private static final class ReplayHttpResponse implements HttpResponse<String> {
        private final HttpRequest request;
        private final int status;
        private final String body;

        ReplayHttpResponse(HttpRequest request, int status, String body) {
            this.request = request;
            this.status = status;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }
    }
}
