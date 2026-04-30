package com.levelsweep.marketdata.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link AlpacaRestClient}. We exercise the JSON deserialization
 * via the package-private {@link AlpacaRestClient.Fetcher} seam — no real HTTP traffic.
 */
class AlpacaRestClientTest {

    private static final Instant START = Instant.parse("2026-04-30T13:30:00Z");
    private static final Instant END = Instant.parse("2026-04-30T20:00:00Z");

    @Test
    void parsesAlpacaBarsResponseIntoBarRecords() throws Exception {
        String body =
                """
                {
                  "bars": [
                    {"t": "2026-04-30T13:30:00Z", "o": 594.10, "h": 594.50, "l": 594.05, "c": 594.45, "v": 12345, "n": 100},
                    {"t": "2026-04-30T13:32:00Z", "o": 594.45, "h": 594.80, "l": 594.40, "c": 594.70, "v": 7890, "n": 60}
                  ],
                  "next_page_token": null,
                  "symbol": "SPY"
                }
                """;
        AlpacaRestClient client =
                new AlpacaRestClient(stubCfg(), new ObjectMapper(), AlpacaRestClient.fromFunction(req -> okResponse(body)));

        List<Bar> bars = client.fetchHistoricalBars("SPY", Timeframe.TWO_MIN, START, END, 200);

        assertThat(bars).hasSize(2);
        Bar first = bars.get(0);
        assertThat(first.symbol()).isEqualTo("SPY");
        assertThat(first.timeframe()).isEqualTo(Timeframe.TWO_MIN);
        assertThat(first.openTime()).isEqualTo(Instant.parse("2026-04-30T13:30:00Z"));
        assertThat(first.closeTime()).isEqualTo(Instant.parse("2026-04-30T13:32:00Z"));
        assertThat(first.open()).isEqualByComparingTo(new BigDecimal("594.10"));
        assertThat(first.high()).isEqualByComparingTo(new BigDecimal("594.50"));
        assertThat(first.low()).isEqualByComparingTo(new BigDecimal("594.05"));
        assertThat(first.close()).isEqualByComparingTo(new BigDecimal("594.45"));
        assertThat(first.volume()).isEqualTo(12_345L);
        assertThat(first.ticks()).isEqualTo(100L);

        Bar second = bars.get(1);
        assertThat(second.openTime()).isEqualTo(Instant.parse("2026-04-30T13:32:00Z"));
        assertThat(second.close()).isEqualByComparingTo(new BigDecimal("594.70"));
    }

    @Test
    void returnsEmptyListWhenBarsArrayIsEmpty() {
        String body = "{\"bars\": [], \"next_page_token\": null, \"symbol\": \"SPY\"}";
        AlpacaRestClient client =
                new AlpacaRestClient(stubCfg(), new ObjectMapper(), AlpacaRestClient.fromFunction(req -> okResponse(body)));

        List<Bar> bars = client.fetchHistoricalBars("SPY", Timeframe.TWO_MIN, START, END, 200);

        assertThat(bars).isEmpty();
    }

    @Test
    void returnsEmptyListOnNon2xxStatus() {
        AlpacaRestClient client = new AlpacaRestClient(
                stubCfg(),
                new ObjectMapper(),
                AlpacaRestClient.fromFunction(req -> response(403, "{\"message\":\"forbidden\"}")));

        List<Bar> bars = client.fetchHistoricalBars("SPY", Timeframe.TWO_MIN, START, END, 200);

        assertThat(bars).isEmpty();
    }

    @Test
    void returnsEmptyListOnIoError() {
        AlpacaRestClient.Fetcher fetcher = req -> {
            throw new IOException("network down");
        };
        AlpacaRestClient client = new AlpacaRestClient(stubCfg(), new ObjectMapper(), fetcher);

        List<Bar> bars = client.fetchHistoricalBars("SPY", Timeframe.TWO_MIN, START, END, 200);

        assertThat(bars).isEmpty();
    }

    @Test
    void fetchSendsAuthHeadersAndUrlForGivenSymbolAndTimeframe() throws Exception {
        // Verify the request shape — auth headers, timeframe param, feed param.
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = (HttpResponse<String>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"bars\":[]}");
        java.util.concurrent.atomic.AtomicReference<HttpRequest> captured = new java.util.concurrent.atomic.AtomicReference<>();
        AlpacaRestClient.Fetcher fetcher = req -> {
            captured.set(req);
            return resp;
        };
        AlpacaRestClient client = new AlpacaRestClient(stubCfg(), new ObjectMapper(), fetcher);

        client.fetchHistoricalBars("SPY", Timeframe.TWO_MIN, START, END, 200);

        HttpRequest req = captured.get();
        assertThat(req).isNotNull();
        assertThat(req.uri().toString())
                .contains("/v2/stocks/SPY/bars")
                .contains("timeframe=2Min")
                .contains("limit=200")
                .contains("adjustment=raw")
                .contains("feed=sip");
        assertThat(req.headers().firstValue("APCA-API-KEY-ID")).hasValue("AKtest");
        assertThat(req.headers().firstValue("APCA-API-SECRET-KEY")).hasValue("SKtest");
    }

    @Test
    void zeroLimitReturnsEmptyWithoutHttpCall() {
        AlpacaRestClient.Fetcher fetcher = mock(AlpacaRestClient.Fetcher.class);
        AlpacaRestClient client = new AlpacaRestClient(stubCfg(), new ObjectMapper(), fetcher);

        List<Bar> bars = client.fetchHistoricalBars("SPY", Timeframe.TWO_MIN, START, END, 0);

        assertThat(bars).isEmpty();
        verifyNoInteractions(fetcher);
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

    private static AlpacaConfig stubCfg() {
        return new AlpacaConfig() {
            @Override
            public String wsBaseUrl() {
                return "wss://stream.data.alpaca.markets";
            }

            @Override
            public String feed() {
                return "sip";
            }

            @Override
            public String tradingUrl() {
                return "https://paper-api.alpaca.markets";
            }

            @Override
            public String dataUrl() {
                return "https://data.alpaca.markets";
            }

            @Override
            public String apiKey() {
                return "AKtest";
            }

            @Override
            public String secretKey() {
                return "SKtest";
            }

            @Override
            public List<String> symbols() {
                return List.of("SPY");
            }

            @Override
            public Duration reconnectInitialBackoff() {
                return Duration.ofMillis(200);
            }

            @Override
            public Duration reconnectMaxBackoff() {
                return Duration.ofSeconds(30);
            }

            @Override
            public Duration reconnectJitter() {
                return Duration.ofMillis(100);
            }

            @Override
            public int ringBufferCapacity() {
                return 1000;
            }
        };
    }
}
