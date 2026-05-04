package com.levelsweep.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Notification Service entry point — Phase 6 Kafka fan-out.
 *
 * <p>Cold-path Spring Boot 3.x service per CLAUDE.md tech stack and
 * architecture-spec §9 (Notification Service is Tier 2 cold). Subscribes
 * to the {@code notifications} Kafka topic (architecture-spec §12.1) and
 * dispatches each event to enabled delivery channels:
 *
 * <ul>
 *   <li><b>Email</b> (Phase 6, live) — SMTP via Spring's {@code JavaMailSender}.
 *       Phase A: configured for the owner's mailbox. When the SMTP host is
 *       unset, the dispatcher logs-only — useful for dev clusters that
 *       don't yet have an SMTP relay.</li>
 *   <li><b>SMS</b> (Phase 7, stubbed) — Twilio integration ships in Phase 7.
 *       The Phase 6 stub logs and short-circuits.</li>
 * </ul>
 *
 * <p>Per architecture-spec §6 (Notification delivery CAP profile = AP):
 * eventually-delivered, deduplicated by consumer. The notification service
 * achieves the dedupe by hashing {@code (tenantId, eventId)} into a
 * sha256 dedupe key and inserting into a Mongo {@code notifications.outbox}
 * collection with a unique index — duplicate redeliveries from Kafka
 * (consumer rebalance, partition reassign) DuplicateKeyException out and
 * the dispatch path short-circuits.
 *
 * <p>Multi-tenant: every event carries {@code tenantId} (validated non-blank
 * at the shared-domain record level), every outbox row is per-tenant scoped,
 * and the Mongo unique index is on the tenant-prefixed dedupe key. Phase B
 * per-user OAuth + per-user channel preferences land in Phase 7.
 */
@SpringBootApplication
@EnableKafka
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("notification-service starting (Phase 6: notifications consumer + email/SMS dispatcher)");
        SpringApplication.run(Application.class, args);
    }
}
