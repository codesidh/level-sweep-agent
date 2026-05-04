package com.levelsweep.aiagent.sentinel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-Trade Sentinel REST endpoint (ADR-0007 §1, §3, §4). The decision-engine
 * Trade Saga calls this synchronously between RiskGate and StrikeSelector;
 * the saga lives in a separate K8s pod ({@code decision-engine}) so the call
 * crosses a process boundary as a single 750ms-budgeted POST.
 *
 * <p>Endpoint: {@code POST /api/v1/sentinel/evaluate}.
 *
 * <p>Wire contract:
 *
 * <ul>
 *   <li>200 OK with body matching {@link SentinelDecisionResponse} JSON
 *       (variant tagged via {@code "type": "Allow"|"Veto"|"Fallback"} —
 *       see the {@code @JsonTypeInfo} on the response interface). Even
 *       transport-layer failures inside {@link SentinelService} surface as
 *       a 200 with a {@code Fallback} payload — fail-OPEN per ADR-0007 §3.</li>
 *   <li>400 Bad Request when the request body is missing or fails the
 *       {@link SentinelDecisionRequest} compact-constructor validation.</li>
 *   <li>503 Service Unavailable only when the resource itself cannot
 *       construct the call (rare — the orchestrator already swallows every
 *       known transport / parse / timeout failure into a {@code Fallback}).</li>
 * </ul>
 *
 * <p><b>Phase A auth</b>: bypass at this layer per CLAUDE.md guardrail #1.
 * The BFF's {@code BypassAuthFilter} is the gate; the decision-engine pod
 * connects in-cluster on the AKS network policy.
 *
 * <p><b>Audit + metrics</b>: handled inside {@link SentinelService}, not
 * here. The resource is intentionally a thin shell — it never touches the
 * Anthropic client or Mongo directly.
 */
@Path("/api/v1/sentinel")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SentinelResource {

    private static final Logger LOG = LoggerFactory.getLogger(SentinelResource.class);

    private final SentinelService sentinelService;

    @Inject
    public SentinelResource(SentinelService sentinelService) {
        this.sentinelService = Objects.requireNonNull(sentinelService, "sentinelService");
    }

    @POST
    @Path("/evaluate")
    public Response evaluate(SentinelDecisionRequest request) {
        if (request == null) {
            return badRequest("missing request body");
        }
        try {
            SentinelDecisionResponse response = sentinelService.evaluate(request);
            return Response.ok(response).build();
        } catch (NullPointerException | IllegalArgumentException e) {
            // Both surface from the request's compact-constructor validation
            // (Objects.requireNonNull, blank-tenant guard, recent-trades-window
            // size guard, etc.). They are caller errors, not service outages.
            LOG.warn("sentinel evaluate rejected (validation): {}", e.toString());
            return badRequest(e.getMessage() == null ? "validation failed" : e.getMessage());
        } catch (RuntimeException e) {
            // SentinelService.evaluate() is contractually fail-OPEN — every
            // documented failure becomes a Fallback. If a RuntimeException
            // still escapes, it's a genuine bug in the orchestrator. We log,
            // return 503, and let the saga client treat that as transport
            // (its own SentinelClient maps non-2xx to Fallback TRANSPORT,
            // preserving the fail-OPEN posture end-to-end).
            LOG.error("sentinel evaluate failed unexpectedly tenantId={}: {}", request.tenantId(), e.toString());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new ErrorResponse("sentinel evaluate failed"))
                    .build();
        }
    }

    private static Response badRequest(String reason) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(reason))
                .build();
    }

    /** Standard error envelope. */
    public record ErrorResponse(String error) {}
}
