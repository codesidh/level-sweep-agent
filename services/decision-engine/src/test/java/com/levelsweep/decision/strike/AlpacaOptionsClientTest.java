package com.levelsweep.decision.strike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.options.OptionContract;
import com.levelsweep.shared.domain.options.OptionSide;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AlpacaOptionsClient}. We exercise the JSON
 * deserialization via the package-private {@link AlpacaOptionsClient.Fetcher}
 * seam — same pattern as
 * {@code com.levelsweep.marketdata.alpaca.AlpacaRestClientTest}.
 */
class AlpacaOptionsClientTest {

    private static final String BASE_URL = "https://data.alpaca.markets";
    private static final String SAMPLE_BODY =
            """
            {
              "snapshots": {
                "SPY260430C00600000": {
                  "latestQuote": {"t":"2026-04-30T14:30:00Z","bp":1.51,"bs":100,"ap":1.53,"as":50,"bx":"C","ax":"C"},
                  "latestTrade": {"t":"2026-04-30T14:30:00Z","p":1.52,"s":10},
                  "impliedVolatility": 0.25,
                  "greeks": {"delta":0.52,"gamma":0.08,"theta":-0.15,"vega":0.12,"rho":0.05},
                  "openInterest": 1500,
                  "dailyBar": {"o":1.45,"h":1.55,"l":1.42,"c":1.52,"v":12345}
                },
                "SPY260430P00595000": {
                  "latestQuote": {"bp":0.80,"ap":0.82,"bs":100,"as":50},
                  "openInterest": 800,
                  "dailyBar": {"v":5000},
                  "greeks": {"delta":-0.40}
                }
              }
            }
            """;

    @Test
    void parsesSnapshotsIntoOptionContracts() {
        AlpacaOptionsClient client = new AlpacaOptionsClient(
                BASE_URL,
                "AKtest",
                "SKtest",
                new ObjectMapper(),
                AlpacaOptionsClient.fromFunction(req -> okResponse(SAMPLE_BODY)));

        List<OptionContract> chain = client.fetchChain("SPY");

        assertThat(chain).hasSize(2);
        OptionContract call = chain.stream()
                .filter(c -> c.side() == OptionSide.CALL)
                .findFirst()
                .orElseThrow();
        assertThat(call.symbol()).isEqualTo("SPY260430C00600000");
        assertThat(call.underlying()).isEqualTo("SPY");
        assertThat(call.expiry()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(call.strike()).isEqualByComparingTo(new BigDecimal("600.000"));
        assertThat(call.bidPrice()).isEqualByComparingTo(new BigDecimal("1.51"));
        assertThat(call.askPrice()).isEqualByComparingTo(new BigDecimal("1.53"));
        assertThat(call.openInterest()).hasValue(1500);
        assertThat(call.volume()).hasValue(12345);
        assertThat(call.impliedVolatility()).isPresent();
        assertThat(call.impliedVolatility().get()).isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(call.delta()).isPresent();
        assertThat(call.delta().get()).isEqualByComparingTo(new BigDecimal("0.52"));

        OptionContract put = chain.stream()
                .filter(c -> c.side() == OptionSide.PUT)
                .findFirst()
                .orElseThrow();
        assertThat(put.strike()).isEqualByComparingTo(new BigDecimal("595.000"));
        assertThat(put.delta()).isPresent();
        assertThat(put.delta().get()).isEqualByComparingTo(new BigDecimal("-0.40"));
    }

    @Test
    void returnsEmptyOnNonOkStatus() {
        AlpacaOptionsClient client = new AlpacaOptionsClient(
                BASE_URL,
                "AKtest",
                "SKtest",
                new ObjectMapper(),
                AlpacaOptionsClient.fromFunction(req -> response(403, "{\"message\":\"forbidden\"}")));

        assertThat(client.fetchChain("SPY")).isEmpty();
    }

    @Test
    void returnsEmptyOnIoError() {
        AlpacaOptionsClient.Fetcher fetcher = req -> {
            throw new IOException("network down");
        };
        AlpacaOptionsClient client =
                new AlpacaOptionsClient(BASE_URL, "AKtest", "SKtest", new ObjectMapper(), fetcher);

        assertThat(client.fetchChain("SPY")).isEmpty();
    }

    @Test
    void returnsEmptyOnEmptyBody() {
        AlpacaOptionsClient client = new AlpacaOptionsClient(
                BASE_URL,
                "AKtest",
                "SKtest",
                new ObjectMapper(),
                AlpacaOptionsClient.fromFunction(req -> okResponse("")));

        assertThat(client.fetchChain("SPY")).isEmpty();
    }

    @Test
    void returnsEmptyWhenSnapshotsObjectMissing() {
        AlpacaOptionsClient client = new AlpacaOptionsClient(
                BASE_URL,
                "AKtest",
                "SKtest",
                new ObjectMapper(),
                AlpacaOptionsClient.fromFunction(req -> okResponse("{\"foo\":\"bar\"}")));

        assertThat(client.fetchChain("SPY")).isEmpty();
    }

    @Test
    void skipsMalformedEntriesAndKeepsValidOnes() {
        String body =
                """
                {
                  "snapshots": {
                    "GARBAGE-NOT-OCC": {"latestQuote":{"bp":1.0,"ap":1.1}},
                    "SPY260430C00600000": {
                      "latestQuote": {"bp":1.51,"ap":1.53},
                      "openInterest": 1500
                    },
                    "SPY260430C00601000": {
                      "latestQuote": {"bp":2.00,"ap":1.50}
                    }
                  }
                }
                """;
        AlpacaOptionsClient client = new AlpacaOptionsClient(
                BASE_URL,
                "AKtest",
                "SKtest",
                new ObjectMapper(),
                AlpacaOptionsClient.fromFunction(req -> okResponse(body)));

        List<OptionContract> chain = client.fetchChain("SPY");

        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).symbol()).isEqualTo("SPY260430C00600000");
    }

    @Test
    void sendsAuthHeadersAndCorrectUrl() {
        java.util.concurrent.atomic.AtomicReference<HttpRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        AlpacaOptionsClient.Fetcher fetcher = req -> {
            captured.set(req);
            return okResponse("{\"snapshots\":{}}");
        };
        AlpacaOptionsClient client = new AlpacaOptionsClient(BASE_URL, "AKtest", "SKtest", new ObjectMapper(), fetcher);

        client.fetchChain("SPY");

        HttpRequest req = captured.get();
        assertThat(req).isNotNull();
        assertThat(req.uri().toString()).isEqualTo(BASE_URL + "/v1beta1/options/snapshots/SPY");
        assertThat(req.headers().firstValue("APCA-API-KEY-ID")).hasValue("AKtest");
        assertThat(req.headers().firstValue("APCA-API-SECRET-KEY")).hasValue("SKtest");
        assertThat(req.method()).isEqualTo("GET");
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
}
