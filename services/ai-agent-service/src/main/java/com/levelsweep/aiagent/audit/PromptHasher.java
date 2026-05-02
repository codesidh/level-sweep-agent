package com.levelsweep.aiagent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.levelsweep.aiagent.anthropic.AnthropicMessage;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicTool;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Deterministic SHA-256 over an {@link AnthropicRequest}. The hash is stored
 * in {@code audit_log.ai_calls.prompt_hash} (architecture-spec §4.10) and
 * keyed against the cold-blob copy of the full prompt — the audit row stays
 * compact while the prompt itself is recoverable for reviewer / regulator
 * inspection.
 *
 * <p>Replay-parity: the hash function MUST produce the same digest for the
 * same logical inputs across runs and JVMs. The canonicalization rules below
 * are explicit so future schema changes are reviewable. ANY change to the
 * canonical form is a replay-breaking change and requires:
 *
 * <ul>
 *   <li>An ADR update (this is part of ADR-0006's scope)</li>
 *   <li>A version bump on the recorded fixtures</li>
 *   <li>A replay-parity test pass at ≥99% on 30 sessions</li>
 * </ul>
 *
 * <p><b>Canonical form</b>:
 *
 * <pre>
 *   model:&lt;model&gt;\n
 *   system:&lt;system prompt verbatim&gt;\n
 *   message:&lt;role&gt;:&lt;content&gt;\n   (one per message, in original order)
 *   tool:&lt;name&gt;:&lt;description&gt;:&lt;input_schema toString()&gt;\n  (one per tool, in original order)
 * </pre>
 *
 * <p>Order is intentionally preserved as-given by the caller — message order
 * carries semantic meaning. Tool order does not, but enforcing arrival-order
 * makes the hash trivially reproducible without sorting plumbing. Callers that
 * want order-independent tool hashing must sort before constructing the
 * request.
 */
public final class PromptHasher {

    private PromptHasher() {
        // utility
    }

    /** SHA-256 hex digest of the canonical form of a request. */
    public static String hash(AnthropicRequest request) {
        Objects.requireNonNull(request, "request");
        StringBuilder sb = new StringBuilder(256);
        sb.append("model:").append(request.model()).append('\n');
        sb.append("system:").append(request.systemPrompt()).append('\n');
        for (AnthropicMessage m : request.messages()) {
            sb.append("message:")
                    .append(m.role())
                    .append(':')
                    .append(m.content())
                    .append('\n');
        }
        for (AnthropicTool t : request.tools()) {
            sb.append("tool:")
                    .append(t.name())
                    .append(':')
                    .append(t.description())
                    .append(':')
                    .append(canonicalSchema(t.inputSchema()))
                    .append('\n');
        }
        return sha256Hex(sb.toString());
    }

    /**
     * JsonNode#toString() is semantically stable across versions of Jackson
     * for the same logical content; routed through here so any future need
     * to canonicalize key ordering is centralized.
     */
    private static String canonicalSchema(JsonNode node) {
        return node.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JVM since Java 1.4.2.
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }
}
