package com.levelsweep.aiagent.narrator;

import java.time.Instant;
import java.util.Objects;

/**
 * Inbound request to {@link TradeNarrator#narrate(NarrationRequest)}. The
 * narrator never reads the raw shared-domain trade event records — instead the
 * relevant CDI / Kafka listeners flatten the payload into a compact JSON
 * fragment in {@link #eventPayload}. Two consequences:
 *
 * <ul>
 *   <li>The narrator stays event-shape-agnostic. New event types land by
 *       extending the {@link NarrationPromptBuilder} template matrix and the
 *       listener — never by changing the narrator core.</li>
 *   <li>The {@code eventPayload} string IS the deterministic input to the
 *       prompt template. Two runs of the same listener over the same shared
 *       event produce bit-identical {@link NarrationRequest} records, which
 *       in turn produce bit-identical prompts (replay parity, ADR-0006 §6 +
 *       architecture-spec Principle #2).</li>
 * </ul>
 *
 * <p>{@link #eventType} is one of the constants in
 * {@link NarrationPromptBuilder} ({@code "FILL"}, {@code "REJECTED"},
 * {@code "STOP"}, {@code "TRAIL_BREACH"}, {@code "EOD_FLATTEN"},
 * {@code "ORDER_SUBMITTED"}). Unknown types throw at construction time so a
 * bug in a listener surfaces immediately, not as a confusing prompt template.
 *
 * @param tenantId      per-tenant cost-cap + audit scope (never blank)
 * @param eventType     one of the {@link NarrationPromptBuilder#KNOWN_EVENT_TYPES}
 * @param eventPayload  compact JSON / key=value string built by the listener
 * @param tradeId       ties the resulting narrative back to a single trade
 * @param occurredAt    domain timestamp of the inbound event (NOT now)
 */
public record NarrationRequest(
        String tenantId, String eventType, String eventPayload, String tradeId, Instant occurredAt) {

    public NarrationRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventPayload, "eventPayload");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (!NarrationPromptBuilder.KNOWN_EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException(
                    "unknown eventType: " + eventType + "; must be one of " + NarrationPromptBuilder.KNOWN_EVENT_TYPES);
        }
    }
}
