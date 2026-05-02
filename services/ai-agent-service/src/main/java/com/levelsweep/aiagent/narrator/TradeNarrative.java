package com.levelsweep.aiagent.narrator;

import java.time.Instant;
import java.util.Objects;

/**
 * Persistent record produced by {@link TradeNarrator}: a 1-3 sentence
 * post-trade explanation surfaced on the trader dashboard + journal
 * (architecture-spec §4.3.2). One narrative per inbound trade event — multiple
 * narratives may exist for a single {@code tradeId} (one per fill, one per
 * rejection, one per stop trigger, etc.) ordered by {@link #generatedAt}.
 *
 * <p>{@code modelUsed} is recorded for cost reconciliation + future model
 * migration audits — Phase 5 may swap Sonnet 4.6 for a newer Sonnet revision,
 * and the journal must be able to attribute existing narratives to the model
 * that produced them.
 *
 * <p>{@code promptHash} is the SHA-256 prompt fingerprint (same value as the
 * {@code audit_log.ai_calls.prompt_hash} row) — joins narratives back to the
 * full prompt body in the cold blob store, supporting the architecture-spec
 * §4.10 reproducibility requirement and the replay-parity assertion that
 * "same trade event → same prompt → same narrative".
 *
 * @param tenantId    audit + tenant isolation scope
 * @param tradeId     trade lifecycle identifier from the inbound domain event
 * @param narrative   1-3 sentence plain-English text returned by the model
 * @param generatedAt wall-clock instant when {@link TradeNarrator} produced this record
 * @param modelUsed   Anthropic model id ({@code claude-sonnet-4-6} for Phase 4)
 * @param promptHash  SHA-256 of the canonical request — see {@code PromptHasher}
 */
public record TradeNarrative(
        String tenantId, String tradeId, String narrative, Instant generatedAt, String modelUsed, String promptHash) {

    public TradeNarrative {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(narrative, "narrative");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(modelUsed, "modelUsed");
        Objects.requireNonNull(promptHash, "promptHash");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (narrative.isBlank()) {
            throw new IllegalArgumentException("narrative must not be blank");
        }
        if (modelUsed.isBlank()) {
            throw new IllegalArgumentException("modelUsed must not be blank");
        }
        if (promptHash.isBlank()) {
            throw new IllegalArgumentException("promptHash must not be blank");
        }
    }
}
