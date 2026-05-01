package com.levelsweep.decision.strike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.options.OptionContract;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin REST client for the Alpaca Options Snapshots endpoint, used by
 * {@link StrikeSelectorService} to pull a 0DTE chain on demand for the
 * 0DTE strike selector.
 *
 * <p>Endpoint shape (per Alpaca v1beta1):
 *
 * <pre>
 * GET https://data.alpaca.markets/v1beta1/options/snapshots/{underlying}
 * Headers:
 *   APCA-API-KEY-ID:    {apiKey}
 *   APCA-API-SECRET-KEY:{secretKey}
 * </pre>
 *
 * <p>Response shape (relevant fields only):
 *
 * <pre>
 * {
 *   "snapshots": {
 *     "SPY250130C00600000": {
 *       "latestQuote": {"bp":1.51,"bs":100,"ap":1.53,"as":50,...},
 *       "latestTrade": {"p":1.52,...},
 *       "impliedVolatility": 0.25,
 *       "greeks": {"delta":0.52,...},
 *       "openInterest": 1500,
 *       "dailyBar":   {"v":12345,...}
 *     },
 *     ...
 *   }
 * }
 * </pre>
 *
 * <p>Modeled the same way as
 * {@code com.levelsweep.marketdata.alpaca.AlpacaRestClient} — a package-private
 * {@link Fetcher} seam abstracts {@link HttpClient} so unit tests inject canned
 * JSON. Production uses the JDK default. Errors degrade to an empty list and a
 * WARN log; the selector treats that the same as an empty chain.
 */
@ApplicationScoped
public class AlpacaOptionsClient {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaOptionsClient.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;
    private final ObjectMapper mapper;
    private final Fetcher fetcher;

    @Inject
    public AlpacaOptionsClient(
            @ConfigProperty(name = "decision.strike.alpaca-options-url", defaultValue = "https://data.alpaca.markets")
                    String baseUrl,
            @ConfigProperty(name = "alpaca.api-key", defaultValue = "") String apiKey,
            @ConfigProperty(name = "alpaca.secret-key", defaultValue = "") String secretKey) {
        this(baseUrl, apiKey, secretKey, new ObjectMapper(), defaultFetcher());
    }

    /** Test seam: inject a {@link Fetcher} returning canned JSON. */
    AlpacaOptionsClient(String baseUrl, String apiKey, String secretKey, ObjectMapper mapper, Fetcher fetcher) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * Fetch the full options chain for {@code underlying} as a list of
     * {@link OptionContract} records. Returns an empty list on any non-2xx
     * response, transport error, or parse error — never {@code null}, never
     * throws on transport / parse errors.
     *
     * <p>Symbols whose JSON shape is unparseable (missing quote, malformed OCC
     * symbol, missing bid/ask, etc.) are silently skipped: a single bad entry
     * does not poison the whole chain.
     */
    public List<OptionContract> fetchChain(String underlying) {
        Objects.requireNonNull(underlying, "underlying");
        String url = baseUrl + "/v1beta1/options/snapshots/" + URLEncoder.encode(underlying, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("APCA-API-KEY-ID", apiKey)
                .header("APCA-API-SECRET-KEY", secretKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> resp = fetcher.fetch(req);
            int status = resp.statusCode();
            if (status / 100 != 2) {
                LOG.warn("alpaca options chain non-2xx status={} underlying={}", status, underlying);
                return Collections.emptyList();
            }
            return parseChain(resp.body(), underlying);
        } catch (IOException e) {
            LOG.warn("alpaca options chain IO error underlying={}: {}", underlying, e.toString());
            return Collections.emptyList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("alpaca options chain interrupted underlying={}", underlying);
            return Collections.emptyList();
        } catch (RuntimeException e) {
            LOG.warn("alpaca options chain unexpected error underlying={}: {}", underlying, e.toString());
            return Collections.emptyList();
        }
    }

    private List<OptionContract> parseChain(String body, String underlying) {
        if (body == null || body.isBlank()) {
            return Collections.emptyList();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            LOG.warn("alpaca options chain JSON parse error underlying={}: {}", underlying, e.toString());
            return Collections.emptyList();
        }
        JsonNode snapshots = root.get("snapshots");
        if (snapshots == null || !snapshots.isObject() || snapshots.isEmpty()) {
            return Collections.emptyList();
        }
        List<OptionContract> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = snapshots.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String occSymbol = entry.getKey();
            JsonNode snap = entry.getValue();
            try {
                OptionContract contract = toContract(occSymbol, snap);
                if (contract != null) {
                    out.add(contract);
                }
            } catch (RuntimeException e) {
                // Single malformed entry — skip rather than abort the whole chain.
                LOG.warn("alpaca options snapshot skipped (parse error symbol={}): {}", occSymbol, e.toString());
            }
        }
        return out;
    }

    private static OptionContract toContract(String occSymbol, JsonNode snap) {
        if (snap == null || !snap.isObject()) return null;
        JsonNode quote = snap.get("latestQuote");
        if (quote == null || !quote.isObject()) {
            // No live quote → cannot evaluate liquidity; skip.
            return null;
        }
        JsonNode bidNode = quote.get("bp");
        JsonNode askNode = quote.get("ap");
        if (bidNode == null || askNode == null) {
            return null;
        }
        BigDecimal bid = new BigDecimal(bidNode.asText());
        BigDecimal ask = new BigDecimal(askNode.asText());
        // Defensive: Alpaca occasionally returns inverted/zero quotes during
        // the open. Reject so the selector doesn't divide by zero or sort by
        // negative spread.
        if (bid.signum() < 0 || ask.signum() < 0 || ask.compareTo(bid) < 0) {
            return null;
        }

        OccSymbolParser.Parsed parsed = OccSymbolParser.parse(occSymbol);

        Optional<Integer> openInterest = optionalInt(snap.get("openInterest"));
        Optional<Integer> volume = optionalInt(
                snap.has("dailyBar") && snap.get("dailyBar").isObject()
                        ? snap.get("dailyBar").get("v")
                        : null);
        Optional<BigDecimal> iv = optionalBigDecimal(snap.get("impliedVolatility"));
        Optional<BigDecimal> delta = Optional.empty();
        JsonNode greeks = snap.get("greeks");
        if (greeks != null && greeks.isObject()) {
            delta = optionalBigDecimal(greeks.get("delta"));
        }

        return new OptionContract(
                occSymbol,
                parsed.underlying(),
                parsed.expiry(),
                parsed.strike(),
                parsed.side(),
                bid,
                ask,
                openInterest,
                volume,
                iv,
                delta);
    }

    private static Optional<Integer> optionalInt(JsonNode node) {
        if (node == null || node.isNull()) return Optional.empty();
        if (node.isNumber()) return Optional.of(node.intValue());
        try {
            return Optional.of(Integer.parseInt(node.asText()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> optionalBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(node.asText()));
        } catch (NumberFormatException e) {
            return Optional.empty();
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
