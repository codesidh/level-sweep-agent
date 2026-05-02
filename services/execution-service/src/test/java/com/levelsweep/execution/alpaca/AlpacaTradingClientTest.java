package com.levelsweep.execution.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link AlpacaTradingClient}. We exercise the JSON +
 * HTTP contract via the package-private {@link AlpacaTradingClient.Fetcher}
 * seam — no real HTTP traffic, no real Alpaca account.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>200 with full JSON → {@link OrderSubmission.Submitted} populated correctly
 *   <li>422 with error body → {@link OrderSubmission.Rejected} carrying status + body
 *   <li>500 → {@link OrderSubmission.Rejected}
 *   <li>IOException → {@link OrderSubmission.FailedWithError}
 *   <li>Missing API key → request still attempted (server returns Rejected)
 *   <li>Auth headers + JSON body shape on the wire
 *   <li>Idempotency key matches {@code "<tenantId>:<tradeId>"}
 *   <li>API key / secret never appear in the serialized request body
 * </ul>
 */
class AlpacaTradingClientTest {

    private static final String BASE_URL = "https://paper-api.alpaca.markets";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-30T13:32:30Z"), ZoneOffset.UTC);

    @Test
    void twoHundredWithFullJsonProducesSubmitted() {
        String body =
                """
                {
                  "id": "61e69015-8549-4bfd-b9c3-01e75843f47d",
                  "client_order_id": "OWNER:trade-abc",
                  "status": "accepted",
                  "symbol": "SPY260430C00595000"
                }
                """;
        AlpacaTradingClient client = newClient(req -> okResponse(body));

        OrderSubmission outcome = client.submit(buyOrder("OWNER:trade-abc"));

        assertThat(outcome).isInstanceOf(OrderSubmission.Submitted.class);
        OrderSubmission.Submitted s = (OrderSubmission.Submitted) outcome;
        assertThat(s.alpacaOrderId()).isEqualTo("61e69015-8549-4bfd-b9c3-01e75843f47d");
        assertThat(s.clientOrderId()).isEqualTo("OWNER:trade-abc");
        assertThat(s.status()).isEqualTo("accepted");
        assertThat(s.submittedAt()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    void fourTwentyTwoWithErrorBodyProducesRejected() {
        String body = "{\"code\":40010001,\"message\":\"client_order_id must be unique\"}";
        AlpacaTradingClient client = newClient(req -> response(422, body));

        OrderSubmission outcome = client.submit(buyOrder("OWNER:trade-abc"));

        assertThat(outcome).isInstanceOf(OrderSubmission.Rejected.class);
        OrderSubmission.Rejected r = (OrderSubmission.Rejected) outcome;
        assertThat(r.httpStatus()).isEqualTo(422);
        assertThat(r.reason()).contains("client_order_id must be unique");
        assertThat(r.clientOrderId()).isEqualTo("OWNER:trade-abc");
    }

    @Test
    void fiveHundredAlsoProducesRejected() {
        AlpacaTradingClient client = newClient(req -> response(500, "internal server error"));

        OrderSubmission outcome = client.submit(buyOrder("OWNER:trade-abc"));

        assertThat(outcome).isInstanceOf(OrderSubmission.Rejected.class);
        OrderSubmission.Rejected r = (OrderSubmission.Rejected) outcome;
        assertThat(r.httpStatus()).isEqualTo(500);
    }

    @Test
    void ioExceptionProducesFailedWithError() {
        AlpacaTradingClient.Fetcher fetcher = req -> {
            throw new IOException("connection reset");
        };
        AlpacaTradingClient client = newClient(fetcher);

        OrderSubmission outcome = client.submit(buyOrder("OWNER:trade-abc"));

        assertThat(outcome).isInstanceOf(OrderSubmission.FailedWithError.class);
        OrderSubmission.FailedWithError f = (OrderSubmission.FailedWithError) outcome;
        assertThat(f.exceptionMessage()).contains("connection reset");
        assertThat(f.clientOrderId()).isEqualTo("OWNER:trade-abc");
    }

    @Test
    void emptyBodyOn2xxIsTreatedAsTransportFailure() {
        AlpacaTradingClient client = newClient(req -> okResponse(""));

        OrderSubmission outcome = client.submit(buyOrder("OWNER:trade-abc"));

        assertThat(outcome).isInstanceOf(OrderSubmission.FailedWithError.class);
    }

    @Test
    void missingIdFieldOn2xxIsTreatedAsTransportFailure() {
        AlpacaTradingClient client = newClient(req -> okResponse("{\"status\":\"accepted\"}"));

        OrderSubmission outcome = client.submit(buyOrder("OWNER:trade-abc"));

        assertThat(outcome).isInstanceOf(OrderSubmission.FailedWithError.class);
    }

    @Test
    void requestCarriesAuthHeadersAndJsonBody() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AlpacaTradingClient.Fetcher fetcher = req -> {
            captured.set(req);
            capturedBody.set(extractBody(req));
            return okResponse("{\"id\":\"abc\",\"status\":\"accepted\"}");
        };
        AlpacaTradingClient client = newClient(fetcher);

        client.submit(buyOrder("OWNER:trade-abc"));

        HttpRequest req = captured.get();
        assertThat(req).isNotNull();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.uri().toString()).isEqualTo(BASE_URL + "/v2/orders");
        assertThat(req.headers().firstValue("APCA-API-KEY-ID")).hasValue("AKtest");
        assertThat(req.headers().firstValue("APCA-API-SECRET-KEY")).hasValue("SKtest");
        assertThat(req.headers().firstValue("Content-Type")).hasValue("application/json");

        String body = capturedBody.get();
        assertThat(body).isNotBlank();
        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.get("symbol").asText()).isEqualTo("SPY260430C00595000");
        assertThat(root.get("qty").asText()).isEqualTo("1");
        assertThat(root.get("side").asText()).isEqualTo("buy");
        assertThat(root.get("type").asText()).isEqualTo("limit");
        assertThat(root.get("time_in_force").asText()).isEqualTo("day");
        assertThat(root.get("limit_price").asText()).isEqualTo("1.24");
        assertThat(root.get("client_order_id").asText()).isEqualTo("OWNER:trade-abc");
    }

    @Test
    void apiKeyAndSecretNeverAppearInSerializedJsonBody() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AlpacaTradingClient.Fetcher fetcher = req -> {
            capturedBody.set(extractBody(req));
            return okResponse("{\"id\":\"abc\",\"status\":\"accepted\"}");
        };
        AlpacaTradingClient client = newClient(fetcher);

        client.submit(buyOrder("OWNER:trade-abc"));

        // The redaction guarantee: under no circumstance does the JSON body
        // include the credentials. They are HTTP headers — never payload.
        String body = capturedBody.get();
        assertThat(body).doesNotContain("AKtest").doesNotContain("SKtest");
    }

    @Test
    void missingApiKeyStillAttemptsRequest() throws Exception {
        // No API key configured — the client should warn-log and still send the request.
        // The server (here stubbed) responds with 401, mapped to Rejected.
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AlpacaTradingClient.Fetcher fetcher = req -> {
            captured.set(req);
            return response(401, "{\"message\":\"unauthorized\"}");
        };
        AlpacaTradingClient client = new AlpacaTradingClient(
                BASE_URL, Optional.empty(), Optional.empty(), FIXED_CLOCK, new ObjectMapper(), fetcher);

        OrderSubmission outcome = client.submit(buyOrder("OWNER:trade-abc"));

        assertThat(outcome).isInstanceOf(OrderSubmission.Rejected.class);
        assertThat(((OrderSubmission.Rejected) outcome).httpStatus()).isEqualTo(401);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().firstValue("APCA-API-KEY-ID")).isEmpty();
    }

    private static AlpacaTradingClient newClient(AlpacaTradingClient.Fetcher fetcher) {
        return new AlpacaTradingClient(
                BASE_URL, Optional.of("AKtest"), Optional.of("SKtest"), FIXED_CLOCK, new ObjectMapper(), fetcher);
    }

    private static OrderRequest buyOrder(String clientOrderId) {
        return new OrderRequest(
                "OWNER",
                "trade-abc",
                "SPY260430C00595000",
                1,
                OrderRequest.SIDE_BUY,
                OrderRequest.TYPE_LIMIT,
                Optional.of(new BigDecimal("1.24")),
                OrderRequest.TIF_DAY,
                clientOrderId);
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

    /**
     * Extract the {@code POST} body from the JDK {@link HttpRequest} for assertions.
     * The {@link HttpRequest.BodyPublisher} delivers the bytes via a reactive
     * {@link java.util.concurrent.Flow.Subscriber}; we synthesize a tiny one
     * that concatenates the chunks. {@code BodyPublishers.ofString} is fully
     * synchronous so the result is populated before this method returns.
     */
    private static String extractBody(HttpRequest req) {
        Optional<HttpRequest.BodyPublisher> bp = req.bodyPublisher();
        if (bp.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        bp.get().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] arr = new byte[item.remaining()];
                item.get(arr);
                sb.append(new String(arr, java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable throwable) {
                // ignore — test seam, errors surface elsewhere
            }

            @Override
            public void onComplete() {
                // no-op
            }
        });
        return sb.toString();
    }
}
