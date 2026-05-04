package com.levelsweep.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Journal &amp; State Service entry point — Phase 6 audit aggregator.
 *
 * <p>Cold-path Spring Boot 3.x service per CLAUDE.md tech stack. Subscribes to
 * the {@code tenant.fills} Kafka topic (live; produced by execution-service
 * Phase 3 Step 3) and the {@code tenant.events.*} family (gap-stubbed; the
 * producer side ships in Phase 5/6 follow-up PRs from execution-service /
 * decision-engine — see {@code TradeEventConsumer}'s class header).
 *
 * <p>Per architecture-spec §13.4 the journal is the canonical writer of the
 * audit trail: every consumed event lands as one append-only row in the
 * Mongo {@code audit_log.events} collection. Operators query the trail via
 * {@code GET /journal/{tenantId}}; the dashboard's "Trade Journal" pane is
 * the Phase 7 client of that endpoint.
 *
 * <p>CAP profile (architecture-spec §6): <b>CP for write, AP for query</b>.
 * The Mongo writes use w=majority semantics (default Spring Data Mongo);
 * the GET endpoint tolerates secondary lag. Replay parity is not a concern —
 * the journal is downstream of the Decision Engine, not part of it.
 *
 * <p>Multi-tenant: every consumed event carries {@code tenant_id} (validated
 * non-blank at the shared-domain record level), every Mongo document keys
 * on it, and every query path is per-tenant scoped. Phase B per-user OAuth
 * + Auth0 JWT validation lands in Phase 5; Phase A is owner-only and skips
 * the auth check.
 */
@SpringBootApplication
@EnableKafka
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("journal-service starting (Phase 6: tenant.fills consumer + audit log writer)");
        SpringApplication.run(Application.class, args);
    }
}
