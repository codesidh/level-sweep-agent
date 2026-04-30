---
name: ai-prompt-management
description: Rules for Claude prompts, tool definitions, cost caps, and AI agent behavior. Use when writing or modifying any code that calls the Anthropic API, defines a Claude tool or tool definition, or composes a prompt for Sentinel/Narrator/Assistant/Reviewer. Triggers on Anthropic, Claude, prompt, tool use, tool definition, AI agent, sentinel, narrator, reviewer, daily reviewer.
---

# AI Prompt & Agent Management

The AI is an executor and explainer of the user's pre-configured strategy. It is NOT an investment advisor.

## MUST

1. **Prompts versioned in git**: every system prompt and tool definition lives in `prompts/` directory. Hash logged on every call.
2. **Anthropic prompt caching enabled**: system prompt + tool definitions + recent journal context tagged for caching. Target ≥ 70% cache hit rate.
3. **Tool-use protocol**: structured outputs via Anthropic tool calls. Never parse free-text responses for control flow.
4. **Per-tenant cost cap enforced** in code (not just monitoring).
   - **Pre-call**: reject if estimated cost (`prompt_tokens × in-rate + max_tokens × out-rate`) would exceed remaining budget.
   - **Post-call**: reconcile with actual `response.usage` (input + output tokens, including cache read/write) and persist to `audit_log.ai_calls`.
   - **Streaming**: accrue `input_tokens` at start, `output_tokens` on stream completion (including partial streams interrupted by error).
   - **On breach**: role degrades to no-op (Sentinel ALLOW; Narrator/Reviewer skip; Assistant disabled) until 00:00 ET.
5. **Confidence threshold for veto**: Sentinel vetoes only when `confidence ≥ 0.85`. Below threshold → ALLOW.
6. **Fail-open for AI**: any AI error (timeout, malformed JSON, 5xx) → ALLOW. Deterministic engine remains conservative on its own.
7. **Audit every call**: write to `audit_log.ai_calls` with role, model, prompt hash, tools invoked, response, tokens, cost, latency.
8. **Tenant scoping at the tool boundary**: every tool method takes implicit `tenant_id` from caller context; cross-tenant access throws.

## MUST NOT

1. ❌ Give an AI tool that places, cancels, or modifies orders.
2. ❌ Give an AI tool that mutates Trade / Risk / Session FSM state.
3. ❌ Generate "advice" language. Prefer "the strategy says X because Y" over "you should do X".
4. ❌ Auto-apply Reviewer's config-tweak proposals (Phase A: advisory only; user must approve).
5. ❌ Log raw user PII in prompts.
6. ❌ Use a different model from the spec. Sentinel = Haiku 4.5, Narrator/Assistant = Sonnet 4.6, Reviewer = Opus 4.7.
7. ❌ Bypass the cost cap. Even for "important" calls.
8. ❌ Ship a prompt change without an A/B test plan or replay equivalence check.

## Pattern (Sentinel call)

```java
SentinelDecision askSentinel(SignalContext ctx) {
    if (costGuard.exceedsCap(ctx.tenantId(), Role.SENTINEL)) {
        return SentinelDecision.allow("cost cap reached");
    }
    try {
        var response = anthropic.messages()
            .model("claude-haiku-4-5")
            .system(promptRegistry.system(SENTINEL))      // cached
            .tools(toolRegistry.read(SENTINEL))            // cached
            .toolChoice(ToolChoice.tool("decide"))
            .maxTokens(300)
            .timeout(Duration.ofSeconds(30))
            .send(ctx.toUserMessage());
        return SentinelDecision.from(response);
    } catch (Exception e) {
        log.warn("sentinel failed; defaulting allow", e);
        return SentinelDecision.allow("error: " + e.getClass().getSimpleName());
    }
}
```

## Models

| Role | Model | Reasoning |
|---|---|---|
| Sentinel | claude-haiku-4-5 | Speed-critical binary classification |
| Narrator | claude-sonnet-4-6 | Balanced; explanatory text |
| Assistant | claude-sonnet-4-6 | Chat UX with tool use |
| Reviewer | claude-opus-4-7 | Pattern recognition over journal |
