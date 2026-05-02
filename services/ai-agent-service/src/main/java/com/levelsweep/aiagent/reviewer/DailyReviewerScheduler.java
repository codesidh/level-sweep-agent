package com.levelsweep.aiagent.reviewer;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 4 Step 3 — Daily Reviewer scheduler. At {@code 16:30:00 ET} every day,
 * aggregate the session journal + signal history + regime context + prior 5
 * days of reports and ask Opus 4.7 to produce a {@link DailyReport} for the
 * trader.
 *
 * <p>Cron expression {@code "0 30 16 * * ?"} fires at HH:30:00 with HH=16
 * (4:30 PM in the {@code America/New_York} zone — Quarkus / Quartz handles DST
 * transitions transparently). The reviewer runs every day of the week — the
 * session journal will be empty outside trading sessions so weekend invocations
 * are cheap and (per Phase A operating model) we still produce a report so
 * the audit trail is dense.
 *
 * <p><b>Timing rationale</b> (architecture-spec §4.3.4): 16:30 ET is 30
 * minutes after the EOD flatten cron at 15:55 ET, leaving slack for
 * reconciliation + the journal writes from the closing positions to land in
 * Mongo before the reviewer reads them.
 *
 * <p><b>Failure posture</b>: every step is wrapped in try/catch. Aggregator
 * failure → swallow + WARN. Reviewer failure → swallow + WARN. Repository
 * failure → swallow + WARN. The scheduler MUST NEVER let an exception
 * propagate out of {@link #runOnce()} — a broken review must not break the
 * next day's cron fire.
 *
 * <p>Per-fire flow:
 *
 * <ol>
 *   <li>Read {@link Clock#instant()} once and derive the session date in ET.</li>
 *   <li>Aggregate the inputs via {@link SessionJournalAggregator#aggregate}
 *       (catches its own exceptions internally; this scheduler still wraps
 *       defensively).</li>
 *   <li>Call {@link DailyReviewer#review(ReviewRequest)}. On
 *       {@link Optional#empty()} (cost-cap skip OR Anthropic non-success),
 *       persist a {@code SKIPPED_COST_CAP} stub report so the audit trail
 *       carries one row per session — dashboards can distinguish "reviewer
 *       didn't run" from "reviewer ran and decided nothing was worth saying".</li>
 *   <li>On a non-empty result, persist via
 *       {@link DailyReportRepository#save(DailyReport)}.</li>
 * </ol>
 *
 * <p>The {@code identity = "daily-reviewer"} disambiguates this cron from
 * any future schedulers in the same JVM. The scheduler is enabled by default
 * via Quarkus' standard {@code quarkus.scheduler.enabled} flag; the
 * {@code %test} profile disables the scheduler so unit tests drive
 * {@link #runOnce()} directly.
 */
@ApplicationScoped
public class DailyReviewerScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DailyReviewerScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final Clock clock;
    private final SessionJournalAggregator aggregator;
    private final DailyReviewer reviewer;
    private final DailyReportRepository repository;
    private final String tenantId;
    private final String model;

    @Inject
    public DailyReviewerScheduler(
            Clock clock,
            SessionJournalAggregator aggregator,
            DailyReviewer reviewer,
            DailyReportRepository repository,
            @ConfigProperty(name = "levelsweep.tenant.bootstrap-id", defaultValue = "OWNER") String tenantId,
            @ConfigProperty(name = "anthropic.models.reviewer", defaultValue = "claude-opus-4-7") String model) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Cron handler — fires at 16:30:00 ET daily. Delegates to {@link #runOnce()}
     * so unit tests can exercise the saga directly without the Quarkus
     * scheduler harness.
     */
    @Scheduled(cron = "0 30 16 * * ?", timeZone = "America/New_York", identity = "daily-reviewer")
    void onCron() {
        runOnce();
    }

    /**
     * Test-visible body. One read of {@link Clock#instant()} per fire; the
     * resulting session date is threaded through every downstream call.
     *
     * <p>The scheduler is single-tenant in Phase A — it operates on the
     * configured bootstrap tenant id. Phase B fans out by enumerating tenants
     * from the User & Config service; the cron expression stays the same.
     */
    void runOnce() {
        Instant now;
        LocalDate sessionDate;
        try {
            now = clock.instant();
            sessionDate = LocalDate.ofInstant(now, ET);
        } catch (RuntimeException e) {
            LOG.warn("daily reviewer: clock read failed; skipping fire: {}", e.toString());
            return;
        }

        LOG.info("daily reviewer: starting tenantId={} sessionDate={} model={}", tenantId, sessionDate, model);

        ReviewRequest request;
        try {
            request = aggregator.aggregate(tenantId, sessionDate);
        } catch (RuntimeException e) {
            LOG.warn(
                    "daily reviewer: aggregator failed tenantId={} sessionDate={}: {}",
                    tenantId,
                    sessionDate,
                    e.toString());
            return;
        }

        Optional<DailyReport> result;
        try {
            result = reviewer.review(request);
        } catch (RuntimeException e) {
            LOG.warn(
                    "daily reviewer: reviewer threw tenantId={} sessionDate={}: {}",
                    tenantId,
                    sessionDate,
                    e.toString());
            return;
        }

        if (result.isPresent()) {
            persist(result.get(), sessionDate);
            LOG.info(
                    "daily reviewer: complete tenantId={} sessionDate={} outcome={} totalTokens={} costUsd={}",
                    tenantId,
                    sessionDate,
                    result.get().outcome(),
                    result.get().totalTokensUsed(),
                    result.get().costUsd().toPlainString());
            return;
        }

        // Empty result — either the cost cap blocked the call or Anthropic
        // returned a non-Success variant. Persist a SKIPPED_COST_CAP stub so
        // the audit trail carries a row per session. We label as
        // SKIPPED_COST_CAP rather than FAILED because the reviewer logs the
        // exact cause; the persisted outcome is a coarse "reviewer did not
        // produce a substantive report" marker. Phase 5/6 may split this into
        // SKIPPED_COST_CAP vs. FAILED based on a richer return type.
        DailyReport stub = stubReport(sessionDate, now);
        persist(stub, sessionDate);
        LOG.info(
                "daily reviewer: skipped (no substantive report); persisted stub tenantId={} sessionDate={} outcome={}",
                tenantId,
                sessionDate,
                stub.outcome());
    }

    private void persist(DailyReport report, LocalDate sessionDate) {
        try {
            repository.save(report);
        } catch (RuntimeException e) {
            LOG.warn(
                    "daily reviewer: repository save failed tenantId={} sessionDate={}: {}",
                    tenantId,
                    sessionDate,
                    e.toString());
        }
    }

    /**
     * Build the audit-stub report when the reviewer returns empty. Carries a
     * deterministic synthetic prompt-hash placeholder so the row passes
     * {@link DailyReport}'s validation; downstream consumers filter by
     * {@link DailyReport.Outcome} when they want substantive reports.
     */
    private DailyReport stubReport(LocalDate sessionDate, Instant generatedAt) {
        return new DailyReport(
                tenantId,
                sessionDate,
                "(reviewer did not produce a substantive report — see ai_calls audit log for details)",
                List.of(),
                List.of(),
                DailyReport.Outcome.SKIPPED_COST_CAP,
                generatedAt,
                model,
                "skipped-no-call",
                /* totalTokensUsed */ 0L,
                BigDecimal.ZERO);
    }
}
