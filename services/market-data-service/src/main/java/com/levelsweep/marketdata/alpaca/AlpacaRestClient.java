package com.levelsweep.marketdata.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin REST client for the Alpaca Market Data v2 historical bars endpoint, used at
 * startup by {@link com.levelsweep.marketdata.live.LivePipeline} to pre-warm the
 * {@link com.levelsweep.marketdata.indicators.IndicatorEngine} EMA windows.
 *
 * <p>Endpoint shape:
 *
 * <pre>
 * GET https://data.alpaca.markets/v2/stocks/{symbol}/bars
 *     ?timeframe=2Min
 *     &amp;start={ISO}
 *     &amp;end={ISO}
 *     &amp;limit=200
 *     &amp;adjustment=raw
 *     &amp;feed=sip
 * Headers:
 *   APCA-API-KEY-ID:    {apiKey}
 *   APCA-API-SECRET-KEY:{secretKey}
 * </pre>
 *
 * <p>Tests inject a {@link Function} that fakes {@link HttpClient}; production uses
 * the JDK's default {@link HttpClient} configured here. We do NOT depend on any
 * Quarkus REST client extension — Quarkus' arc context isn't required and adding a
 * full RestClient build would be overkill for one endpoint.
 *
 * <p>This client returns bars in the order the Alpaca API delivers them — Alpaca's
 * documented contract is chronological ascending. The pre-warm path relies on this
 * (no re-sort is performed).
 */
@ApplicationScoped
public class AlpacaRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaRestClient.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final AlpacaConfig cfg;
    private final ObjectMapper mapper;
    private final Fetcher fetcher;

    @Inject
    public AlpacaRestClient(AlpacaConfig cfg) {
        this(cfg, new ObjectMapper(), defaultFetcher());
    }

    /** Test seam: inject a {@link Fetcher} returning canned JSON. */
    AlpacaRestClient(AlpacaConfig cfg, ObjectMapper mapper, Fetcher fetcher) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * Fetch up to {@code limit} historical bars for {@code symbol} between
     * {@code start} (inclusive) and {@code end} (exclusive). Returns an empty list on
     * any non-2xx response or empty {@code bars} array — never {@code null}, never
     * throws on transport / parse errors (the caller logs a warning and continues).
     */
    public List<Bar> fetchHistoricalBars(String symbol, Timeframe tf, Instant start, Instant end, int limit) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(tf, "tf");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (limit <= 0) {
            return Collections.emptyList();
        }

        String tfParam = mapTimeframe(tf);
        String url = cfg.dataUrl()
                + "/v2/stocks/"
                + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "/bars?timeframe="
                + tfParam
                + "&start="
                + URLEncoder.encode(DateTimeFormatter.ISO_INSTANT.format(start), StandardCharsets.UTF_8)
                + "&end="
                + URLEncoder.encode(DateTimeFormatter.ISO_INSTANT.format(end), StandardCharsets.UTF_8)
                + "&limit="
                + limit
                + "&adjustment=raw"
                + "&feed="
                + URLEncoder.encode(cfg.feed(), StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("APCA-API-KEY-ID", cfg.apiKey())
                .header("APCA-API-SECRET-KEY", cfg.secretKey())
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> resp = fetcher.fetch(req);
            int status = resp.statusCode();
            if (status / 100 != 2) {
                LOG.warn("alpaca historical bars non-2xx status={} symbol={} tf={}", status, symbol, tf);
                return Collections.emptyList();
            }
            return parseBars(resp.body(), symbol, tf);
        } catch (IOException e) {
            LOG.warn("alpaca historical bars IO error symbol={} tf={}: {}", symbol, tf, e.toString());
            return Collections.emptyList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("alpaca historical bars interrupted symbol={} tf={}", symbol, tf);
            return Collections.emptyList();
        } catch (RuntimeException e) {
            LOG.warn("alpaca historical bars unexpected error symbol={} tf={}: {}", symbol, tf, e.toString());
            return Collections.emptyList();
        }
    }

    /** Map the domain {@link Timeframe} onto the Alpaca query parameter form. */
    private static String mapTimeframe(Timeframe tf) {
        return switch (tf) {
            case ONE_MIN -> "1Min";
            case TWO_MIN -> "2Min";
            case FIFTEEN_MIN -> "15Min";
            case DAILY -> "1Day";
        };
    }

    private List<Bar> parseBars(String body, String symbol, Timeframe tf) {
        if (body == null || body.isBlank()) {
            return Collections.emptyList();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            LOG.warn("alpaca historical bars JSON parse error symbol={} tf={}: {}", symbol, tf, e.toString());
            return Collections.emptyList();
        }
        JsonNode barsNode = root.get("bars");
        if (barsNode == null || !barsNode.isArray() || barsNode.isEmpty()) {
            return Collections.emptyList();
        }
        List<Bar> out = new ArrayList<>(barsNode.size());
        Duration tfDuration = tf.duration();
        for (JsonNode b : barsNode) {
            try {
                Instant openTime = Instant.parse(b.get("t").asText());
                Instant closeTime = openTime.plus(tfDuration);
                BigDecimal open = new BigDecimal(b.get("o").asText());
                BigDecimal high = new BigDecimal(b.get("h").asText());
                BigDecimal low = new BigDecimal(b.get("l").asText());
                BigDecimal close = new BigDecimal(b.get("c").asText());
                long volume = b.has("v") ? b.get("v").asLong() : 0L;
                long ticks = b.has("n") ? b.get("n").asLong() : 0L;
                out.add(new Bar(symbol, tf, openTime, closeTime, open, high, low, close, volume, ticks));
            } catch (RuntimeException e) {
                // Skip a single malformed bar rather than aborting the whole pre-warm.
                LOG.warn("alpaca historical bar skipped (parse error symbol={} tf={}): {}", symbol, tf, e.toString());
            }
        }
        return out;
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
