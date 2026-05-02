# ADR-0006: AI Agent Service Anthropic integration — hand-rolled HTTP client

**Status**: accepted
**Date**: 2026-05-02
**Deciders**: owner
**Supersedes**: closes the "decides between LangChain4J adapter, Spring AI starter, or hand-rolled HTTP client" comment in `services/ai-agent-service/build.gradle.kts` and the `# anthropic-sdk` placeholder in `gradle/libs.versions.toml`.

## Context

Phase 4 starts the AI Agent Service substrate (architecture-spec §4). The service will host four roles:

- Pre-Trade Sentinel (Claude Haiku 4.5) — Phase 5
- Trade Narrator (Claude Sonnet 4.6) — Phase 4
- Conversational Assistant (Claude Sonnet 4.6) — Phase 5
- Daily Reviewer (Claude Opus 4.7) — Phase 4

Phase 0 left three options open for the Anthropic SDK choice:

1. **LangChain4J** with the Anthropic adapter — popular, batteries-included, but heavyweight; pulls in vector-store + chain abstractions we do not need; Anthropic-specific features (prompt caching headers, beta features) lag the official API.
2. **Spring AI** Anthropic starter — would pull Spring into a Quarkus module (architecture-spec §15 locks Quarkus for hot-path services; ai-agent-service is Tier 2 mixed but bound to Quarkus per §8 Service Catalog).
3. **Official `anthropic-java` SDK** — not on Maven Central as of Phase 0 (April 2026 check). The `gradle/libs.versions.toml` Phase 0 entry was a placeholder; the artifact `com.anthropic:anthropic-java` does not resolve.
4. **Hand-rolled JDK HttpClient** against the public REST `POST /v1/messages` endpoint. This is the same shape `AlpacaTradingClient` uses for the broker (ADR-0004 pattern).

Three hard constraints from `CLAUDE.md` and `architecture-spec.md` §4 inform the choice:

- **Replay-parity** (Principle #2): the AI call path must be deterministic-mockable for the replay harness. A handful of test fixtures should replay identical Sentinel decisions across runs.
- **Cost-cap as code** (architecture-spec §4.8 + `ai-prompt-management` skill rule #4): a HARD pre-flight check must run BEFORE any HTTP call so the Anthropic bill cannot run away.
- **Audit log** (architecture-spec §4.10): every call writes a row to `audit_log.ai_calls` with prompt hash, tokens, cost, latency, cache hit ratio.

A heavyweight SDK fights all three: replay parity needs a stable test seam (LangChain4J's chain abstraction obscures the HTTP boundary); cost-cap pre-flight needs to short-circuit before any call (most SDKs assume "make the call, then handle errors"); audit needs raw token/cost numbers, not chain-level summaries.

## Decision

**Hand-rolled HTTP client mirroring `AlpacaTradingClient`'s pattern.**

### 1. JDK `HttpClient` + sealed `AnthropicResponse` + `Fetcher` test seam

`com.levelsweep.aiagent.anthropic.AnthropicClient` is `@ApplicationScoped`, exposes a single `submit(AnthropicRequest)` method, and returns a sealed `AnthropicResponse` (`Success | RateLimited | Overloaded | InvalidRequest | TransportFailure | CostCapBreached`). Same shape as the Alpaca client's `OrderSubmission` sealed interface.

Headers:

- `x-api-key` — value from `anthropic.api-key` config; never logged, never serialized into the body
- `anthropic-version: 2023-06-01` — pinned; bumping requires an ADR update
- `content-type: application/json`
- `anthropic-beta: prompt-caching-2024-07-31` — gated on `anthropic.enable-prompt-caching` config flag

Endpoint: `https://api.anthropic.com/v1/messages` (configurable via `anthropic.base-url` for stub / regional override).

A package-private `Fetcher` interface wraps the `HttpClient.send` call so tests inject canned responses. NO real Anthropic call in any test.

### 2. Sealed `AnthropicResponse` for exhaustive failure handling

Every documented architecture-spec §4.9 failure mode maps to a variant:

| Failure | Variant | Default behavior at caller |
|---|---|---|
| 2xx valid | `Success` | normal flow |
| 429 rate-limited | `RateLimited` | Sentinel ALLOW; Narrator/Reviewer retry |
| 503/529 overloaded | `Overloaded` | Sentinel ALLOW; Narrator/Reviewer retry |
| 4xx invalid request | `InvalidRequest` | no retry; alert |
| network/parse | `TransportFailure` | Sentinel ALLOW; Narrator queue |
| cost cap pre-flight breach | `CostCapBreached` | Sentinel ALLOW; Narrator/Reviewer skip |

Java pattern matching forces every caller to handle every variant — a new failure class added later cannot be silently dropped (the switch becomes non-exhaustive at compile time).

### 3. Per-tenant per-day per-role cost cap as a HARD pre-flight check

`com.levelsweep.aiagent.cost.DailyCostTracker` is `@ApplicationScoped` and combines:

- An in-memory `ConcurrentHashMap<(tenantId, role, date), BigDecimal>` accumulator
- Write-through to Mongo `audit_log.daily_cost` per `recordCost` call
- Lazy bootstrap from Mongo via `sumByDay` on first observation of a `(tenant, role, date)` key

Before any HTTP call, `AnthropicClient` calls `wouldExceedCap` and short-circuits to `CostCapBreached` if the projected cost would push the bucket over its configured cap (`anthropic.cost-cap-usd-per-tenant-per-day.{role}`). This is the architecture-spec §4.8 cap enforced as code, not just monitoring.

Date roll-over runs against `ZoneId.of("America/New_York")` per architecture-spec Principle #10 (wall-clock time discipline). Tests pin the `Clock` and verify the rollover at 00:00 ET.

### 4. AI call audit log — split between hot summary + cold prompt body

`com.levelsweep.aiagent.audit.AiCallAuditWriter` writes two collections:

- `audit_log.ai_calls` — compact summary per architecture-spec §4.10: tenant, role, model, prompt hash, tool calls, response, tokens, cost, latency, cache hit ratio, occurred_at, trace_id, outcome label
- `audit_log.ai_prompts` — full prompt body keyed by `prompt_hash` (SHA-256 of canonical form). Phase 4 stores in Mongo for dev simplicity; **Phase 7 follow-up** migrates this to Azure Blob (architecture-spec §4.10 calls for "cold blob storage").

The split keeps the hot collection compact (audit reads stream summaries fast) while preserving full reproducibility for reviewer/regulator inspection.

### 5. Deterministic prompt hashing

`com.levelsweep.aiagent.audit.PromptHasher` produces a stable SHA-256 over `(model, system prompt, messages, tools)` in canonical form. The hash is the join key between `ai_calls` and `ai_prompts`, and the basis for the future replay-harness assertion that "same recorded inputs → same Sentinel decision" (Phase 5 exit gate per architecture-spec §21 row 5).

Any change to the canonical form is replay-breaking and requires an ADR amendment plus a fixture version bump.

### 6. `temperature = 0` everywhere by default

All AI calls in production paths run with `temperature = 0`. Replay-parity requires same input → same output; non-zero temperature breaks the contract. The `AnthropicRequest` record validates `temperature ∈ [0, 1]` so future deliberate fuzzing (Phase 7 chaos) can opt in, but the convenience constructor pins zero.

### 7. Retry policy parameterized; default no-retry (Sentinel)

Per architecture-spec §4.9, Sentinel-class calls have NO retries (fail-fast, fail-open). Narrator and Reviewer get one bounded retry on 429/529 — wired in the same PR that adds those callers (S2 Narrator, S3 Reviewer). The S1 client surface accepts a `retryEnabled` parameter; the production retry library binding lands with the first caller that needs it.

## Consequences

### Positive

- **Replay parity preserved**: the `Fetcher` seam returns deterministic fixture JSON in tests. Audit prompt hash is reproducible across JVMs.
- **Cost cap is real, not aspirational**: hard pre-flight check, not an after-the-fact bill alert. Mongo write-through means the cap survives restarts.
- **Failure modes are exhaustive at compile time**: the sealed `AnthropicResponse` forces callers to handle every architecture-spec §4.9 case.
- **Single Anthropic writer**: every AI call — Sentinel, Narrator, Assistant, Reviewer — flows through one component. Phase 7 adds CB / metrics / DLQ in one place.
- **No transitive bloat**: zero new third-party deps. The existing Quarkus + Jackson + Mongo plumbing is sufficient.
- **Pattern consistency**: mirrors `AlpacaTradingClient` (ADR-0004) — operators / reviewers familiar with one client know the other.
- **Audit-ready**: every call writes to `audit_log.ai_calls` per architecture-spec §4.10. Phase 7 migration to cold blob is a swap of one repository, not a refactor of the call path.

### Negative

- **More code than a packaged SDK**: ~1500 lines vs. a one-line dependency add. Mitigated by direct mapping to architecture-spec §4 — every line maps to a documented requirement.
- **Anthropic API surface is a moving target**: the `anthropic-version: 2023-06-01` header is pinned, but new features (extended thinking, citations, etc.) require client changes. Mitigated by sealed response (new failure modes are caught) and ADR discipline (model bumps require an ADR update).
- **Streaming not yet supported**: S1 ships request/response only. Conversational Assistant (S5) needs streaming for the chat UX — that's a separate code path added in Phase 5, layered onto the same `Fetcher` seam.
- **Retry library deferred**: `retryEnabled` on `submit` is a parameter today; the actual `Resilience4j` wiring lands with Narrator/Reviewer in S2/S3. No production code path requires retry in S1.

## Alternatives Considered

- **LangChain4J's Anthropic adapter** — rejected. Pulls in chain + vector-store abstractions we don't use. Anthropic-specific features (prompt caching, tool-use forcing via `tool_choice`) lag the official API. Replay-parity story is harder because the chain-level abstraction obscures the HTTP boundary tests need to mock.
- **Spring AI Anthropic starter** — rejected. Would pull Spring into a Quarkus module — direct conflict with the architecture-spec §8 Quarkus pin for `ai-agent-service`. Operational overhead of mixing Spring + Quarkus in one module exceeds whatever Spring AI saves.
- **Official `anthropic-java` SDK** — deferred. Not on Maven Central as of April 2026; placeholder catalog entry in `gradle/libs.versions.toml` was hallucinated coordinates that don't resolve. If a first-party SDK ships before Phase 5 with full prompt-caching + tool-use coverage, an ADR amendment will reconsider.
- **Reactive Mongo client (`quarkus-mongodb-reactive`)** — rejected. The cost / audit writes are off the trade hot path (AI calls are not on the order-placement latency budget). Sync MongoClient matches the pattern in `MongoBarRepository` and avoids reactive plumbing complexity.
- **Single audit collection (no cold blob split)** — rejected. Architecture-spec §4.10 explicitly requires "Prompt hash (full prompt in cold blob storage)" — splitting hot summary from cold prompt is not optional.
- **Cost cap as Prometheus alert only** — rejected. `ai-prompt-management` skill rule #4: "Per-tenant cost cap enforced in code (not just monitoring)". An alert-only cap allows a runaway prompt to cost real money before ops sees the alert; the pre-flight check in code is the only safe form.

## References

- `architecture-spec.md` §4 (full): conceptual model, agent roles, tool surface, cost model, failure modes, observability, compliance stance
- `CLAUDE.md` AI Agent rules: "The AI cannot place orders. Only the Execution Service places orders. The AI's only write into the trade saga is the Pre-Trade Sentinel veto channel (confidence ≥ 0.85 required)."
- `.claude/skills/ai-prompt-management/SKILL.md` — Anthropic rules, cost caps, advisory-not-advisor framing
- ADR-0004 (Alpaca single provider) — same hand-rolled HTTP client pattern that informs this decision
- `services/execution-service/src/main/java/com/levelsweep/execution/alpaca/AlpacaTradingClient.java` — the mirror for shape, sealed-response handling, and `Fetcher` test seam
- Anthropic Messages API: https://docs.anthropic.com/en/api/messages (REST `POST /v1/messages`)
