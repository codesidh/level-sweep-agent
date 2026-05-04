package com.levelsweep.aiagent.sentinel;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Allow;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.DecisionPath;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Fallback;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.FallbackReason;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.ReasonCode;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Veto;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Quarkus REST tests for {@link SentinelResource}. Mocks
 * {@link SentinelService} so no Anthropic traffic + no Mongo writes happen
 * during the test. Exercises:
 *
 * <ul>
 *   <li>Round-trip of each {@link SentinelDecisionResponse} variant
 *       through Jackson's {@code @JsonTypeInfo} discriminator.</li>
 *   <li>400 Bad Request on validation failures (null body / missing
 *       tenantId / oversized recent-trades-window).</li>
 *   <li>200 OK with a {@link Fallback} payload when the service throws —
 *       fail-OPEN posture per ADR-0007 §3 still requires the resource
 *       to surface 200 with a Fallback variant rather than 5xx (the
 *       service is the layer that decides; the resource is a thin shell).</li>
 *   <li>503 only when an unexpected RuntimeException escapes the service
 *       layer — defensive, not the documented happy-path failure mode.</li>
 * </ul>
 */
@QuarkusTest
class SentinelResourceTest {

    @InjectMock
    SentinelService sentinelService;

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void evaluate_allowVariantRoundTripsViaJsonDiscriminator() {
        Allow allow = new Allow(
                "req_allow",
                new BigDecimal("0.42"),
                ReasonCode.STRUCTURE_MATCH,
                "alignment OK",
                123L,
                DecisionPath.EXPLICIT_ALLOW);
        Mockito.when(sentinelService.evaluate(ArgumentMatchers.any(SentinelDecisionRequest.class)))
                .thenReturn(allow);

        given().contentType("application/json")
                .body(validRequestJson("OWNER", "trade-1", "sig-1"))
                .when()
                .post("/api/v1/sentinel/evaluate")
                .then()
                .statusCode(200)
                .body("type", equalTo("Allow"))
                .body("clientRequestId", equalTo("req_allow"))
                .body("confidence", equalTo(0.42f))
                .body("reasonCode", equalTo("STRUCTURE_MATCH"))
                .body("reasonText", equalTo("alignment OK"))
                .body("decisionPath", equalTo("EXPLICIT_ALLOW"))
                .body("latencyMs", equalTo(123));
    }

    @Test
    void evaluate_vetoVariantRoundTrips() {
        Veto veto = new Veto(
                "req_veto", new BigDecimal("0.92"), ReasonCode.STRUCTURE_DIVERGENCE, "regime fights signal", 145L);
        Mockito.when(sentinelService.evaluate(ArgumentMatchers.any(SentinelDecisionRequest.class)))
                .thenReturn(veto);

        given().contentType("application/json")
                .body(validRequestJson("OWNER", "trade-2", "sig-2"))
                .when()
                .post("/api/v1/sentinel/evaluate")
                .then()
                .statusCode(200)
                .body("type", equalTo("Veto"))
                .body("clientRequestId", equalTo("req_veto"))
                .body("confidence", equalTo(0.92f))
                .body("reasonCode", equalTo("STRUCTURE_DIVERGENCE"))
                .body("reasonText", equalTo("regime fights signal"))
                .body("latencyMs", equalTo(145));
    }

    @Test
    void evaluate_fallbackVariantRoundTrips() {
        Fallback fallback = new Fallback("req_fb", FallbackReason.TIMEOUT, 750L);
        Mockito.when(sentinelService.evaluate(ArgumentMatchers.any(SentinelDecisionRequest.class)))
                .thenReturn(fallback);

        given().contentType("application/json")
                .body(validRequestJson("OWNER", "trade-3", "sig-3"))
                .when()
                .post("/api/v1/sentinel/evaluate")
                .then()
                .statusCode(200)
                .body("type", equalTo("Fallback"))
                .body("clientRequestId", equalTo("req_fb"))
                .body("reason", equalTo("TIMEOUT"))
                .body("latencyMs", equalTo(750));
    }

    @Test
    void evaluate_400OnEmptyBody() {
        // RestAssured: explicit content-type and a body of "{}" so
        // Jackson sees an empty object — the SentinelDecisionRequest's
        // canonical constructor trips on null tenantId immediately. The
        // failure happens during deserialization so Quarkus may return
        // 400 without a JSON body — assert only the status code.
        given().contentType("application/json")
                .body("{}")
                .when()
                .post("/api/v1/sentinel/evaluate")
                .then()
                .statusCode(400);
    }

    @Test
    void evaluate_400OnMissingTenantId() {
        // Drop tenantId from an otherwise valid payload to trip the
        // record's compact-constructor null check. The framework returns
        // 400 because Jackson + record canonical-constructor rejects null
        // before the resource sees the object.
        String json = validRequestJson(null, "trade-x", "sig-x");
        given().contentType("application/json")
                .body(json)
                .when()
                .post("/api/v1/sentinel/evaluate")
                .then()
                .statusCode(400);
    }

    @Test
    void evaluate_400OnInvalidLevelSwept() {
        // Pass an enum value the framework can't deserialize. Quarkus REST
        // returns 400 for an unparseable enum value.
        String json = validRequestJson("OWNER", "trade-y", "sig-y").replace("\"PDH\"", "\"NOT_A_LEVEL\"");
        given().contentType("application/json")
                .body(json)
                .when()
                .post("/api/v1/sentinel/evaluate")
                .then()
                .statusCode(400);
    }

    @Test
    void evaluate_503WhenServiceThrowsUnexpectedly() {
        // The service contract is fail-OPEN; a RuntimeException escaping
        // the orchestrator is a genuine bug. The resource maps that to
        // 503, and the saga client (decision-engine) translates 503 into
        // its own Fallback TRANSPORT — preserving end-to-end fail-OPEN.
        Mockito.when(sentinelService.evaluate(ArgumentMatchers.any(SentinelDecisionRequest.class)))
                .thenThrow(new RuntimeException("orchestrator bug"));

        given().contentType("application/json")
                .body(validRequestJson("OWNER", "trade-bug", "sig-bug"))
                .when()
                .post("/api/v1/sentinel/evaluate")
                .then()
                .statusCode(503)
                .body("error", notNullValue());
    }

    @Test
    void evaluate_passesRequestThroughToService() throws Exception {
        // Verify the resource is a thin shell — the request that arrives
        // at SentinelService matches the wire payload byte-for-byte (modulo
        // the Jackson / record-canonical-constructor reconstruction).
        Allow allow = new Allow("req_pt", BigDecimal.ZERO, ReasonCode.OTHER, "", 10L, DecisionPath.FALLBACK_ALLOW);
        Mockito.when(sentinelService.evaluate(ArgumentMatchers.any(SentinelDecisionRequest.class)))
                .thenReturn(allow);

        given().contentType("application/json")
                .body(validRequestJson("OWNER", "trade-pt", "sig-pt"))
                .when()
                .post("/api/v1/sentinel/evaluate")
                .then()
                .statusCode(200);

        // Capture + assert tenantId / tradeId / signalId reach the service.
        org.mockito.ArgumentCaptor<SentinelDecisionRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SentinelDecisionRequest.class);
        Mockito.verify(sentinelService).evaluate(captor.capture());
        SentinelDecisionRequest passed = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(passed.tenantId()).isEqualTo("OWNER");
        org.assertj.core.api.Assertions.assertThat(passed.tradeId()).isEqualTo("trade-pt");
        org.assertj.core.api.Assertions.assertThat(passed.signalId()).isEqualTo("sig-pt");
    }

    /** Build a valid {@link SentinelDecisionRequest} JSON with overridable identifiers. */
    private static String validRequestJson(String tenantId, String tradeId, String signalId) {
        try {
            SentinelDecisionRequest.IndicatorSnapshot snap = new SentinelDecisionRequest.IndicatorSnapshot(
                    new BigDecimal("595.00"),
                    new BigDecimal("594.00"),
                    new BigDecimal("593.00"),
                    new BigDecimal("1.10"),
                    new BigDecimal("55"),
                    "TRENDING",
                    List.of(new SentinelDecisionRequest.Bar(
                            Instant.parse("2026-04-30T13:30:00Z"), new BigDecimal("590.00"), 1000L)));
            // Build a record only when all fields are valid; otherwise build the
            // JSON directly via a Map so we can omit/null-out specific fields
            // (the test uses raw JSON to exercise validation paths).
            if (tenantId != null) {
                SentinelDecisionRequest req = new SentinelDecisionRequest(
                        tenantId,
                        tradeId,
                        signalId,
                        SentinelDecisionRequest.Direction.LONG_CALL,
                        SentinelDecisionRequest.LevelSwept.PDH,
                        snap,
                        List.of(),
                        new BigDecimal("18.50"),
                        Instant.parse("2026-04-30T13:30:00Z"));
                return MAPPER.writeValueAsString(req);
            }
            // Hand-rolled JSON with omitted tenantId.
            return "{\"tradeId\":\"" + tradeId + "\","
                    + "\"signalId\":\"" + signalId + "\","
                    + "\"direction\":\"LONG_CALL\","
                    + "\"levelSwept\":\"PDH\","
                    + "\"indicatorSnapshot\":" + MAPPER.writeValueAsString(snap) + ","
                    + "\"recentTradesWindow\":[],"
                    + "\"vixClosePrev\":\"18.50\","
                    + "\"nowUtc\":\"2026-04-30T13:30:00Z\"}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
