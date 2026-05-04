package com.levelsweep.gateway.routing;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin proxy controller for ai-agent-service Conversational Assistant
 * endpoints (architecture-spec §4.5).
 *
 * <pre>
 * POST /api/v1/assistant/chat                           →  POST {aiAgentBase}/api/v1/assistant/chat
 * GET  /api/v1/assistant/conversations?tenantId&limit   →  GET  {aiAgentBase}/api/v1/assistant/conversations?tenantId&limit
 * GET  /api/v1/assistant/conversations/{conversationId} →  GET  {aiAgentBase}/api/v1/assistant/conversations/{id}?tenantId
 * </pre>
 *
 * <p>The Assistant is READ-ONLY (CLAUDE.md guardrail #2). The BFF does not
 * enrich, transform, or aggregate — it forwards bytes. Per-tenant rate
 * limiting at the BFF edge ({@code RateLimitFilter}) protects the downstream
 * cost cap.
 *
 * <p>Tenant scope: Phase A's {@code BypassAuthFilter} stamps {@code OWNER}
 * on the MDC + request attribute; this controller forwards
 * {@code X-Tenant-Id: OWNER} so ai-agent-service can apply its own filter.
 */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantRouteController {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantRouteController.class);

    private final RestClient client;

    public AssistantRouteController(@Qualifier("aiAgentRestClient") RestClient client) {
        this.client = client;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chat(
            @RequestBody String body, @RequestParam(value = "tenantId", required = false) String tenantParam) {
        String tenantId = resolveTenant(tenantParam);
        LOG.debug("proxying POST /api/v1/assistant/chat tenant={}", tenantId);
        try {
            String resp = client.post()
                    .uri("/api/v1/assistant/chat")
                    .header(BypassAuthFilter.TENANT_HEADER, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(resp);
        } catch (RestClientResponseException e) {
            LOG.warn("ai-agent-service returned {} on /chat for tenant={}", e.getStatusCode(), tenantId);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> listConversations(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        String resolvedTenant = resolveTenant(tenantId);
        UriComponentsBuilder uri =
                UriComponentsBuilder.fromPath("/api/v1/assistant/conversations").queryParam("tenantId", resolvedTenant);
        if (limit != null) {
            uri.queryParam("limit", limit);
        }
        String path = uri.build().toUriString();
        LOG.debug("proxying GET {} tenant={}", path, resolvedTenant);
        try {
            String body = client.get()
                    .uri(path)
                    .header(BypassAuthFilter.TENANT_HEADER, resolvedTenant)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(body);
        } catch (RestClientResponseException e) {
            LOG.warn("ai-agent-service returned {} on /conversations for tenant={}", e.getStatusCode(), resolvedTenant);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<?> getConversation(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String resolvedTenant = resolveTenant(tenantId);
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/api/v1/assistant/conversations/" + conversationId)
                .queryParam("tenantId", resolvedTenant);
        String path = uri.build().toUriString();
        LOG.debug("proxying GET {} tenant={}", path, resolvedTenant);
        try {
            String body = client.get()
                    .uri(path)
                    .header(BypassAuthFilter.TENANT_HEADER, resolvedTenant)
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok().body(body);
        } catch (RestClientResponseException e) {
            LOG.warn(
                    "ai-agent-service returned {} on /conversations/{} for tenant={}",
                    e.getStatusCode(),
                    conversationId,
                    resolvedTenant);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    /**
     * Resolve the effective tenantId. Phase A: BypassAuthFilter has already
     * gated and stamped OWNER on the MDC; we accept either an explicit query
     * param or fall back to the bypass tenant. Phase B's Auth0JwtFilter will
     * stamp the JWT-derived tenant which the same code path reads.
     */
    private static String resolveTenant(String fromQuery) {
        if (fromQuery != null && !fromQuery.isBlank()) {
            return fromQuery;
        }
        // Phase A bypass tenant — mirrors BypassAuthFilter's contract.
        return BypassAuthFilter.OWNER_TENANT;
    }
}
