# LevelSweepAgent

> AI-agentic 0DTE SPY options trader implementing the four-level liquidity
> sweep strategy with EMA/ATR confluence. Multi-tenant-shaped, single-tenant-operated
> (Phase A).

## Read this first

| Doc | Purpose |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Critical guardrails. Read before opening any IDE. |
| [`requirements.md`](requirements.md) | Locked strategy specification (v1.0). |
| [`architecture-spec.md`](architecture-spec.md) | System architecture (v2.1, locked for Phase A). |
| [`adr/`](adr/) | Architecture Decision Records. |
| [`docs/feature-flags.md`](docs/feature-flags.md) | Phase-B-gated feature flags (default OFF). |
| [`docs/local-dev.md`](docs/local-dev.md) | Spinning the stack on your laptop. |

## Phase 0 status

This is the **scaffolding** drop. There is **no business logic** yet — every
service runs a hello-world entry point and a single smoke test asserting `true`.
Phase 1 wires the data layer; see `architecture-spec.md` §21 for the build phase
plan.

## Repo layout

```
LevelSweepAgent/
├── adr/                       # Architecture Decision Records
├── dev/                       # Local-dev seed scripts
│   ├── mongo/init.js          # Mongo bootstrap (collections per arch §13.2)
│   └── sql/init.sql           # MS SQL bootstrap (level_sweep DB + dev user)
├── docs/                      # Long-form docs
│   ├── feature-flags.md       # Registry of Phase B flags (all OFF)
│   └── local-dev.md           # docker-compose + gradle quickstart
├── gradle/                    # Wrapper + version catalog
│   ├── libs.versions.toml     # ALL pinned versions live here
│   └── wrapper/
├── infra/                     # Terraform skeleton (Azure)
│   ├── environments/{dev,stage,prod}/
│   └── modules/{aks,storage,networking,keyvault,observability}/
├── services/
│   ├── market-data-service/   # Quarkus, hot
│   ├── decision-engine/       # Quarkus, hot
│   ├── execution-service/     # Quarkus, hot
│   ├── ai-agent-service/      # Quarkus, mixed (Anthropic SDK)
│   ├── journal-service/       # Quarkus, warm
│   ├── user-config-service/   # Spring Boot, cold
│   ├── projection-service/    # Spring Boot, cold
│   ├── calendar-service/      # Spring Boot, cold
│   ├── notification-service/  # Spring Boot, cold
│   └── api-gateway-bff/       # Spring Boot, edge BFF
├── shared/
│   ├── shared-domain/         # Records, DTOs, enums
│   ├── shared-tenant/         # TenantContext, JWT filter, MT helpers
│   ├── shared-fsm/            # FSM abstract base + persistence pattern
│   └── shared-resilience/     # Resilience4j config helpers
├── .github/workflows/         # PR / main / IaC pipelines
├── docker-compose.yml         # Local Kafka + Mongo + MS SQL + AKHQ
├── settings.gradle.kts        # Multi-module include list
├── build.gradle.kts           # Root build (Spotless / Jacoco / Java 21)
├── gradle.properties
├── .env.example               # Copy → `.env` for local dev
└── .editorconfig
```

## Tech stack (locked — see arch-spec §15)

- **JDK 21 LTS** (virtual threads + ZGC; GraalVM native image for hot path)
- **Quarkus 3.15.x** for hot-path services
- **Spring Boot 3.3.x** for cold-path services
- **Gradle 8.10.x** multi-module with Kotlin DSL + version catalog
- **Apache Kafka** (Strimzi on AKS in prod; KRaft container locally)
- **MS SQL Server 2022** (financial system of record)
- **MongoDB 7** (audit + read models + agent memory)
- **Anthropic Claude** (Haiku 4.5 / Sonnet 4.6 / Opus 4.7)
- **Resilience4j**, **OpenTelemetry**, **Auth0**, **Alpaca**

## Quick start

```bash
# 1. Bootstrap secrets file
cp .env.example .env

# 2. Start local infra (Kafka + Mongo + MS SQL + AKHQ at http://localhost:8089)
docker-compose up -d

# 3. Generate the Gradle wrapper jar (one time, requires system gradle 8.x)
gradle wrapper --gradle-version 8.10.2 --distribution-type bin

# 4. Build everything
./gradlew build

# 5. Run a single service
./gradlew :services:user-config-service:bootRun
```

See [`docs/local-dev.md`](docs/local-dev.md) for details.

## Build & test

```bash
./gradlew build               # compile + spotless + unit tests
./gradlew test                # unit tests only
./gradlew integrationTest     # phase-1+ integration tests (no-op in Phase 0)
./gradlew replayTest          # decision-engine replay parity (Phase 2+)
./gradlew nativeBuild         # GraalVM native image (Phase 7+; stub today)
./gradlew ciCheck             # local CI: spotlessCheck + build for all modules
```

## Hard rules

1. **Phase A only.** Phase B is gated on legal review. All Phase B paths are
   feature-flagged off — see [`docs/feature-flags.md`](docs/feature-flags.md).
2. **The AI cannot place orders.** Only the Execution Service places orders.
3. **Fail-closed on the order path.** Any uncertainty → halt new entries.
4. **Multi-tenant from day 1.** Every entity carries `tenant_id`; every Kafka
   topic keyed by tenant. Every log line carries `tenant_id` + `trace_id`.
5. **No real money in tests.** Always Alpaca paper or mocks.
6. **Determinism.** Decision Engine changes must preserve replay parity ≥ 99%.

## License

Private / unreleased. Do not redistribute.
