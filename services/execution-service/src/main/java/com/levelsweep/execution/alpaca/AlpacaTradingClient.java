package com.levelsweep.execution.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin REST client for the Alpaca paper Trading API's
 * {@code POST /v2/orders} endpoint. Used by
 * {@link com.levelsweep.execution.ingest.OrderPlacingTradeRouter} to submit
 * a single options entry order per {@code TradeProposed} event.
 *
 * <p>Endpoint shape:
 *
 * <pre>
 * POST {alpaca.base-url}/v2/orders
 * Headers:
 *   APCA-API-KEY-ID:    {apiKey}
 *   APCA-API-SECRET-KEY:{secretKey}
 *   Content-Type:       application/json
 * Body:
 *   {
 *     "symbol":          "SPY260430C00595000",   // OCC contract symbol
 *     "qty":             "1",
 *     "side":            "buy",
 *     "type":            "limit",
 *     "time_in_force":   "day",
 *     "limit_price":     "1.24",
 *     "client_order_id": "OWNER:trade-abc123"     // idempotency key
 *   }
 * </pre>
 *
 * <p><b>Retry policy:</b> none on entry (architecture-spec §17.4). The signal
 * is time-sensitive — a second attempt against a wider spread is strictly
 * worse than the first attempt's slippage. {@code 4xx}/{@code 5xx} surface as
 * {@link OrderSubmission.Rejected}; transport / parse errors surface as
 * {@link OrderSubmission.FailedWithError}. Both are fatal for the trade's
 * entry path. The fill listener (S3) handles the post-acceptance lifecycle.
 *
 * <p><b>Idempotency:</b> Alpaca rejects duplicate {@code client_order_id} with
 * a 422 — so a stuck-pipeline replay never double-fires the same trade.
 *
 * <p><b>Logging:</b> INFO on submit (clientOrderId + contractSymbol + qty),
 * WARN on reject / IO error. The API key + secret are NEVER serialized into
 * any log line; the Jackson body excludes them by construction.
 *
 * <p><b>Test seam:</b> mirrors {@code AlpacaRestClient}'s {@link Fetcher} —
 * production gets the JDK {@link HttpClient}; tests inject a {@link Function}
 * returning canned responses.
 */
@ApplicationScoped
public class AlpacaTradingClient {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaTradingClient.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final Optional<String> apiKey;
    private final Optional<String> secretKey;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Fetcher fetcher;

    @Inject
    public AlpacaTradingClient(
            @ConfigProperty(name = "alpaca.base-url", defaultValue = "https://paper-api.alpaca.markets") String baseUrl,
            @ConfigProperty(name = "alpaca.api-key") Optional<String> apiKey,
            @ConfigProperty(name = "alpaca.secret-key") Optional<String> secretKey,
            Clock clock) {
        this(baseUrl, apiKey, secretKey, clock, new ObjectMapper(), defaultFetcher());
    }

    /** Test seam: inject a {@link Fetcher} returning canned JSON. */
    AlpacaTradingClient(
            String baseUrl,
            Optional<String> apiKey,
            Optional<String> secretKey,
            Clock clock,
            ObjectMapper mapper,
            Fetcher fetcher) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * Submit a single order. Single attempt — never retries. Any failure
     * (HTTP, transport, parse) is captured in the returned variant.
     */
    public OrderSubmission submit(OrderRequest req) {
        Objects.requireNonNull(req, "req");

        String body;
        try {
            body = mapper.writeValueAsString(toJsonBody(req));
        } catch (IOException e) {
            // ObjectMapper failure on a fully-validated record is essentially
            // impossible, but treating it as a transport failure keeps the
            // surface uniform.
            LOG.warn(
                    "alpaca trading client JSON body serialization failed clientOrderId={} contractSymbol={}: {}",
                    req.clientOrderId(),
                    req.contractSymbol(),
                    e.toString());
            return new OrderSubmission.FailedWithError(req.clientOrderId(), e.toString());
        }

        HttpRequest.Builder httpReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v2/orders"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (apiKey.isPresent() && !apiKey.get().isBlank()) {
            httpReq.header("APCA-API-KEY-ID", apiKey.get());
        } else {
            // Logging the missing key (not its value) is intentional: the
            // smoke test in dev where keys are absent must show WHY a 401 came
            // back.
            LOG.warn(
                    "alpaca trading client missing API key — request will be sent unauthenticated"
                            + " clientOrderId={}",
                    req.clientOrderId());
        }
        if (secretKey.isPresent() && !secretKey.get().isBlank()) {
            httpReq.header("APCA-API-SECRET-KEY", secretKey.get());
        }

        LOG.info(
                "alpaca trading client submit clientOrderId={} contractSymbol={} qty={} side={} type={} limitPrice={}",
                req.clientOrderId(),
                req.contractSymbol(),
                req.quantity(),
                req.side(),
                req.type(),
                req.limitPrice().map(Object::toString).orElse("-"));

        try {
            HttpResponse<String> resp = fetcher.fetch(httpReq.build());
            int status = resp.statusCode();
            if (status / 100 != 2) {
                String reason = truncate(resp.body(), 512);
                LOG.warn(
                        "alpaca trading client rejected clientOrderId={} contractSymbol={} status={} body={}",
                        req.clientOrderId(),
                        req.contractSymbol(),
                        status,
                        reason);
                return new OrderSubmission.Rejected(req.clientOrderId(), status, reason);
            }
            return parseSubmitted(req, resp.body());
        } catch (IOException e) {
            LOG.warn(
                    "alpaca trading client IO error clientOrderId={} contractSymbol={}: {}",
                    req.clientOrderId(),
                    req.contractSymbol(),
                    e.toString());
            return new OrderSubmission.FailedWithError(req.clientOrderId(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn(
                    "alpaca trading client interrupted clientOrderId={} contractSymbol={}",
                    req.clientOrderId(),
                    req.contractSymbol());
            return new OrderSubmission.FailedWithError(req.clientOrderId(), "interrupted");
        } catch (RuntimeException e) {
            LOG.warn(
                    "alpaca trading client unexpected error clientOrderId={} contractSymbol={}: {}",
                    req.clientOrderId(),
                    req.contractSymbol(),
                    e.toString());
            return new OrderSubmission.FailedWithError(req.clientOrderId(), e.toString());
        }
    }

    private OrderSubmission parseSubmitted(OrderRequest req, String body) {
        if (body == null || body.isBlank()) {
            LOG.warn(
                    "alpaca trading client 2xx with empty body clientOrderId={}; treating as failure",
                    req.clientOrderId());
            return new OrderSubmission.FailedWithError(req.clientOrderId(), "empty 2xx response body");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            LOG.warn(
                    "alpaca trading client 2xx response parse error clientOrderId={}: {}",
                    req.clientOrderId(),
                    e.toString());
            return new OrderSubmission.FailedWithError(req.clientOrderId(), e.toString());
        }
        JsonNode idNode = root.get("id");
        JsonNode statusNode = root.get("status");
        if (idNode == null || idNode.isNull() || idNode.asText().isBlank()) {
            LOG.warn(
                    "alpaca trading client 2xx response missing id clientOrderId={} body={}",
                    req.clientOrderId(),
                    truncate(body, 256));
            return new OrderSubmission.FailedWithError(req.clientOrderId(), "2xx response missing 'id' field");
        }
        String alpacaOrderId = idNode.asText();
        String status = statusNode != null && !statusNode.isNull() ? statusNode.asText("accepted") : "accepted";
        return new OrderSubmission.Submitted(alpacaOrderId, req.clientOrderId(), status, clock.instant());
    }

    /**
     * Serialize the request to a {@link Map} so {@link ObjectMapper} can drive
     * the JSON body without a one-off DTO. Insertion order is preserved
     * (LinkedHashMap) for deterministic on-the-wire bytes — the replay-parity
     * harness compares request bodies field-for-field.
     */
    private static Map<String, Object> toJsonBody(OrderRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", req.contractSymbol());
        body.put("qty", Integer.toString(req.quantity()));
        body.put("side", req.side());
        body.put("type", req.type());
        body.put("time_in_force", req.timeInForce());
        req.limitPrice().ifPresent(p -> body.put("limit_price", p.toPlainString()));
        body.put("client_order_id", req.clientOrderId());
        return body;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Minimal HTTP seam — abstracts {@link HttpClient} so tests can stub it. */
    @FunctionalInterface
    interface Fetcher {
        HttpResponse<String> fetch(HttpRequest request) throws IOException, InterruptedException;
    }

    private static Fetcher defaultFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
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
}
