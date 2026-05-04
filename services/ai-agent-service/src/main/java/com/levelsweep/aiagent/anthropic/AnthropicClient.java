package com.levelsweep.aiagent.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.aiagent.connection.AnthropicConnectionMonitor;
import com.levelsweep.aiagent.connection.ConnectionMonitor;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hand-rolled HTTP client for the Anthropic Messages API
 * ({@code POST /v1/messages}). Mirrors {@code AlpacaTradingClient}'s
 * shape (ADR-0006, ADR-0004 reference): JDK {@link HttpClient}, sealed
 * outcome via {@link AnthropicResponse}, package-private {@link Fetcher}
 * test seam.
 *
 * <p>Why hand-rolled (per ADR-0006):
 *
 * <ul>
 *   <li>Fewer transitive deps than LangChain4J / Spring AI</li>
 *   <li>Full control over prompt-caching headers
 *       ({@code anthropic-beta: prompt-caching-2024-07-31})</li>
 *   <li>Deterministic mocks for the replay-parity story (the {@link Fetcher}
 *       seam returns fixture JSON in tests)</li>
 *   <li>Mirrors the codebase's existing Alpaca client pattern (one place to
 *       add CB / metrics / DLQ in Phase 7)</li>
 * </ul>
 *
 * <p><b>Cost cap (HARD pre-flight)</b>: per architecture-spec §4.9 +
 * {@code ai-prompt-management} skill rule #4, this client checks
 * {@link DailyCostTracker#wouldExceedCap} BEFORE making the HTTP call. If
 * the projected cost would push the (tenant, role, today) bucket over its
 * configured cap, the call is short-circuited with
 * {@link AnthropicResponse.CostCapBreached} and no HTTP traffic is emitted.
 *
 * <p><b>Retry policy</b> (architecture-spec §4.9 + §17.4): the
 * {@code retryEnabled} parameter on the request method controls retry. Default
 * is NO retry — Sentinel is fail-open and the latency budget is binary
 * (one shot or default ALLOW). Narrator/Reviewer in S2/S3 will pass
 * {@code true} which enables a single bounded retry on 429/529 only.
 *
 * <p><b>Logging</b>: INFO on submit (role + model + clientRequestId), WARN on
 * non-2xx + transport failures + cap breach. The {@code x-api-key} header
 * value is NEVER serialized into any log line (architecture-spec §6 +
 * {@code trading-system-guardrails} skill MUST NOT #4). The Jackson body
 * excludes credentials by construction.
 *
 * <p><b>Audit</b>: this client does NOT write audit rows itself — that's
 * {@link com.levelsweep.aiagent.audit.AiCallAuditWriter}'s job. The wired
 * pattern in S2/S3 is: caller → {@code AnthropicClient.submit} → match on
 * variant → {@code AiCallAuditWriter.record}. Keeping audit out of the
 * client keeps the client testable without Mongo.
 */
@ApplicationScoped
public class AnthropicClient {

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicClient.class);

    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String PROMPT_CACHING_BETA = "prompt-caching-2024-07-31";
    private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(35);

    private final String baseUrl;
    private final Optional<String> apiKey;
    private final boolean promptCachingEnabled;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Fetcher fetcher;
    private final DailyCostTracker costTracker;
    private final AnthropicConnectionMonitor connectionMonitor;
    private final AiAgentMetrics metrics;

    @Inject
    public AnthropicClient(
            @ConfigProperty(name = "anthropic.base-url", defaultValue = "https://api.anthropic.com") String baseUrl,
            @ConfigProperty(name = "anthropic.api-key") Optional<String> apiKey,
            @ConfigProperty(name = "anthropic.enable-prompt-caching", defaultValue = "true")
                    boolean promptCachingEnabled,
            Clock clock,
            DailyCostTracker costTracker,
            AnthropicConnectionMonitor connectionMonitor,
            AiAgentMetrics metrics) {
        this(
                baseUrl,
                apiKey,
                promptCachingEnabled,
                clock,
                new ObjectMapper(),
                defaultFetcher(),
                costTracker,
                connectionMonitor,
                metrics);
    }

    /** Test seam — inject a {@link Fetcher} returning canned JSON. */
    AnthropicClient(
            String baseUrl,
            Optional<String> apiKey,
            boolean promptCachingEnabled,
            Clock clock,
            ObjectMapper mapper,
            Fetcher fetcher,
            DailyCostTracker costTracker,
            AnthropicConnectionMonitor connectionMonitor,
            AiAgentMetrics metrics) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.promptCachingEnabled = promptCachingEnabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.costTracker = Objects.requireNonNull(costTracker, "costTracker");
        this.connectionMonitor = Objects.requireNonNull(connectionMonitor, "connectionMonitor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        // Publish the initial gauge value so the meter is registered at boot
        // (alert KQL needs at least one sample to ever evaluate).
        this.metrics.recordConnectionState(connectionMonitor.dependency(), connectionMonitor.state());
    }

    /**
     * Submit one Anthropic Messages API call. Single attempt by default
     * (Sentinel-class). Caller passes {@code retryEnabled=true} (S2/S3) for
     * Narrator/Reviewer, which currently still resolves to single-attempt
     * here — the retry is wired in the same pull request as those callers
     * land so this S1 ships without bound to a runtime retry library.
     */
    public AnthropicResponse submit(AnthropicRequest request) {
        return submit(request, /* retryEnabled */ false);
    }

    /** Variant carrying explicit retry preference (Narrator/Reviewer wire true). */
    public AnthropicResponse submit(AnthropicRequest request, boolean retryEnabled) {
        Objects.requireNonNull(request, "request");
        // Deterministic per-call id — survives across audit logs + traces. We
        // generate UUIDv4 here; replay tests fix the seed via the test seam if
        // they need stable ids.
        String clientRequestId = UUID.randomUUID().toString();
        long startNs = System.nanoTime();

        // 1. HARD pre-flight cost cap — never makes an HTTP call if the
        //    projected cost would push the (tenant, role, today) bucket over.
        if (costTracker.wouldExceedCap(
                request.tenantId(), request.role(), costTracker.today(), request.projectedCostUsd())) {
            BigDecimal current = costTracker.currentSpend(request.tenantId(), request.role(), costTracker.today());
            BigDecimal cap = costTracker.capFor(request.role());
            long latencyMs = elapsedMs(startNs);
            LOG.warn(
                    "anthropic client cost cap breached pre-flight tenantId={} role={} model={}"
                            + " capUsd={} currentSpendUsd={} projectedCostUsd={} clientRequestId={}",
                    request.tenantId(),
                    request.role(),
                    request.model(),
                    cap,
                    current,
                    request.projectedCostUsd(),
                    clientRequestId);
            return new AnthropicResponse.CostCapBreached(
                    clientRequestId,
                    request.role(),
                    request.model(),
                    latencyMs,
                    cap,
                    current,
                    request.projectedCostUsd());
        }

        // 2. Connection FSM short-circuit (ADR-0007 §3 cb_open). UNHEALTHY +
        //    inside the probe interval → return TransportFailure without an
        //    HTTP call. UNHEALTHY + past the probe interval → admit a single
        //    probe (transitions UNHEALTHY → RECOVERING) then continue.
        if (connectionMonitor.shouldShortCircuit()) {
            long latencyMs = elapsedMs(startNs);
            LOG.warn(
                    "anthropic client circuit-breaker open role={} model={} clientRequestId={} state={}",
                    request.role(),
                    request.model(),
                    clientRequestId,
                    connectionMonitor.state());
            return new AnthropicResponse.TransportFailure(
                    clientRequestId, request.role(), request.model(), latencyMs, "circuit_breaker_open");
        }
        if (connectionMonitor.state() == ConnectionMonitor.State.UNHEALTHY) {
            // Probe interval has elapsed (shouldShortCircuit returned false but
            // state is still UNHEALTHY). Transition to RECOVERING for this one
            // probe; the response handler will set HEALTHY on success or hold
            // UNHEALTHY on failure.
            connectionMonitor.admitProbe();
            metrics.recordConnectionState(connectionMonitor.dependency(), connectionMonitor.state());
        }

        // 3. Build + send the HTTP request.
        String body;
        try {
            body = mapper.writeValueAsString(toJsonBody(request));
        } catch (IOException e) {
            // Should not happen for fully-validated records. Treat as transport.
            return new AnthropicResponse.TransportFailure(
                    clientRequestId, request.role(), request.model(), elapsedMs(startNs), e.toString());
        }

        HttpRequest.Builder httpReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MESSAGES_PATH))
                .timeout(DEFAULT_HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (promptCachingEnabled) {
            httpReq.header("anthropic-beta", PROMPT_CACHING_BETA);
        }

        if (apiKey.isPresent() && !apiKey.get().isBlank()) {
            httpReq.header("x-api-key", apiKey.get());
        } else {
            LOG.warn(
                    "anthropic client missing api-key — request will be sent unauthenticated"
                            + " role={} model={} clientRequestId={}",
                    request.role(),
                    request.model(),
                    clientRequestId);
        }

        LOG.info(
                "anthropic client submit role={} model={} maxTokens={} retryEnabled={} clientRequestId={}",
                request.role(),
                request.model(),
                request.maxTokens(),
                retryEnabled,
                clientRequestId);

        AnthropicResponse outcome;
        try {
            HttpResponse<String> resp = fetcher.fetch(httpReq.build());
            int status = resp.statusCode();
            long latencyMs = elapsedMs(startNs);
            outcome = mapResponse(request, clientRequestId, latencyMs, status, resp.body());
        } catch (IOException e) {
            LOG.warn(
                    "anthropic client IO error role={} model={} clientRequestId={}: {}",
                    request.role(),
                    request.model(),
                    clientRequestId,
                    e.toString());
            outcome = new AnthropicResponse.TransportFailure(
                    clientRequestId, request.role(), request.model(), elapsedMs(startNs), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = new AnthropicResponse.TransportFailure(
                    clientRequestId, request.role(), request.model(), elapsedMs(startNs), "interrupted");
        } catch (RuntimeException e) {
            LOG.warn(
                    "anthropic client unexpected error role={} model={} clientRequestId={}: {}",
                    request.role(),
                    request.model(),
                    clientRequestId,
                    e.toString());
            outcome = new AnthropicResponse.TransportFailure(
                    clientRequestId, request.role(), request.model(), elapsedMs(startNs), e.toString());
        }
        applyOutcomeToConnectionFsm(outcome);
        return outcome;
    }

    /**
     * Translate an outcome variant to a Connection FSM signal.
     * {@link AnthropicResponse.Success} clears the error window;
     * {@link AnthropicResponse.TransportFailure} and
     * {@link AnthropicResponse.Overloaded} (5xx / 503) record errors.
     * RateLimited / InvalidRequest / CostCapBreached are NOT connection-health
     * signals (per the deliverable spec) — Anthropic is responsive and well,
     * the issue is request- or budget-level. Push the post-mutation state to
     * the gauge so alert #11 sees the transition immediately.
     */
    private void applyOutcomeToConnectionFsm(AnthropicResponse outcome) {
        if (outcome instanceof AnthropicResponse.Success) {
            connectionMonitor.recordSuccess();
        } else if (outcome instanceof AnthropicResponse.TransportFailure tf) {
            connectionMonitor.recordError(new RuntimeException(tf.exceptionMessage()));
        } else if (outcome instanceof AnthropicResponse.Overloaded ol) {
            connectionMonitor.recordError(new RuntimeException("http " + ol.httpStatus()));
        } else {
            return;
        }
        metrics.recordConnectionState(connectionMonitor.dependency(), connectionMonitor.state());
    }

    /** Map an HTTP outcome to an {@link AnthropicResponse} variant. */
    private AnthropicResponse mapResponse(
            AnthropicRequest request, String clientRequestId, long latencyMs, int status, String body) {
        if (status / 100 == 2) {
            return parseSuccess(request, clientRequestId, latencyMs, body);
        }
        String snippet = truncate(body, 512);
        return switch (status) {
            case 429 -> new AnthropicResponse.RateLimited(
                    clientRequestId, request.role(), request.model(), latencyMs, snippet);
            case 503, 529 -> new AnthropicResponse.Overloaded(
                    clientRequestId, request.role(), request.model(), latencyMs, status, snippet);
            default -> {
                if (status >= 400 && status < 500) {
                    LOG.warn(
                            "anthropic client invalid request role={} model={} status={} clientRequestId={} body={}",
                            request.role(),
                            request.model(),
                            status,
                            clientRequestId,
                            snippet);
                    yield new AnthropicResponse.InvalidRequest(
                            clientRequestId, request.role(), request.model(), latencyMs, status, snippet);
                }
                if (status >= 500) {
                    yield new AnthropicResponse.Overloaded(
                            clientRequestId, request.role(), request.model(), latencyMs, status, snippet);
                }
                // 1xx/3xx — extremely unusual on the Messages API; treat as transport.
                yield new AnthropicResponse.TransportFailure(
                        clientRequestId,
                        request.role(),
                        request.model(),
                        latencyMs,
                        "unexpected http status: " + status);
            }
        };
    }

    /** Parse a 2xx body into {@link AnthropicResponse.Success}. */
    private AnthropicResponse parseSuccess(
            AnthropicRequest request, String clientRequestId, long latencyMs, String body) {
        if (body == null || body.isBlank()) {
            return new AnthropicResponse.TransportFailure(
                    clientRequestId, request.role(), request.model(), latencyMs, "empty 2xx response body");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            return new AnthropicResponse.TransportFailure(
                    clientRequestId, request.role(), request.model(), latencyMs, e.toString());
        }
        // Concatenate text content blocks; collect tool_use names.
        StringBuilder text = new StringBuilder();
        List<String> toolCalls = new ArrayList<>();
        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    text.append(block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    toolCalls.add(block.path("name").asText(""));
                }
            }
        }
        // Token accounting per the Anthropic API:
        //   usage.input_tokens         — uncached input
        //   usage.output_tokens        — completion
        //   usage.cache_read_input_tokens — cached read (10% rate)
        //   usage.cache_creation_input_tokens — cached write (1.25× rate but
        //       Phase 4 audit treats as input_tokens; pricing refinement is
        //       a Phase 7 audit-tooling enhancement, not a hot-path change)
        JsonNode usage = root.path("usage");
        int inputTokens = usage.path("input_tokens").asInt(0);
        int outputTokens = usage.path("output_tokens").asInt(0);
        int cachedTokens = usage.path("cache_read_input_tokens").asInt(0);
        BigDecimal costUsd = CostCalculator.knowsModel(request.model())
                ? CostCalculator.compute(request.model(), inputTokens, outputTokens, cachedTokens)
                : BigDecimal.ZERO;
        return new AnthropicResponse.Success(
                clientRequestId,
                request.role(),
                request.model(),
                latencyMs,
                text.toString(),
                toolCalls,
                inputTokens,
                outputTokens,
                cachedTokens,
                costUsd);
    }

    /**
     * Serialize the request to a {@link Map} so {@link ObjectMapper} drives
     * the JSON body without a one-off DTO. Insertion order preserved
     * (LinkedHashMap) for deterministic on-the-wire bytes — the prompt-hash
     * canonicalization in {@link com.levelsweep.aiagent.audit.PromptHasher}
     * does not depend on this, but keeping the bytes stable simplifies
     * replay-test capture comparison.
     */
    private static Map<String, Object> toJsonBody(AnthropicRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("max_tokens", req.maxTokens());
        body.put("temperature", req.temperature());
        body.put("system", req.systemPrompt());
        // Messages — wrap content as a single text block, the canonical Anthropic shape.
        List<Map<String, Object>> messages = new ArrayList<>(req.messages().size());
        for (AnthropicMessage m : req.messages()) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            msg.put("content", List.of(Map.of("type", "text", "text", m.content())));
            messages.add(msg);
        }
        body.put("messages", messages);
        // Tools — only emit if non-empty (Anthropic accepts the omission).
        if (!req.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>(req.tools().size());
            for (AnthropicTool t : req.tools()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", t.name());
                tool.put("description", t.description());
                tool.put("input_schema", t.inputSchema());
                tools.add(tool);
            }
            body.put("tools", tools);
        }
        return body;
    }

    private static long elapsedMs(long startNs) {
        return Duration.ofNanos(System.nanoTime() - startNs).toMillis();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Test seam: function from {@link HttpRequest} to {@link HttpResponse}. */
    @FunctionalInterface
    public interface Fetcher {
        HttpResponse<String> fetch(HttpRequest request) throws IOException, InterruptedException;
    }

    private static Fetcher defaultFetcher() {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(DEFAULT_HTTP_TIMEOUT).build();
        return req -> client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    /** Currently unused — placeholder for the shared-clock observability of latency budget. */
    Clock clock() {
        return clock;
    }
}
