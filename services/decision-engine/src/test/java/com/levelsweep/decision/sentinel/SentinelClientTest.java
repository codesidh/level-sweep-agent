package com.levelsweep.decision.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
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

/**
 * Mockito-light tests for {@link SentinelClient}: every assertion is driven
 * through the {@link SentinelClient.Fetcher} test seam, so the JDK
 * {@link java.net.http.HttpClient} is never reached. Covers:
 *
 * <ul>
 *   <li>JSON round-trip for each {@link SentinelClientResult} variant
 *       (Allow / Veto / Fallback) via the type-tagged discriminator.</li>
 *   <li>Wall-clock timeout produces {@code Fallback(TIMEOUT)}.</li>
 *   <li>Connection-refused / IO failure produces {@code Fallback(TRANSPORT)}.</li>
 *   <li>Non-2xx HTTP response produces {@code Fallback(TRANSPORT)}.</li>
 *   <li>Empty body / malformed JSON / missing discriminator produces
 *       {@code Fallback(PARSE)}.</li>
 *   <li>Header propagation: {@code Content-Type: application/json} +
 *       request-timeout duration matches the configured ms.</li>
 * </ul>
 */
class SentinelClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void allowResponseDeserializesToAllowVariant() throws Exception {
        SentinelClient client = clientFromBody(allowJson());

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Allow.class);
        SentinelClientResult.Allow a = (SentinelClientResult.Allow) result;
        assertThat(a.clientRequestId()).isEqualTo("req_remote_allow");
        assertThat(a.confidence()).isEqualByComparingTo("0.42");
        assertThat(a.reasonCode()).isEqualTo(SentinelClientResult.ReasonCode.STRUCTURE_MATCH);
        assertThat(a.reasonText()).isEqualTo("alignment OK");
        assertThat(a.decisionPath()).isEqualTo(SentinelClientResult.DecisionPath.EXPLICIT_ALLOW);
    }

    @Test
    void vetoResponseDeserializesToVetoVariant() throws Exception {
        SentinelClient client = clientFromBody(vetoJson());

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Veto.class);
        SentinelClientResult.Veto v = (SentinelClientResult.Veto) result;
        assertThat(v.confidence()).isEqualByComparingTo("0.92");
        assertThat(v.reasonCode()).isEqualTo(SentinelClientResult.ReasonCode.STRUCTURE_DIVERGENCE);
        assertThat(v.reasonText()).isEqualTo("regime fights signal");
    }

    @Test
    void fallbackResponseDeserializesToFallbackVariant() throws Exception {
        SentinelClient client = clientFromBody(fallbackJson());

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        SentinelClientResult.Fallback f = (SentinelClientResult.Fallback) result;
        assertThat(f.reason()).isEqualTo(SentinelClientResult.FallbackReason.TIMEOUT);
    }

    @Test
    void timeoutProducesFallbackTimeout() throws Exception {
        SentinelClient.Fetcher slow = req -> {
            // Throw the same exception the JDK HttpClient raises on socket
            // timeout — the client maps it to Fallback TIMEOUT.
            throw new java.net.http.HttpTimeoutException("simulated timeout");
        };
        SentinelClient client = new SentinelClient("http://stub", 50L, MAPPER, slow);

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.TIMEOUT);
    }

    @Test
    void connectionRefusedProducesFallbackTransport() throws Exception {
        SentinelClient.Fetcher refused = req -> {
            throw new java.net.ConnectException("Connection refused");
        };
        SentinelClient client = new SentinelClient("http://stub", 750L, MAPPER, refused);

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.TRANSPORT);
    }

    @Test
    void serverError5xxProducesFallbackTransport() throws Exception {
        SentinelClient client = clientFromStatus(503, "{\"error\":\"upstream\"}");

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.TRANSPORT);
    }

    @Test
    void clientError4xxProducesFallbackTransport() throws Exception {
        SentinelClient client = clientFromStatus(400, "{\"error\":\"bad request\"}");

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.TRANSPORT);
    }

    @Test
    void malformedJsonProducesFallbackParse() throws Exception {
        SentinelClient client = clientFromBody("not-json-at-all{{");

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.PARSE);
    }

    @Test
    void emptyBodyProducesFallbackParse() throws Exception {
        SentinelClient client = clientFromBody("");

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.PARSE);
    }

    @Test
    void missingTypeDiscriminatorProducesFallbackParse() throws Exception {
        // Valid JSON but no "type" field — the bridge between Jackson's
        // type-tagged sealed interface and the wire payload requires
        // the discriminator.
        SentinelClient client = clientFromBody(
                "{\"clientRequestId\":\"x\",\"confidence\":\"0.5\",\"reasonCode\":\"OTHER\",\"reasonText\":\"\"}");

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.PARSE);
    }

    @Test
    void unknownDiscriminatorProducesFallbackParse() throws Exception {
        SentinelClient client = clientFromBody("{\"type\":\"NotAVariant\",\"clientRequestId\":\"x\"}");

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.PARSE);
    }

    @Test
    void allowWithInvalidConfidenceProducesFallbackParse() throws Exception {
        // The Allow record's compact constructor enforces [0, 1]. A
        // remote returning 1.5 trips the check; the client maps to PARSE.
        SentinelClient client = clientFromBody("{\"type\":\"Allow\",\"clientRequestId\":\"x\",\"confidence\":\"1.5\","
                + "\"reasonCode\":\"OTHER\",\"reasonText\":\"x\",\"latencyMs\":10,"
                + "\"decisionPath\":\"EXPLICIT_ALLOW\"}");

        SentinelClientResult result = client.evaluate(sampleRequest());

        assertThat(result).isInstanceOf(SentinelClientResult.Fallback.class);
        assertThat(((SentinelClientResult.Fallback) result).reason())
                .isEqualTo(SentinelClientResult.FallbackReason.PARSE);
    }

    @Test
    void requestPropagatesContentTypeAndTimeout() throws Exception {
        AtomicReference<HttpRequest> seen = new AtomicReference<>();
        SentinelClient.Fetcher capture = req -> {
            seen.set(req);
            return stubResponse(200, allowJson());
        };
        SentinelClient client = new SentinelClient("http://stub", 750L, MAPPER, capture);

        client.evaluate(sampleRequest());

        HttpRequest req = seen.get();
        assertThat(req).isNotNull();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.uri().toString()).isEqualTo("http://stub/api/v1/sentinel/evaluate");
        assertThat(req.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(req.headers().firstValue("Accept")).contains("application/json");
        assertThat(req.timeout()).contains(java.time.Duration.ofMillis(750));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SentinelClient("http://stub", 0, MAPPER, noopFetcher()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    // ---- Helpers ----------------------------------------------------------

    private static SentinelClient clientFromBody(String body) {
        return new SentinelClient("http://stub", 750L, MAPPER, req -> stubResponse(200, body));
    }

    private static SentinelClient clientFromStatus(int status, String body) {
        return new SentinelClient("http://stub", 750L, MAPPER, req -> stubResponse(status, body));
    }

    private static SentinelClient.Fetcher noopFetcher() {
        return req -> stubResponse(200, allowJson());
    }

    private static String allowJson() {
        return "{\"type\":\"Allow\",\"clientRequestId\":\"req_remote_allow\","
                + "\"confidence\":\"0.42\",\"reasonCode\":\"STRUCTURE_MATCH\","
                + "\"reasonText\":\"alignment OK\",\"latencyMs\":120,"
                + "\"decisionPath\":\"EXPLICIT_ALLOW\"}";
    }

    private static String vetoJson() {
        return "{\"type\":\"Veto\",\"clientRequestId\":\"req_remote_veto\","
                + "\"confidence\":\"0.92\",\"reasonCode\":\"STRUCTURE_DIVERGENCE\","
                + "\"reasonText\":\"regime fights signal\",\"latencyMs\":150}";
    }

    private static String fallbackJson() {
        return "{\"type\":\"Fallback\",\"clientRequestId\":\"req_remote_fb\","
                + "\"reason\":\"TIMEOUT\",\"latencyMs\":750}";
    }

    private static SentinelDecisionRequest sampleRequest() {
        SentinelDecisionRequest.IndicatorSnapshot snap = new SentinelDecisionRequest.IndicatorSnapshot(
                new BigDecimal("595.00"),
                new BigDecimal("594.00"),
                new BigDecimal("593.00"),
                new BigDecimal("1.10"),
                new BigDecimal("55"),
                "TRENDING",
                List.of(new SentinelDecisionRequest.Bar(
                        Instant.parse("2026-04-30T13:30:00Z"), new BigDecimal("590.00"), 1000L)));
        return new SentinelDecisionRequest(
                "OWNER",
                "trade-1",
                "sig-1",
                SentinelDecisionRequest.Direction.LONG_CALL,
                SentinelDecisionRequest.LevelSwept.PDH,
                snap,
                List.of(),
                new BigDecimal("18.50"),
                Instant.parse("2026-04-30T13:30:00Z"));
    }

    /**
     * Build a synthetic {@link HttpResponse} carrying just a body + status —
     * implements only the methods the client exercises. Throws on the
     * unexercised methods so a future production code path that touches
     * them surfaces in test as a UOE.
     */
    private static HttpResponse<String> stubResponse(int status, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
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
                throw new UnsupportedOperationException();
            }

            @Override
            public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_1_1;
            }
        };
    }

    /** Bridge so the IOException-throwing fetcher path compiles. */
    @SuppressWarnings("unused")
    private static SentinelClient.Fetcher ioFailing() {
        return req -> {
            throw new IOException("boom");
        };
    }
}
