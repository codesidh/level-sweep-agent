package com.levelsweep.aiagent.assistant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.anthropic.AnthropicResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Quarkus REST tests for {@link AssistantResource}. Mocks
 * {@link AnthropicClient} and the conversation repository — no Anthropic
 * traffic, no Mongo. Uses RestAssured (project pattern; same harness as
 * other Quarkus services in the repo would use when they add REST endpoints).
 */
@QuarkusTest
class AssistantResourceTest {

    @InjectMock
    AnthropicClient anthropicClient;

    @InjectMock
    AssistantConversationRepository repository;

    @Inject
    ConversationalAssistant assistant; // ensure the bean wires.

    @Test
    void chat_postReturnsAssistantTurnAndConversationId() {
        Instant now = Instant.parse("2026-05-04T13:30:00Z");
        AssistantConversation fresh =
                new AssistantConversation("OWNER", "conv-rest-1", now, now, List.of(), BigDecimal.ZERO);
        Mockito.when(repository.createNew("OWNER")).thenReturn(fresh);
        Mockito.when(anthropicClient.submit(
                        ArgumentMatchers.any(AnthropicRequest.class), ArgumentMatchers.anyBoolean()))
                .thenReturn(success("the answer."));

        given().contentType("application/json")
                .body(Map.of("tenantId", "OWNER", "userMessage", "what was the R-multiple?"))
                .when()
                .post("/api/v1/assistant/chat")
                .then()
                .statusCode(200)
                .body("conversationId", equalTo("conv-rest-1"))
                .body("turn.role", equalTo("assistant"))
                .body("turn.content", equalTo("the answer."));
    }

    @Test
    void chat_400OnMissingTenantId() {
        given().contentType("application/json")
                .body(Map.of("userMessage", "hi"))
                .when()
                .post("/api/v1/assistant/chat")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void chat_400OnMissingUserMessage() {
        given().contentType("application/json")
                .body(Map.of("tenantId", "OWNER"))
                .when()
                .post("/api/v1/assistant/chat")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void chat_400OnBlankUserMessage() {
        given().contentType("application/json")
                .body(Map.of("tenantId", "OWNER", "userMessage", "   "))
                .when()
                .post("/api/v1/assistant/chat")
                .then()
                .statusCode(400);
    }

    @Test
    void listConversations_returnsSummariesWithoutTurnContent() {
        Instant now = Instant.parse("2026-05-04T13:30:00Z");
        AssistantConversation conv = new AssistantConversation(
                "OWNER",
                "conv-rest-2",
                now,
                now,
                List.of(AssistantTurn.user("q", now), AssistantTurn.assistant("a", now, new BigDecimal("0.0150"))),
                new BigDecimal("0.0150"));
        Mockito.when(repository.recentForTenant("OWNER", 20)).thenReturn(List.of(conv));

        given().queryParam("tenantId", "OWNER")
                .queryParam("limit", 20)
                .when()
                .get("/api/v1/assistant/conversations")
                .then()
                .statusCode(200)
                .body("[0].conversationId", equalTo("conv-rest-2"))
                .body("[0].turnCount", equalTo(2))
                .body("[0].totalCostUsd", equalTo(0.0150f));
    }

    @Test
    void listConversations_400OnMissingTenant() {
        given().when().get("/api/v1/assistant/conversations").then().statusCode(400);
    }

    @Test
    void listConversations_400OnInvalidLimit() {
        given().queryParam("tenantId", "OWNER")
                .queryParam("limit", 0)
                .when()
                .get("/api/v1/assistant/conversations")
                .then()
                .statusCode(400);
    }

    @Test
    void getConversation_returnsFullThread() {
        Instant now = Instant.parse("2026-05-04T13:30:00Z");
        AssistantConversation conv = new AssistantConversation(
                "OWNER", "conv-rest-3", now, now, List.of(AssistantTurn.user("hi", now)), BigDecimal.ZERO);
        Mockito.when(repository.findById("OWNER", "conv-rest-3")).thenReturn(Optional.of(conv));

        given().queryParam("tenantId", "OWNER")
                .when()
                .get("/api/v1/assistant/conversations/conv-rest-3")
                .then()
                .statusCode(200)
                .body("conversationId", equalTo("conv-rest-3"))
                .body("turns.size()", equalTo(1))
                .body("turns[0].role", equalTo("user"));
    }

    @Test
    void getConversation_404OnUnknownId() {
        Mockito.when(repository.findById("OWNER", "missing")).thenReturn(Optional.empty());

        given().queryParam("tenantId", "OWNER")
                .when()
                .get("/api/v1/assistant/conversations/missing")
                .then()
                .statusCode(404);
    }

    @Test
    void getConversation_400OnMissingTenant() {
        given().when().get("/api/v1/assistant/conversations/anything").then().statusCode(400);
    }

    private static AnthropicResponse.Success success(String text) {
        return new AnthropicResponse.Success(
                "req_ok",
                Role.ASSISTANT,
                "claude-sonnet-4-6",
                42L,
                text,
                List.of(),
                1500,
                50,
                0,
                new BigDecimal("0.0150"));
    }
}
