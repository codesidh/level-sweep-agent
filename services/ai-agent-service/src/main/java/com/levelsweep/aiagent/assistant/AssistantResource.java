package com.levelsweep.aiagent.assistant;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conversational Assistant REST endpoints (architecture-spec §4.5 + §16.4).
 * The Phase 6 BFF proxies every endpoint here under
 * {@code /api/v1/assistant/...}; Phase A does not enforce auth at this layer
 * (CLAUDE.md guardrail #1 — single OWNER tenant; the BFF's
 * {@code BypassAuthFilter} is the gate).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/v1/assistant/chat} — body
 *       {@code {tenantId, conversationId?, userMessage}} → {@code {turn, conversationId}}.</li>
 *   <li>{@code GET /api/v1/assistant/conversations?tenantId=...&limit=20} —
 *       metadata-only listing (no turn content).</li>
 *   <li>{@code GET /api/v1/assistant/conversations/{conversationId}?tenantId=...} —
 *       full conversation with turns.</li>
 * </ul>
 *
 * <p>Validation: 400 on missing tenantId / userMessage / blank input. The BFF
 * bucket4j limiter handles per-tenant rate limiting upstream.
 */
@Path("/api/v1/assistant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AssistantResource {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantResource.class);

    private final ConversationalAssistant assistant;
    private final AssistantConversationRepository repository;

    @Inject
    public AssistantResource(ConversationalAssistant assistant, AssistantConversationRepository repository) {
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @POST
    @Path("/chat")
    public Response chat(ChatRequest body) {
        if (body == null) {
            return badRequest("missing request body");
        }
        if (isBlank(body.tenantId())) {
            return badRequest("tenantId must not be blank");
        }
        if (isBlank(body.userMessage())) {
            return badRequest("userMessage must not be blank");
        }
        try {
            ConversationalAssistant.ChatResult result =
                    assistant.chat(body.tenantId(), body.conversationId(), body.userMessage());
            return Response.ok(new ChatResponse(result.conversationId(), TurnView.of(result.turn())))
                    .build();
        } catch (IllegalArgumentException e) {
            // Defensive — the orchestrator validates too, but a stray null
            // surfaces as 400 rather than 500.
            return badRequest(e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("assistant chat failed unexpectedly tenantId={}: {}", body.tenantId(), e.toString());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("assistant chat failed"))
                    .build();
        }
    }

    @GET
    @Path("/conversations")
    public Response list(@QueryParam("tenantId") String tenantId, @QueryParam("limit") @DefaultValue("20") int limit) {
        if (isBlank(tenantId)) {
            return badRequest("tenantId query parameter required");
        }
        if (limit <= 0 || limit > 100) {
            return badRequest("limit must be in [1, 100]");
        }
        List<AssistantConversation> recent = repository.recentForTenant(tenantId, limit);
        List<ConversationSummary> summaries =
                recent.stream().map(ConversationSummary::of).toList();
        return Response.ok(summaries).build();
    }

    @GET
    @Path("/conversations/{conversationId}")
    public Response get(@PathParam("conversationId") String conversationId, @QueryParam("tenantId") String tenantId) {
        if (isBlank(tenantId)) {
            return badRequest("tenantId query parameter required");
        }
        if (isBlank(conversationId)) {
            return badRequest("conversationId path parameter required");
        }
        Optional<AssistantConversation> conv = repository.findById(tenantId, conversationId);
        if (conv.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("conversation not found"))
                    .build();
        }
        return Response.ok(ConversationView.of(conv.get())).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Response badRequest(String reason) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(reason))
                .build();
    }

    // ---------- DTOs ----------

    /** Inbound chat body. {@code conversationId} is optional — null/blank → new thread. */
    public record ChatRequest(String tenantId, String conversationId, String userMessage) {}

    /** Outbound chat response. */
    public record ChatResponse(String conversationId, TurnView turn) {}

    /** Outbound turn view — flat shape for the BFF / Angular client. */
    public record TurnView(String role, String content, Instant ts, BigDecimal costUsd) {
        public static TurnView of(AssistantTurn t) {
            return new TurnView(t.role(), t.content(), t.ts(), t.costUsd());
        }
    }

    /** Listing summary — drops turn content, keeps metadata only. */
    public record ConversationSummary(
            String conversationId, Instant createdAt, Instant updatedAt, int turnCount, BigDecimal totalCostUsd) {
        public static ConversationSummary of(AssistantConversation c) {
            return new ConversationSummary(
                    c.conversationId(), c.createdAt(), c.updatedAt(), c.turns().size(), c.totalCostUsd());
        }
    }

    /** Full conversation view — includes turns. */
    public record ConversationView(
            String conversationId,
            Instant createdAt,
            Instant updatedAt,
            List<TurnView> turns,
            BigDecimal totalCostUsd) {
        public static ConversationView of(AssistantConversation c) {
            return new ConversationView(
                    c.conversationId(),
                    c.createdAt(),
                    c.updatedAt(),
                    c.turns().stream().map(TurnView::of).toList(),
                    c.totalCostUsd());
        }
    }

    /** Standard error envelope. */
    public record ErrorResponse(String error) {}
}
