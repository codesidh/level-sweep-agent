package com.levelsweep.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Projection Service entry point — Phase 6 Monte Carlo projection engine.
 *
 * <p>Cold-path Spring Boot 3.x service per CLAUDE.md tech stack. Serves two
 * REST endpoints over the {@code projections.runs} Mongo collection:
 *
 * <ul>
 *   <li>{@code POST /projection/run} — run a Monte Carlo simulation given
 *       starting equity, win-rate, position-sizing and horizon assumptions;
 *       persist the result keyed by (tenantId, requestHash) and return the
 *       p10/p25/p50/p75/p90 percentiles, mean, and ruin probability.</li>
 *   <li>{@code GET  /projection/last/{tenantId}} — return the most recent
 *       cached projection for the tenant (by occurredAt DESC).</li>
 * </ul>
 *
 * <p>Per architecture-spec §9 the Projection Service is Tier 2 cold-path; per
 * requirements.md §22 #4 the dashboard's default projection horizon is the
 * 12-month backtest window for SPY 0DTE. The Monte Carlo math is pure-Java,
 * deterministic for a given seed, and is the single hot CPU burst — there is
 * no Kafka consumer, no continuous workload, no scheduled job.
 *
 * <p>CAP profile (architecture-spec §6): <b>AP</b>. The cache is a read-side
 * convenience — a stale or eventually-consistent {@code last} response is
 * acceptable; the operator triggers a fresh run via POST when they need
 * up-to-date numbers.
 *
 * <p>Determinism: the engine accepts an optional {@code seed} on the request.
 * When absent, a deterministic SHA-256 hash of {@code (tenantId, normalized
 * request)} is used so identical requests from the same tenant produce
 * identical results. Tests assert this contract.
 *
 * <p>Multi-tenant: every persisted document carries {@code tenantId}; every
 * query is per-tenant scoped. Phase A operates only on the {@code OWNER}
 * tenant; Phase B per-user OAuth + Auth0 JWT validation lands in Phase 5
 * behind {@code phase-b-multi-tenant-onboarding}.
 *
 * <p>Authentication: <b>Phase A is owner-only and skips authentication</b>.
 * The BFF (api-gateway-bff) is the public ingress point and validates the
 * Auth0 JWT; this service is reachable inside-cluster only (NetworkPolicy
 * restricts ingress to the same namespace).
 */
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("projection-service starting (Phase 6: Monte Carlo projection engine)");
        SpringApplication.run(Application.class, args);
    }
}
