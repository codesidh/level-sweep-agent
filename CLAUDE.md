# LevelSweepAgent

> AI-agentic 0DTE SPY options trader implementing the four-level liquidity sweep strategy with EMA/ATR confluence. Multi-tenant-shaped, single-tenant-operated (Phase A).

## ⚠️ Critical Guardrails (READ FIRST)

1. **Phase A only.** Single-user (owner) operation. Phase B (multi-tenant SaaS) is **gated on legal counsel completing RIA / broker-dealer review**. Phase B code paths must stay behind feature flags (default OFF).
2. **The AI cannot place orders.** Only the Execution Service places orders. The AI's only write into the trade saga is the Pre-Trade Sentinel veto channel (confidence ≥ 0.85 required).
3. **Fail-closed on the order path.** Any uncertainty → halt new entries. Existing positions continue under deterministic stop-loss / trailing rules.
4. **Multi-tenant from day one.** Every persistent entity carries `tenant_id`. No global mutable state. Every Kafka topic keyed by tenant.
5. **Determinism is the contract.** Decision Engine changes must preserve replay parity (≥ 99% on 30 historical sessions).
6. **Never log or commit credentials.** Alpaca tokens live encrypted in Key Vault, scoped per tenant.
7. **No real money in tests.** All tests run against Alpaca paper or mocks.
8. **Scope-gated soak — not phase-gated.** Every build phase ships to Azure dev and soaks ≥5 RTH sessions against the live external deps it introduces (Alpaca SIP stocks WS at Phase 1, Alpaca options + execution at Phase 3, Anthropic at Phase 4, …). P0/P1 incidents during soak reset the counter. A subsequent-phase PR may merge to `main` while a prior phase is still soaking **iff all three conditions hold**: (a) it doesn't modify any service currently in active soak (or its Helm chart, deploy workflow, or container image); (b) it doesn't change shared infrastructure used by the soaking service (Strimzi/Kafka topology, AKS network policy, DataSource shape, Key Vault layout); (c) any newly exposed runtime path stays default-OFF behind a feature flag (already required by guardrail #1 for Phase B; extended here to Phase A overlaps). Scope-failing PRs land on a long-lived `staging/<phase>` branch with full CI but no main merge until the relevant soak clears. See `architecture-spec.md` §21.1.

## 📚 Authoritative Docs (read before coding)

- **Strategy**: [`requirements.md`](requirements.md)
- **Architecture**: [`architecture-spec.md`](architecture-spec.md)
- **ADRs**: [`adr/`](adr/)

## 🏗️ Tech Stack (locked)

- **JDK 21 LTS** with virtual threads + ZGC
- **Quarkus** for hot-path services (GraalVM native image)
- **Spring Boot 3.x** for cold-path services
- **Build**: Gradle (multi-module)
- **Messaging**: Apache Kafka via Strimzi on AKS
- **Storage**: MS SQL (financial), MongoDB (audit), SQLite (per-pod ephemeral)
- **AI**: Anthropic Claude — Haiku 4.5 (Sentinel), Sonnet 4.6 (Narrator/Assistant), Opus 4.7 (Reviewer)
- **Frontend**: Angular (latest) + Tailwind CSS, deployed to Azure Static Web Apps
- **Cloud**: Azure (AKS + APIM + Key Vault + App Insights)
- **IaC**: Terraform; **CI/CD**: GitHub Actions (OIDC to Azure)
- **Broker**: Alpaca (Phase A: owner token; Phase B: per-user OAuth)
- **IDP**: Auth0

## 📦 Service Topology

| Service | Path | Tier | Hot? |
|---|---|---|---|
| Market Data Service | hot | 2 | yes |
| Decision Engine (indicator + signal + risk + strike + saga) | hot | 2 | yes |
| Execution Service (Alpaca) | hot | 2 | yes |
| AI Agent Service (Sentinel + Narrator + Assistant + Reviewer) | mixed | 2 | sentinel sync |
| Journal & State Service | warm | 2 | no |
| User & Config / Projection / Calendar / Notification | cold | 2 | no |
| API Gateway / BFF | edge | 2 | no |

## 🔁 Build & Test Commands

```bash
./gradlew build                       # compile + unit test
./gradlew test                        # unit tests only
./gradlew integrationTest             # integration tests (docker-compose)
./gradlew replayTest                  # Decision Engine replay parity
./gradlew nativeBuild                 # GraalVM native image (hot-path only)
./gradlew :execution-service:bootRun  # run a single service locally
docker-compose up -d                  # local Kafka + Mongo + MS SQL
```

## 📓 Commit Convention

- **Conventional Commits**: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`
- Reference ADR if architectural: `feat(saga): add sentinel veto step (ADR-0002)`
- Reference issue if bugfix: `fix(execution): retry exit on alpaca 502 (#42)`

## 🧪 Testing Discipline

| Layer | Coverage | Notes |
|---|---|---|
| Unit | ≥ 80% on business logic | Pure functions; in-process FSM ops |
| Integration | All cross-service paths | Dockerized stack |
| Replay | All Decision Engine changes | ≥ 99% parity on 30 sessions |
| Chaos | Per-service failure injection | Verify clean resume |
| Soak | 5-day continuous paper run | Pre-Phase 8 entry gate |

## 📖 Glossary

| Term | Meaning |
|---|---|
| **PDH / PDL** | Previous day high/low (RTH 09:30–16:00 ET) |
| **PMH / PML** | Pre-market high/low (16:01 prior day → 09:29 ET) |
| **EMA(13/48/200)** | Exponential moving averages on 2-min chart |
| **ATR(14)** | Average True Range, 14-period daily |
| **0DTE** | Zero days to expiration option |
| **FSM** | Finite State Machine |
| **CB** | Circuit Breaker (Resilience4j) |
| **CP / AP** | Consistency-Partition / Availability-Partition tradeoff |
| **RTH** | Regular Trading Hours (09:30–16:00 ET) |
| **Sentinel** | Pre-Trade veto AI agent (Claude Haiku 4.5) |
| **Saga** | Trade lifecycle orchestrator inside Decision Engine |
| **NBBO** | National Best Bid and Offer |
| **DLQ** | Dead-Letter Queue |
| **BFF** | Backend-for-Frontend |
| **OIDC** | OpenID Connect |
| **RIA** | Registered Investment Adviser |
| **BD** | Broker-Dealer |
| **17a-4** | SEC rule mandating broker-dealer record retention (6+ years, WORM storage for first 2 years) |
| **WORM** | Write-Once, Read-Many storage (compliance-grade immutable) |
| **APIM** | Azure API Management |
| **AKS** | Azure Kubernetes Service |
| **ACR** | Azure Container Registry |
| **B2C** | Business-to-Consumer (Auth0 / Azure AD flavor) |

## 🛡️ Skills

Skills auto-load by trigger phrase. See `.claude/skills/`.

**Always-relevant custom skills**:
- `trading-system-guardrails` — compliance, idempotency, audit, no-real-money-in-tests
- `fsm-discipline` — every state change goes through an FSM
- `saga-compensation` — every saga step has explicit compensation
- `ai-prompt-management` — Anthropic rules, cost caps, advisory-not-advisor
- `multi-tenant-readiness` — `tenant_id` on every entity; isolation tests
- `phase-a-b-feature-flags` — Phase B code stays behind flags until legal review
- `replay-parity` — Decision Engine changes require replay parity test
- `gradle-build-conventions` — version catalog, Gradle pinning, Spotless, dep verification
- `alpaca-trading-api` — Alpaca REST + WS reference (endpoints, auth, OCC symbols, order shapes); fast-load summary backed by `docs/alpaca-trading-api-skill.md`

**Inherited from `claude-code-java`**: java-code-review, security-audit, concurrency-review, performance-smell-detection, test-quality, architecture-review, solid-principles, design-patterns, clean-code, spring-boot-patterns, jpa-patterns, java-migration, logging-patterns, git-commit, changelog-generator, issue-triage, api-contract-review, maven-dependency-audit *(harmless if unused — we're on Gradle)*.
