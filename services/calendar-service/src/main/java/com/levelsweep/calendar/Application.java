package com.levelsweep.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Calendar Service entry point — Phase 6 trading-calendar service.
 *
 * <p>Cold-path Spring Boot 3.x service per CLAUDE.md tech stack. Architecture-spec
 * §9 places this at Tier 2 cold; it answers two questions for the rest of the
 * platform:
 *
 * <ol>
 *   <li><b>"Is today a trading day?"</b> — used by the Session FSM (decision-engine)
 *       to skip the morning ARMING transition on US market holidays. Closes the
 *       Phase 1 alert KQL known-issue "holiday calendar not modeled — fires on
 *       US market holidays".
 *   <li><b>"What events fall today?"</b> — drives Session FSM ARMING → BLACKOUT
 *       routing for FOMC meeting days, FOMC minutes-release days, CPI / NFP
 *       prints, and similar high-vol-risk economic events.
 * </ol>
 *
 * <p>Data source: hand-curated YAML resources (no external API calls, no
 * outbound network). The NYSE holiday schedule is published years in advance
 * (nyse.com) and the FOMC meeting dates are published by the Federal Reserve
 * Board; both are stable enough to ship as static config. Phase 7 may swap in
 * a managed economic-calendar API (Trading Economics) for the broader event
 * set (CPI, NFP) once we need same-day fresh data — until then the static set
 * is the source of truth.
 *
 * <p>CAP profile (architecture-spec §6): pure read-only, in-memory; no Mongo,
 * no Kafka, no MS SQL. Concurrency is trivial (immutable map keyed by
 * LocalDate). Caching with {@link org.springframework.cache.annotation.Cacheable}
 * is paranoia-grade — the underlying map lookup is already O(log n) — but it
 * matches the Spring-idiom for "memoize this" and keeps the controller free
 * of caching plumbing.
 *
 * <p>Multi-tenant: the calendar is tenant-agnostic for Phase A — every tenant
 * trades the same NYSE schedule and the same FOMC events. Endpoints do NOT
 * carry a tenantId; the Session FSM caller is per-tenant scoped, the lookup
 * itself is not. Phase B per-tenant calendar overrides (e.g. an institutional
 * tenant adding their own blackout dates) lands behind the
 * {@code phase-a-b-feature-flags} skill.
 *
 * <p>{@link EnableCaching} activates the {@code @Cacheable} annotations on
 * {@link com.levelsweep.calendar.service.CalendarService}. Without this the
 * annotations are silently ignored and every request re-traverses the full
 * holiday list — a soft-fail mode we explicitly reject in Phase 6.
 */
@SpringBootApplication
@EnableCaching
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("calendar-service starting (Phase 6: NYSE holidays + FOMC calendar 2026-2030)");
        SpringApplication.run(Application.class, args);
    }
}
