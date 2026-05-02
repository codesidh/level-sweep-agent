package com.levelsweep.execution.trail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class AlpacaQuotesClientTest {

    private static final String SYMBOL = "SPY260430C00595000";
    private static final String API_KEY = "AKIA-LIVE-KEY";
    private static final String SECRET = "secret-bytes-very-private";

    private static AlpacaQuotesClient withFetcher(AlpacaQuotesClient.Fetcher fetcher) {
        return new AlpacaQuotesClient(
                "https://paper-api.alpaca.markets",
                Optional.of(API_KEY),
                Optional.of(SECRET),
                new ObjectMapper(),
                fetcher);
    }

    @Test
    void parsesValidLatestQuote() {
        String body = "{\"symbol\":\"" + SYMBOL
                + "\",\"latestQuote\":{\"bp\":\"1.20\",\"ap\":\"1.25\",\"t\":\"2026-04-30T15:00:00Z\"}}";
        AlpacaQuotesClient client = withFetcher(req -> stubResponse(req, 200, body));

        Optional<AlpacaQuotesClient.NbboSnapshot> snap = client.snapshot(SYMBOL);

        assertThat(snap).isPresent();
        assertThat(snap.get().bid()).isEqualByComparingTo("1.20");
        assertThat(snap.get().ask()).isEqualByComparingTo("1.25");
        assertThat(snap.get().mid()).isEqualByComparingTo(new BigDecimal("1.225"));
        assertThat(snap.get().timestamp()).isEqualTo(Instant.parse("2026-04-30T15:00:00Z"));
    }

    @Test
    void parsesNestedSnapshotsShape() {
        String body = "{\"snapshots\":{\"" + SYMBOL
                + "\":{\"latestQuote\":{\"bp\":\"1.20\",\"ap\":\"1.25\",\"t\":\"2026-04-30T15:00:00Z\"}}}}";
        AlpacaQuotesClient client = withFetcher(req -> stubResponse(req, 200, body));
        assertThat(client.snapshot(SYMBOL)).isPresent();
    }

    @Test
    void returnsEmptyOnNon2xx() {
        AlpacaQuotesClient client = withFetcher(req -> stubResponse(req, 404, "{\"error\":\"not found\"}"));
        assertThat(client.snapshot(SYMBOL)).isEmpty();
    }

    @Test
    void returnsEmptyOnIoException() {
        AlpacaQuotesClient.Fetcher fetcher = AlpacaQuotesClient.fromFunction(req -> {
            throw new UncheckedIOException(new IOException("connect timeout"));
        });
        AlpacaQuotesClient client = new AlpacaQuotesClient(
                "https://paper-api.alpaca.markets",
                Optional.of(API_KEY),
                Optional.of(SECRET),
                new ObjectMapper(),
                fetcher);
        assertThat(client.snapshot(SYMBOL)).isEmpty();
    }

    @Test
    void returnsEmptyOnMalformedJson() {
        AlpacaQuotesClient client = withFetcher(req -> stubResponse(req, 200, "{not json"));
        assertThat(client.snapshot(SYMBOL)).isEmpty();
    }

    @Test
    void returnsEmptyOnMissingLatestQuote() {
        AlpacaQuotesClient client = withFetcher(req -> stubResponse(req, 200, "{\"symbol\":\"" + SYMBOL + "\"}"));
        assertThat(client.snapshot(SYMBOL)).isEmpty();
    }

    @Test
    void sendsAuthHeaders() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AlpacaQuotesClient client = withFetcher(req -> {
            captured.set(req);
            return stubResponse(req, 200, validBody());
        });

        client.snapshot(SYMBOL);

        HttpRequest sent = captured.get();
        assertThat(sent).isNotNull();
        assertThat(sent.headers().firstValue("APCA-API-KEY-ID")).contains(API_KEY);
        assertThat(sent.headers().firstValue("APCA-API-SECRET-KEY")).contains(SECRET);
        assertThat(sent.uri().toString()).contains(SYMBOL);
    }

    @Test
    void doesNotLeakCredentialsInSerializedRequest() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AlpacaQuotesClient client = withFetcher(req -> {
            captured.set(req);
            return stubResponse(req, 200, validBody());
        });

        client.snapshot(SYMBOL);

        HttpRequest sent = captured.get();
        // GET request — no body. Verify the URI does NOT contain the secret
        // as a query param and the body publisher (if present) carries no
        // bytes.
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.bodyPublisher().map(p -> p.contentLength()).orElse(0L)).isZero();
        assertThat(sent.uri().toString()).doesNotContain(SECRET).doesNotContain(API_KEY);
    }

    @Test
    void omitsAuthHeadersWhenKeysBlank() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AlpacaQuotesClient client = new AlpacaQuotesClient(
                "https://paper-api.alpaca.markets", Optional.of(""), Optional.of(""), new ObjectMapper(), req -> {
                    captured.set(req);
                    return stubResponse(req, 200, validBody());
                });

        client.snapshot(SYMBOL);

        HttpRequest sent = captured.get();
        assertThat(sent.headers().firstValue("APCA-API-KEY-ID")).isEmpty();
        assertThat(sent.headers().firstValue("APCA-API-SECRET-KEY")).isEmpty();
    }

    private static String validBody() {
        return "{\"symbol\":\"" + SYMBOL
                + "\",\"latestQuote\":{\"bp\":\"1.20\",\"ap\":\"1.25\",\"t\":\"2026-04-30T15:00:00Z\"}}";
    }

    private static HttpResponse<String> stubResponse(HttpRequest req, int status, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return req;
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(java.util.Map.of("content-type", List.of("application/json")), (a, b) -> true);
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
            public java.net.URI uri() {
                return req.uri();
            }

            @Override
            public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
