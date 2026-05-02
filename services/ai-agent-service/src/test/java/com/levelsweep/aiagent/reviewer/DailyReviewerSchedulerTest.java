package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DailyReviewerScheduler}. Pure POJO assembly — no
 * Quarkus harness — so we drive {@link DailyReviewerScheduler#runOnce()}
 * directly and assert every branch:
 *
 * <ul>
 *   <li>Aggregator returns empty journal → reviewer called → present result
 *       saved.</li>
 *   <li>Reviewer returns Optional.empty (cost-cap or non-success) →
 *       SKIPPED_COST_CAP stub persisted.</li>
 *   <li>Aggregator throws → swallowed + WARN, no reviewer call.</li>
 *   <li>Reviewer throws → swallowed + WARN, no save.</li>
 *   <li>Repository throws → swallowed + WARN.</li>
 *   <li>Session date is computed in America/New_York from the clock instant
 *       (not UTC), per architecture-spec Principle #10.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DailyReviewerSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    // 16:30:00 ET on a regular session day → 20:30:00 UTC.
    private static final Instant NOW = Instant.parse("2026-05-02T20:30:00Z");
    private static final LocalDate SESSION = LocalDate.of(2026, 5, 2);
    private static final String TENANT = "OWNER";
    private static final String MODEL = "claude-opus-4-7";

    private Clock clock;

    @Mock
    private SessionJournalAggregator aggregator;

    @Mock
    private DailyReviewer reviewer;

    @Mock
    private DailyReportRepository repository;

    private DailyReviewerScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ET);
        scheduler = new DailyReviewerScheduler(clock, aggregator, reviewer, repository, TENANT, MODEL);
    }

    @Test
    void runOnceAggregatesForCurrentEtSessionDate() {
        when(aggregator.aggregate(any(), any())).thenReturn(emptyRequest());
        when(reviewer.review(any())).thenReturn(Optional.of(completedReport()));

        scheduler.runOnce();

        // 16:30 UTC-4 → 20:30Z; the ET local date for that instant is 2026-05-02.
        verify(aggregator, times(1)).aggregate(TENANT, SESSION);
    }

    @Test
    void runOncePassesAggregatedRequestToReviewer() {
        ReviewRequest req = emptyRequest();
        when(aggregator.aggregate(any(), any())).thenReturn(req);
        when(reviewer.review(any())).thenReturn(Optional.of(completedReport()));

        scheduler.runOnce();

        verify(reviewer, times(1)).review(req);
    }

    @Test
    void runOnceSavesNonEmptyReport() {
        when(aggregator.aggregate(any(), any())).thenReturn(emptyRequest());
        DailyReport report = completedReport();
        when(reviewer.review(any())).thenReturn(Optional.of(report));

        scheduler.runOnce();

        verify(repository, times(1)).save(report);
    }

    @Test
    void runOnceSavesSkippedStubWhenReviewerReturnsEmpty() {
        // Cost cap blocked the call OR Anthropic returned non-Success — the
        // reviewer returns Optional.empty in either case. The scheduler
        // persists a stub so the audit trail carries one row per session.
        when(aggregator.aggregate(any(), any())).thenReturn(emptyRequest());
        when(reviewer.review(any())).thenReturn(Optional.empty());

        scheduler.runOnce();

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(repository, times(1)).save(captor.capture());
        DailyReport stub = captor.getValue();
        assertThat(stub.tenantId()).isEqualTo(TENANT);
        assertThat(stub.sessionDate()).isEqualTo(SESSION);
        assertThat(stub.outcome()).isEqualTo(DailyReport.Outcome.SKIPPED_COST_CAP);
        assertThat(stub.modelUsed()).isEqualTo(MODEL);
        assertThat(stub.summary()).contains("did not produce a substantive report");
        assertThat(stub.totalTokensUsed()).isEqualTo(0L);
        assertThat(stub.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stub.proposals()).isEmpty();
        assertThat(stub.anomalies()).isEmpty();
        assertThat(stub.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void runOnceSwallowsAggregatorExceptionAndDoesNotCallReviewer() {
        when(aggregator.aggregate(any(), any())).thenThrow(new RuntimeException("mongo down"));

        // Must NOT throw — the scheduler is the outermost guard.
        scheduler.runOnce();

        verify(reviewer, never()).review(any());
        verify(repository, never()).save(any());
    }

    @Test
    void runOnceSwallowsReviewerExceptionAndDoesNotSave() {
        when(aggregator.aggregate(any(), any())).thenReturn(emptyRequest());
        when(reviewer.review(any())).thenThrow(new RuntimeException("anthropic down"));

        scheduler.runOnce();

        verify(repository, never()).save(any());
    }

    @Test
    void runOnceSwallowsRepositoryExceptionOnPresentResult() {
        when(aggregator.aggregate(any(), any())).thenReturn(emptyRequest());
        when(reviewer.review(any())).thenReturn(Optional.of(completedReport()));
        org.mockito.Mockito.doThrow(new RuntimeException("mongo write failed"))
                .when(repository)
                .save(any());

        // Must NOT throw — repository failures are advisory.
        scheduler.runOnce();

        verify(repository, times(1)).save(any());
    }

    @Test
    void runOnceSwallowsRepositoryExceptionOnStub() {
        when(aggregator.aggregate(any(), any())).thenReturn(emptyRequest());
        when(reviewer.review(any())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("mongo write failed"))
                .when(repository)
                .save(any());

        scheduler.runOnce();

        verify(repository, times(1)).save(argThat(r -> r.outcome() == DailyReport.Outcome.SKIPPED_COST_CAP));
    }

    @Test
    void runOnceComputesEtSessionDateNotUtcDate() {
        // 03:00 UTC on 2026-05-03 = 23:00 ET on 2026-05-02. The session date
        // for the EOD reviewer is the ET local date, not the UTC date.
        Clock lateClock = Clock.fixed(Instant.parse("2026-05-03T03:00:00Z"), ET);
        DailyReviewerScheduler lateScheduler =
                new DailyReviewerScheduler(lateClock, aggregator, reviewer, repository, TENANT, MODEL);
        when(aggregator.aggregate(any(), any())).thenReturn(emptyRequest());
        when(reviewer.review(any())).thenReturn(Optional.of(completedReport()));

        lateScheduler.runOnce();

        // The aggregator must be called with the ET-local 2026-05-02, not UTC 2026-05-03.
        verify(aggregator, times(1)).aggregate(TENANT, LocalDate.of(2026, 5, 2));
    }

    // ---------- helpers ----------

    private static ReviewRequest emptyRequest() {
        return new ReviewRequest(TENANT, SESSION, List.of(), List.of(), Optional.empty(), List.of());
    }

    private static DailyReport completedReport() {
        return new DailyReport(
                TENANT,
                SESSION,
                "Today the strategy executed two trades.",
                List.of(),
                List.of(),
                DailyReport.Outcome.COMPLETED,
                NOW,
                MODEL,
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                11_500L,
                new BigDecimal("0.2625"));
    }
}
