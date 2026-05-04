package com.levelsweep.aiagent.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Conversational Assistant thread — Mongo-persisted per
 * {@code (tenantId, conversationId)} (architecture-spec §4.5 + ADR-0006).
 *
 * <p>The Assistant is the user-facing chat interface for the operator. It is
 * READ-ONLY (CLAUDE.md guardrail #2 — the AI cannot place orders): every turn
 * is a question about already-persisted state (trades, indicators, journal).
 * The system prompt enforces "you cannot take any action".
 *
 * <p>Schema:
 *
 * <pre>
 *   { tenant_id        : "OWNER",
 *     conversation_id  : "9f86d081-...",                  // UUID v4
 *     created_at       : ISODate(...),
 *     updated_at       : ISODate(...),
 *     turns            : [ {role, content, ts, cost_usd}, ... ],
 *     total_cost_usd   : "0.0421" }                       // running spend, audit-only
 * </pre>
 *
 * <p>{@code totalCostUsd} is audit-only; the cost cap is enforced GLOBALLY per
 * (tenant, day) via {@link com.levelsweep.aiagent.cost.DailyCostTracker}. A
 * runaway conversation cannot exceed the assistant's daily cap because the cap
 * check runs pre-flight on every turn.
 *
 * @param tenantId       audit + tenant isolation scope
 * @param conversationId UUID v4 — generated server-side on first turn
 * @param createdAt      first-turn wall-clock instant
 * @param updatedAt      latest-turn wall-clock instant (drives recency listing)
 * @param turns          alternating user/assistant turns in arrival order
 * @param totalCostUsd   running spend (audit-only)
 */
public record AssistantConversation(
        String tenantId,
        String conversationId,
        Instant createdAt,
        Instant updatedAt,
        List<AssistantTurn> turns,
        BigDecimal totalCostUsd) {

    public AssistantConversation {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(turns, "turns");
        Objects.requireNonNull(totalCostUsd, "totalCostUsd");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (totalCostUsd.signum() < 0) {
            throw new IllegalArgumentException("totalCostUsd must be non-negative");
        }
        // Defensive copy — turns are exposed to the BFF JSON serializer and the
        // record is shared across threads (Mongo write + REST read).
        turns = List.copyOf(turns);
    }
}
