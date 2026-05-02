package com.levelsweep.aiagent.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Anthropic tool-use protocol descriptor (architecture-spec §4.5). Mirrors the
 * Anthropic REST {@code tools[]} schema: a tool is identified by {@code name},
 * carries a human-readable {@code description} the model uses to decide whether
 * to call it, and a JSON Schema {@code input_schema} the model fills in.
 *
 * <p>Phase 4 S1 ships the type so the {@link AnthropicRequest} contract is
 * shaped correctly today; the actual tool registry lands in Phase 5 (Sentinel
 * + Assistant). Including the type now avoids a breaking change to the
 * request record when the first tool is wired.
 *
 * <p>Per-tenant scoping is enforced at the tool-method boundary, NOT here —
 * the schema describes what the model sees, the implementation enforces who
 * can call what (architecture-spec §4.4).
 */
public record AnthropicTool(String name, String description, JsonNode inputSchema) {

    public AnthropicTool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
    }
}
