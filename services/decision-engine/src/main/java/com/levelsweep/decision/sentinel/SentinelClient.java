package com.levelsweep.decision.sentinel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST client for the ai-agent-service Pre-Trade Sentinel endpoint
 * ({@code POST /api/v1/sentinel/evaluate}). Called from the Trade Saga
 * between RiskGate and StrikeSelector per ADR-0007 §1.
 *
 * <p>Endpoint shape:
 *
 * <pre>
 * POST {ai-agent.url}/api/v1/sentinel/evaluate
 * Headers:
 *   Content-Type: application/json
 *   Accept:       application/json
 * Body:           SentinelDecisionRequest JSON
 * Response:       SentinelDecisionResponse JSON (type-tagged sealed variant)
 * </pre>
 *
 * <p><b>Single attempt — no retry</b> (ADR-0007 §4). The remote service
 * already enforces a 750ms timeout against Anthropic; the client adds its
 * own 750ms wall-clock ceiling in case the REST hop itself stalls.
 *
 * <p><b>Fail-OPEN</b> (ADR-0007 §3). Every failure mode — timeout,
 * connection refused, non-2xx, malformed JSON, parse error — is mapped to
 * {@link SentinelClientResult.Fallback} with a fine-grained reason. The
 * saga interprets {@code Fallback} as ALLOW; a Sentinel outage must never
 * silently halt entries.
 *
 * <p><b>Logs</b>: INFO on call, WARN on fallback. The prompt content is
 * intentionally NEVER logged here — the remote service writes the audit
 * row containing the prompt; logging it twice would (a) double the
 * Mongo/App-Insights footprint and (b) risk leaking model-input data
 * through container stdout where retention rules differ from the audit DB.
 *
 * <p><b>Test seam</b>: mirrors {@code AlpacaTradingClient}'s {@link Fetcher}
 * — production gets a JDK {@link HttpClient}; tests inject a stub
 * {@link Function} that returns canned {@link HttpResponse} payloads.
 */
@ApplicationScoped
public class SentinelClient {

    private static final Logger LOG = LoggerFactory.getLogger(SentinelClient.class);

    /** Default per-ADR-0007 §4 wall-clock ceiling. */
    public static final long DEFAULT_TIMEOUT_MS = 750L;

    private static final String EVALUATE_PATH = "/api/v1/sentinel/evaluate";

    private final String baseUrl;
    private final long timeoutMs;
    private final ObjectMapper mapper;
    private final Fetcher fetcher;

    @Inject
    public SentinelClient(
            @ConfigProperty(name = "levelsweep.ai-agent.url", defaultValue = "http://ai-agent-service.ai-agent:8084")
                    String baseUrl,
            @ConfigProperty(name = "levelsweep.sentinel.timeout-ms", defaultValue = "750") long timeoutMs) {
        this(baseUrl, timeoutMs, defaultMapper(), defaultFetcher(timeoutMs));
    }

    /** Test seam: inject a {@link Fetcher} returning canned JSON. */
    SentinelClient(String baseUrl, long timeoutMs, ObjectMapper mapper, Fetcher fetcher) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0: " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * Evaluate a single proposed trade. Always returns a non-null result —
     * never throws, never propagates a transport-layer exception.
     */
    public SentinelClientResult evaluate(SentinelDecisionRequest request) {
        Objects.requireNonNull(request, "request");

        long startNs = System.nanoTime();
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (IOException e) {
            // Serialization on a fully-validated record is essentially impossible
            // (record components are all serializable), but treat it as transport
            // for surface uniformity.
            LOG.warn(
                    "sentinel client serialization failed tenantId={} tradeId={}: {}",
                    request.tenantId(),
                    request.tradeId(),
                    e.toString());
            return new SentinelClientResult.Fallback(
                    "", SentinelClientResult.FallbackReason.TRANSPORT, elapsedMs(startNs));
        }

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + EVALUATE_PATH))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        LOG.info(
                "sentinel client submit tenantId={} tradeId={} signalId={} levelSwept={}",
                request.tenantId(),
                request.tradeId(),
                request.signalId(),
                request.levelSwept());

        // Two timeout layers — belt and braces. The HttpRequest.timeout above is
        // honored by the underlying socket; the orTimeout below catches the case
        // where the connector itself stalls before/after the socket op (DNS,
        // TLS handshake, body-stream backpressure, etc.).
        HttpResponse<String> resp;
        try {
            resp = CompletableFuture.supplyAsync(() -> doFetch(httpReq))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback(request, SentinelClientResult.FallbackReason.TRANSPORT, "interrupted", startNs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                return fallback(request, SentinelClientResult.FallbackReason.TIMEOUT, "wall_clock_timeout", startNs);
            }
            if (cause instanceof FetcherException fe) {
                if (fe.timedOut) {
                    return fallback(request, SentinelClientResult.FallbackReason.TIMEOUT, fe.reason, startNs);
                }
                return fallback(request, SentinelClientResult.FallbackReason.TRANSPORT, fe.reason, startNs);
            }
            return fallback(
                    request,
                    SentinelClientResult.FallbackReason.TRANSPORT,
                    cause == null ? "unknown" : cause.toString(),
                    startNs);
        }

        int status = resp.statusCode();
        if (status / 100 != 2) {
            return fallback(request, SentinelClientResult.FallbackReason.TRANSPORT, "http_status_" + status, startNs);
        }
        return parse(request, resp.body(), startNs);
    }

    private SentinelClientResult parse(SentinelDecisionRequest request, String body, long startNs) {
        if (body == null || body.isBlank()) {
            return fallback(request, SentinelClientResult.FallbackReason.PARSE, "empty_body", startNs);
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            return fallback(request, SentinelClientResult.FallbackReason.PARSE, e.toString(), startNs);
        }
        JsonNode typeNode = root.get("type");
        if (typeNode == null || typeNode.isNull()) {
            return fallback(request, SentinelClientResult.FallbackReason.PARSE, "missing_type_discriminator", startNs);
        }
        String type = typeNode.asText("");
        long latency = elapsedMs(startNs);
        try {
            return switch (type) {
                case "Allow" -> new SentinelClientResult.Allow(
                        textOrEmpty(root, "clientRequestId"),
                        decimal(root, "confidence"),
                        reasonCode(root),
                        textOrEmpty(root, "reasonText"),
                        latency,
                        decisionPath(root));
                case "Veto" -> new SentinelClientResult.Veto(
                        textOrEmpty(root, "clientRequestId"),
                        decimal(root, "confidence"),
                        reasonCode(root),
                        textOrEmpty(root, "reasonText"),
                        latency);
                case "Fallback" -> new SentinelClientResult.Fallback(
                        textOrEmpty(root, "clientRequestId"), fallbackReason(root), latency);
                default -> new SentinelClientResult.Fallback("", SentinelClientResult.FallbackReason.PARSE, latency);
            };
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback(request, SentinelClientResult.FallbackReason.PARSE, e.toString(), startNs);
        }
    }

    private static String textOrEmpty(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? "" : n.asText("");
    }

    private static BigDecimal decimal(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("missing " + field);
        }
        // Use textValue when present (preserves canonical form like "0.85"); fall
        // back to decimalValue for numeric tokens. asText() handles both shapes
        // and round-trips via the BigDecimal(String) constructor.
        return new BigDecimal(n.asText());
    }

    private static SentinelClientResult.ReasonCode reasonCode(JsonNode root) {
        return SentinelClientResult.ReasonCode.valueOf(textOrEmpty(root, "reasonCode"));
    }

    private static SentinelClientResult.DecisionPath decisionPath(JsonNode root) {
        return SentinelClientResult.DecisionPath.valueOf(textOrEmpty(root, "decisionPath"));
    }

    private static SentinelClientResult.FallbackReason fallbackReason(JsonNode root) {
        return SentinelClientResult.FallbackReason.valueOf(textOrEmpty(root, "reason"));
    }

    private SentinelClientResult.Fallback fallback(
            SentinelDecisionRequest request, SentinelClientResult.FallbackReason reason, String detail, long startNs) {
        long latency = elapsedMs(startNs);
        LOG.warn(
                "sentinel client fallback tenantId={} tradeId={} signalId={} reason={} detail={} latencyMs={}",
                request.tenantId(),
                request.tradeId(),
                request.signalId(),
                reason,
                detail,
                latency);
        return new SentinelClientResult.Fallback("", reason, latency);
    }

    private HttpResponse<String> doFetch(HttpRequest request) {
        try {
            return fetcher.fetch(request);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new FetcherException(true, e.toString());
        } catch (IOException e) {
            throw new FetcherException(false, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetcherException(false, "interrupted");
        }
    }

    private static long elapsedMs(long startNs) {
        return Duration.ofNanos(System.nanoTime() - startNs).toMillis();
    }

    /** Default mapper — JSR-310 module + ISO-8601 instants for the wire types. */
    private static ObjectMapper defaultMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static Fetcher defaultFetcher(long timeoutMs) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        return req -> client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Minimal HTTP seam — abstracts {@link HttpClient} so tests can stub it. */
    @FunctionalInterface
    interface Fetcher {
        HttpResponse<String> fetch(HttpRequest request) throws IOException, InterruptedException;
    }

    /** Bridge so tests can pass a {@code Function<HttpRequest, HttpResponse<String>>}. */
    static Fetcher fromFunction(Function<HttpRequest, HttpResponse<String>> fn) {
        Objects.requireNonNull(fn, "fn");
        return req -> {
            try {
                return fn.apply(req);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        };
    }

    /** Internal — distinguishes timeout vs other transport failures across the CompletableFuture boundary. */
    private static final class FetcherException extends RuntimeException {
        final boolean timedOut;
        final String reason;

        FetcherException(boolean timedOut, String reason) {
            super(reason);
            this.timedOut = timedOut;
            this.reason = reason;
        }
    }
}
