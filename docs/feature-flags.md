# Feature Flag Registry

> Per the `phase-a-b-feature-flags` skill, every code path that anticipates
> Phase B (multi-tenant SaaS) must live behind a feature flag that is **OFF by
> default** until legal counsel sign-off (see `architecture-spec.md` §2 / §20
> and `CLAUDE.md` guardrail #1).

## How flags are evaluated

Phase A reads flags from each service's `application.yml` under
`levelsweep.feature-flags.*`. Defaults are committed as `false`. In Phase B
they will be promoted to a per-tenant config record in the User & Config
Service (`tenant_configs.feature_flags` JSONB).

A flag flipping to `true` is a **deployment-gated change** and must:

1. Reference an ADR justifying the flip.
2. Pass replay parity (`./gradlew replayTest`) if the flag affects the
   Decision Engine.
3. Have a documented rollback (flip back to `false`; redeploy).

## Phase B flag inventory (initial — all OFF)

| Flag | Owner service(s) | Description | Unblocks | Default |
|---|---|---|---|---|
| `phase-b-multi-tenant-onboarding` | user-config-service, api-gateway-bff | User self-signup + tenant provisioning flow | Auth0 KYC + ToS pages live | `false` |
| `phase-b-public-signup` | api-gateway-bff | Expose `/auth/register` to public internet | Legal sign-off + WAF rules | `false` |
| `phase-b-alpaca-oauth` | execution-service, api-gateway-bff | Per-user Alpaca OAuth (replaces owner token) | Per-tenant Key Vault keys + token rotation tested | `false` |
| `phase-b-billing` | api-gateway-bff, user-config-service | Stripe subscription metering + plan limits | Billing platform decision (arch §22) | `false` |
| `phase-b-ai-suggestion-tools` | ai-agent-service | Enables Assistant `propose_*` tools (require user approval) | Approval-gate UX shipped | `false` |
| `phase-b-cross-tenant-memory` | ai-agent-service | Anonymized cross-tenant pattern memory (`agent_memory.global`) | Privacy review + DPA template | `false` |
| `phase-b-ops-agent` | ai-agent-service | "Operations Agent" role for ops team triage | On-call playbook authored | `false` |
| `phase-b-multi-instrument` | decision-engine, market-data-service | Fan-out beyond SPY (SPX, QQQ, IWM) | Replay parity demonstrated per symbol | `false` |
| `phase-b-discretionary-override` | decision-engine, api-gateway-bff | Optional manual approval gate before entries | UX + audit logging shipped | `false` |
| `phase-b-soc2-controls` | all | Stricter audit retention + access logging required by SOC 2 Type I | Auditor selection (arch §22) | `false` |

## Phase A operational flags (local toggles, not gated by legal)

| Flag | Owner | Description | Default |
|---|---|---|---|
| `phase-a-sentinel-enabled` | decision-engine, ai-agent-service | Enable Pre-Trade Sentinel veto channel (arch §4.3.1) | `true` once Phase 5 lands; `false` until then |
| `phase-a-narrator-enabled` | ai-agent-service | Trade Narrator post-trade narratives (arch §4.3.2) | `true` once Phase 4 lands |
| `phase-a-reviewer-enabled` | ai-agent-service | Daily Reviewer 16:30 ET batch (arch §4.3.4) | `true` once Phase 4 lands |

## How to add a flag

1. Add the entry to this table with owner service(s) and unblock criteria.
2. Add the property to each owning service's `application.yml`:
   ```yaml
   levelsweep:
     feature-flags:
       phase-b-multi-tenant-onboarding: false
   ```
3. Reference the flag in code via a typed `@ConfigProperty` / `@Value` —
   never read raw env vars at the call site.
4. Open an ADR if the flag changes a contract (Kafka topic, DB schema,
   tenant boundary).

## How to remove a flag

A flag may only be removed when:

- The Phase B path is fully launched, **or**
- The path is permanently abandoned (delete code with the flag).

Stale flags (`false` for >180 days with no roadmap) should be reviewed and
either committed to or deleted.
