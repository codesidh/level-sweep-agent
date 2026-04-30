# Local development guide

End-to-end recipe for running LevelSweepAgent on a developer laptop.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21 LTS** | Temurin, Liberica, GraalVM 23 — any 21 distribution. Set `JAVA_HOME`. |
| Docker | 24+ | With Compose V2 (`docker compose` subcommand). |
| Gradle | 8.10.x | Only needed once to generate `gradle-wrapper.jar` (see below). After that the wrapper handles itself. |
| Git | 2.40+ | |
| (optional) Node.js | 20 LTS | For Phase 6+ Angular frontend. |

Verify:
```bash
java -version    # openjdk version "21..."
docker --version
docker compose version
```

## 1. Bootstrap secrets

```bash
cp .env.example .env
```

Fill in real values for at least:
- `POLYGON_API_KEY` — required to ingest market data (Phase 1+).
- `ALPACA_KEY_ID` / `ALPACA_SECRET_KEY` — paper account is fine.
- `ANTHROPIC_API_KEY` — required for AI agents (Phase 4+).

Phase 0 does not actually call any of these; placeholders are fine until Phase 1.

## 2. Start the local infra stack

```bash
docker compose up -d
```

Wait for healthchecks to go green:
```bash
docker compose ps
```

Services and ports:

| Service | Port | URL |
|---|---|---|
| Kafka (PLAINTEXT, host) | 9092 | `localhost:9092` |
| Kafka (internal docker) | 9094 | `kafka:9094` |
| AKHQ (Kafka UI) | 8089 | http://localhost:8089 |
| MS SQL Server 2022 | 1433 | `sa` / `LevelSweep!2026` |
| MongoDB 7 | 27017 | `root` / `LevelSweep!2026` |

The MS SQL container runs `dev/sql/init.sql` on first boot and the Mongo
container runs `dev/mongo/init.js`. To force a re-init:

```bash
docker compose down -v       # ⚠ wipes the volumes
docker compose up -d
```

## 3. Generate the Gradle wrapper jar (one-time)

We don't commit `gradle-wrapper.jar` (it's a binary). Generate it once with a
system Gradle install:

```bash
# install gradle (mac/linux): brew install gradle / sdk install gradle 8.10.2
# windows: scoop install gradle / choco install gradle

gradle wrapper --gradle-version 8.10.2 --distribution-type bin
```

After this, `./gradlew` works on any machine with just a JDK.

## 4. Build everything

```bash
./gradlew build
```

This runs:
- `compileJava` for all modules
- `spotlessCheck` (Palantir Java format)
- `test` (Phase 0 = smoke tests asserting `true`)
- `jacocoTestReport`

## 5. Run a single service

Cold-path (Spring Boot):
```bash
./gradlew :services:user-config-service:bootRun
./gradlew :services:projection-service:bootRun
./gradlew :services:calendar-service:bootRun
./gradlew :services:notification-service:bootRun
./gradlew :services:api-gateway-bff:bootRun
```

Hot-path (Quarkus):
```bash
./gradlew :services:market-data-service:quarkusDev
./gradlew :services:decision-engine:quarkusDev
./gradlew :services:execution-service:quarkusDev
./gradlew :services:ai-agent-service:quarkusDev
./gradlew :services:journal-service:quarkusDev
```

Default ports:

| Service | Port |
|---|---|
| api-gateway-bff | 8080 |
| market-data-service | 8081 |
| decision-engine | 8082 |
| execution-service | 8083 |
| ai-agent-service | 8084 |
| journal-service | 8085 |
| user-config-service | 8090 |
| projection-service | 8091 |
| calendar-service | 8092 |
| notification-service | 8093 |

Health endpoints:
- Quarkus: `GET /q/health` (smallrye-health)
- Spring Boot: `GET /actuator/health`

## 6. Logs & MDC

Every service logs structured JSON with mandatory MDC keys `tenant_id` and
`trace_id` (per architecture-spec §18.3 and the `multi-tenant-readiness`
skill). Phase A defaults `tenant_id=OWNER`; `trace_id` is populated by the
OTel filter once instrumentation lands in Phase 1+.

## 7. Stopping the stack

```bash
docker compose down          # keep volumes
docker compose down -v       # wipe volumes (Mongo + MS SQL data lost)
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| MS SQL container restarts forever | Password policy. The default `LevelSweep!2026` meets length+complexity; if you change `MSSQL_SA_PASSWORD`, ensure ≥ 8 chars with mixed case, digit, symbol. |
| AKHQ shows "Connection refused" | Wait — Kafka takes ~30s on first boot. `docker compose logs kafka`. |
| `./gradlew` says `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` | You haven't run `gradle wrapper ...` yet. See step 3. |
| Quarkus dev mode complains about port already in use | Another container (or another Quarkus dev) is already on that port. Set `quarkus.http.port` env var. |
| Spotless fails on a file you didn't touch | `./gradlew spotlessApply` — Palantir reformatter will fix it. |

## Where to go next

- Read [`architecture-spec.md`](../architecture-spec.md) §21 for the build phase plan.
- Read [`CLAUDE.md`](../CLAUDE.md) for the guardrails.
- Pick a Phase 1 ticket from the project tracker.
