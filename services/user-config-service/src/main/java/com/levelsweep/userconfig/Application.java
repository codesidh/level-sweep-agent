package com.levelsweep.userconfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * User &amp; Config Service entry point — Phase 6 tenant configuration store.
 *
 * <p>Cold-path Spring Boot 3.x service per CLAUDE.md tech stack. Serves three
 * REST endpoints over the {@code tenant_config} MS SQL table:
 *
 * <ul>
 *   <li>{@code GET  /config/{tenantId}} — fetch the full tenant config row.</li>
 *   <li>{@code PUT  /config/{tenantId}} — full-replace update (idempotent).</li>
 *   <li>{@code GET  /config/{tenantId}/feature-flags} — flags-only read for
 *       hot-path consumers (Decision Engine, Execution Service) that don't
 *       need the entire payload.</li>
 * </ul>
 *
 * <p>Per architecture-spec §13.1 MS SQL is the system of record for financial
 * state, and tenant configuration lives there alongside trades, orders, fills,
 * and risk events. Persistence is hand-rolled JdbcTemplate (mirrors
 * execution-service's {@code EodFlattenAuditRepository}); no JPA, no Spring
 * Data JDBC repositories.
 *
 * <p>CAP profile (architecture-spec §6): <b>CP</b>. Config writes use the
 * default MS SQL transaction isolation; readers must see a consistent snapshot
 * because the Decision Engine consumes {@code position_size_pct} and
 * {@code daily_loss_budget} on every signal evaluation.
 *
 * <p>Multi-tenant: every endpoint is path-scoped by {@code tenantId}. There
 * is no cross-tenant query path; we do not ship one. Phase A operates only on
 * the {@code OWNER} tenant — bootstrap-seeded by {@code OwnerSeed} on startup
 * if missing.
 *
 * <p>Authentication: <b>Phase A is owner-only and skips authentication</b>.
 * The BFF (api-gateway-bff) is the public ingress point and validates the
 * Auth0 JWT; this service is reachable inside-cluster only (NetworkPolicy
 * restricts ingress to same-namespace pods). Phase B per-user OAuth + JWT
 * validation is gated behind the {@code phase-b-multi-tenant-onboarding}
 * feature flag and enabled when legal counsel completes the RIA / broker-
 * dealer review (CLAUDE.md guardrail #1).
 */
@SpringBootApplication
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("user-config-service starting (Phase 6: tenant config CRUD + feature flags)");
        SpringApplication.run(Application.class, args);
    }
}
