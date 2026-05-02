package com.levelsweep.execution.trail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin REST client for the Alpaca options snapshot endpoint:
 *
 * <pre>
 * GET {alpaca.base-url}/v1beta1/options/snapshots/{contractSymbol}
 * Headers:
 *   APCA-API-KEY-ID:    {apiKey}
 *   APCA-API-SECRET-KEY:{secretKey}
 *   Accept:             application/json
 * </pre>
 *
 * <p>Used by {@link TrailPollScheduler} to read the latest NBBO mid-point
 * once per second per held trade (per ADR-0004 + ADR-0005 §5). Failure
 * modes (4xx, 5xx, IOException, parse error) all return
 * {@link Optional#empty()} and log WARN — the scheduler treats absent
 * snapshots as a held-state no-op so a transient broker hiccup does not
 * spuriously fire an exit.
 *
 * <p><b>Credential safety:</b> the API key + secret are sent as HTTP headers
 * but NEVER serialized into any log line. The structured-logging tests
 * assert this explicitly.
 *
 * <p><b>Test seam:</b> mirrors {@code AlpacaTradingClient}'s {@link Fetcher}
 * pattern — production gets a real {@link HttpClient}; tests inject a
 * {@link Function} returning canned responses.
 */
@ApplicationScoped
public class AlpacaQuotesClient {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaQuotesClient.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final Optional<String> apiKey;
    private final Optional<String> secretKey;
    private final ObjectMapper mapper;
    private final Fetcher fetcher;

    @Inject
    public AlpacaQuotesClient(
            @ConfigProperty(name = "alpaca.base-url", defaultValue = "https://paper-api.alpaca.markets") String baseUrl,
            @ConfigProperty(name = "alpaca.api-key") Optional<String> apiKey,
            @ConfigProperty(name = "alpaca.secret-key") Optional<String> secretKey) {
        this(baseUrl, apiKey, secretKey, new ObjectMapper(), defaultFetcher());
    }

    /** Test seam: inject a {@link Fetcher} returning canned JSON. */
    AlpacaQuotesClient(
            String baseUrl, Optional<String> apiKey, Optional<String> secretKey, ObjectMapper mapper, Fetcher fetcher) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * Fetch one NBBO snapshot for {@code contractSymbol}. Returns
     * {@link Optional#empty()} on any failure path (HTTP non-2xx, IO error,
     * malformed JSON, missing fields).
     */
    public Optional<NbboSnapshot> snapshot(String contractSymbol) {
        Objects.requireNonNull(contractSymbol, "contractSymbol");

        String encoded = URLEncoder.encode(contractSymbol, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/v1beta1/options/snapshots/" + encoded);
        HttpRequest.Builder httpReq = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        apiKey.filter(k -> !k.isBlank()).ifPresent(k -> httpReq.header("APCA-API-KEY-ID", k));
        secretKey.filter(k -> !k.isBlank()).ifPresent(k -> httpReq.header("APCA-API-SECRET-KEY", k));

        if (LOG.isDebugEnabled()) {
            LOG.debug("alpaca quotes client GET contractSymbol={}", contractSymbol);
        }

        try {
            HttpResponse<String> resp = fetcher.fetch(httpReq.build());
            int status = resp.statusCode();
            if (status / 100 != 2) {
                LOG.warn(
                        "alpaca quotes client non-2xx contractSymbol={} status={} bodyLen={}",
                        contractSymbol,
                        status,
                        resp.body() == null ? 0 : resp.body().length());
                return Optional.empty();
            }
            return parse(contractSymbol, resp.body());
        } catch (IOException e) {
            LOG.warn("alpaca quotes client IO error contractSymbol={}: {}", contractSymbol, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("alpaca quotes client interrupted contractSymbol={}", contractSymbol);
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.warn("alpaca quotes client unexpected error contractSymbol={}: {}", contractSymbol, e.toString());
            return Optional.empty();
        }
    }

    private Optional<NbboSnapshot> parse(String contractSymbol, String body) {
        if (body == null || body.isBlank()) {
            LOG.warn("alpaca quotes client 2xx empty body contractSymbol={}", contractSymbol);
            return Optional.empty();
        }
        try {
            JsonNode root;
            try {
                root = mapper.readTree(body);
            } catch (IOException ioe) {
                LOG.warn("alpaca quotes client parse error contractSymbol={}: {}", contractSymbol, ioe.toString());
                return Optional.empty();
            }
            // Alpaca v1beta1 snapshot shape:
            //   { "snapshots": { "<symbol>": { "latestQuote": { "bp": .., "ap": .., "t": "..." } } } }
            // OR (single-symbol path):
            //   { "snapshot": { "latestQuote": { "bp": .., "ap": .., "t": "..." } } }
            JsonNode quote = root.path("latestQuote");
            if (quote.isMissingNode() || quote.isNull()) {
                quote = root.path("snapshot").path("latestQuote");
            }
            if (quote.isMissingNode() || quote.isNull()) {
                quote = root.path("snapshots").path(contractSymbol).path("latestQuote");
            }
            if (quote.isMissingNode() || quote.isNull()) {
                LOG.warn("alpaca quotes client missing latestQuote contractSymbol={}", contractSymbol);
                return Optional.empty();
            }
            JsonNode bp = quote.get("bp");
            JsonNode ap = quote.get("ap");
            JsonNode ts = quote.get("t");
            if (bp == null || ap == null || ts == null) {
                LOG.warn(
                        "alpaca quotes client malformed latestQuote contractSymbol={} hasBp={} hasAp={} hasT={}",
                        contractSymbol,
                        bp != null,
                        ap != null,
                        ts != null);
                return Optional.empty();
            }
            BigDecimal bid = new BigDecimal(bp.asText());
            BigDecimal ask = new BigDecimal(ap.asText());
            Instant timestamp = Instant.parse(ts.asText());
            return Optional.of(new NbboSnapshot(contractSymbol, bid, ask, timestamp));
        } catch (RuntimeException e) {
            LOG.warn("alpaca quotes client parse error contractSymbol={}: {}", contractSymbol, e.toString());
            return Optional.empty();
        }
    }

    /** NBBO snapshot — bid + ask + timestamp. Mid is computed by the caller (TrailPollScheduler). */
    public record NbboSnapshot(String contractSymbol, BigDecimal bid, BigDecimal ask, Instant timestamp) {
        public NbboSnapshot {
            Objects.requireNonNull(contractSymbol, "contractSymbol");
            Objects.requireNonNull(bid, "bid");
            Objects.requireNonNull(ask, "ask");
            Objects.requireNonNull(timestamp, "timestamp");
            if (bid.signum() < 0) {
                throw new IllegalArgumentException("bid must be non-negative: " + bid);
            }
            if (ask.compareTo(bid) < 0) {
                throw new IllegalArgumentException("ask must be >= bid: bid=" + bid + " ask=" + ask);
            }
        }

        /** Midpoint with 4-decimal precision; safe for trail UPL math. */
        public BigDecimal mid() {
            return bid.add(ask).divide(new BigDecimal("2"), 4, java.math.RoundingMode.HALF_UP);
        }
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
