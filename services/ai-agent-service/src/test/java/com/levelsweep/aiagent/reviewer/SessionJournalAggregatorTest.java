package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.narrator.TradeNarrative;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionJournalAggregator}. Mockito over the Mongo
 * client + the {@link DailyReportRepository} — no real Mongo. Asserts:
 *
 * <ul>
 *   <li>Stub-mode (Mongo unsatisfied) returns an aggregate with empty
 *       sections rather than throwing.</li>
 *   <li>Trade narrative read filter shape: {@code tenant_id} +
 *       {@code generated_at} window for the ET session day.</li>
 *   <li>Signal history read filter shape and the empty-result gap log.</li>
 *   <li>Prior-5-days delegated to the repository with limit=5.</li>
 *   <li>Regime context always {@link java.util.Optional#empty()} in Phase 4.</li>
 * </ul>
 */
class SessionJournalAggregatorTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate SESSION = LocalDate.of(2026, 5, 2);
    private static final String TENANT = "OWNER";

    @Test
    @SuppressWarnings("unchecked")
    void aggregateInStubModeReturnsEmptySections() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        DailyReportRepository repo = mock(DailyReportRepository.class);
        when(repo.findRecent(any(), any(), eq(SessionJournalAggregator.PRIOR_DAYS_LIMIT)))
                .thenReturn(List.of());

        SessionJournalAggregator aggregator =
                new SessionJournalAggregator(instance, repo, "level_sweep", "trade_narratives", "signal_history");

        ReviewRequest req = aggregator.aggregate(TENANT, SESSION);

        assertThat(req.tenantId()).isEqualTo(TENANT);
        assertThat(req.sessionDate()).isEqualTo(SESSION);
        assertThat(req.sessionJournal()).isEmpty();
        assertThat(req.signalHistory()).isEmpty();
        assertThat(req.regimeContext()).isEmpty();
        assertThat(req.priorFiveDays()).isEmpty();
        // Repository is still consulted — its own stub-mode handling decides
        // whether to query Mongo.
        verify(repo, times(1)).findRecent(TENANT, SESSION, SessionJournalAggregator.PRIOR_DAYS_LIMIT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregateReadsTradeNarrativesForEtSessionWindow() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> narrativesColl = mock(MongoCollection.class);
        MongoCollection<Document> signalsColl = mock(MongoCollection.class);
        FindIterable<Document> narrativesIter = mock(FindIterable.class);
        FindIterable<Document> signalsIter = mock(FindIterable.class);
        DailyReportRepository repo = mock(DailyReportRepository.class);

        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("trade_narratives")).thenReturn(narrativesColl);
        when(db.getCollection("signal_history")).thenReturn(signalsColl);
        when(narrativesColl.find(any(Document.class))).thenReturn(narrativesIter);
        when(narrativesIter.sort(any(Document.class))).thenReturn(narrativesIter);
        Document narrativeDoc = new Document()
                .append("tenant_id", TENANT)
                .append("trade_id", "TR_001")
                .append("narrative", "Entry order filled at $1.42 for 2 contracts.")
                .append("model_used", "claude-sonnet-4-6")
                .append("prompt_hash", "hashhashhashhashhashhashhashhashhashhashhashhashhashhashhashhash")
                .append("generated_at", Date.from(Instant.parse("2026-05-02T13:35:00Z")));
        // Build cursors BEFORE the outer when() — Mockito errors on
        // stubbing-inside-stubbing. The inner cursor() helper itself stubs.
        MongoCursor<Document> narrativesCursor = cursor(List.of(narrativeDoc));
        MongoCursor<Document> signalsCursor = cursor(java.util.Collections.<Document>emptyList());
        when(narrativesIter.iterator()).thenReturn(narrativesCursor);

        when(signalsColl.find(any(Document.class))).thenReturn(signalsIter);
        when(signalsIter.sort(any(Document.class))).thenReturn(signalsIter);
        when(signalsIter.iterator()).thenReturn(signalsCursor);

        when(repo.findRecent(any(), any(), eq(SessionJournalAggregator.PRIOR_DAYS_LIMIT)))
                .thenReturn(List.of());

        SessionJournalAggregator aggregator =
                new SessionJournalAggregator(instance, repo, "level_sweep", "trade_narratives", "signal_history");

        ReviewRequest req = aggregator.aggregate(TENANT, SESSION);

        assertThat(req.sessionJournal()).hasSize(1);
        TradeNarrative n = req.sessionJournal().get(0);
        assertThat(n.tenantId()).isEqualTo(TENANT);
        assertThat(n.tradeId()).isEqualTo("TR_001");
        assertThat(n.narrative()).isEqualTo("Entry order filled at $1.42 for 2 contracts.");

        // Verify the narrative filter window covers the ET session day.
        org.mockito.ArgumentCaptor<Document> filterCaptor = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(narrativesColl, times(1)).find(filterCaptor.capture());
        Document filter = filterCaptor.getValue();
        assertThat(filter.getString("tenant_id")).isEqualTo(TENANT);
        Document genFilter = filter.get("generated_at", Document.class);
        Date gte = (Date) genFilter.get("$gte");
        Date lte = (Date) genFilter.get("$lte");
        // 09:30 ET on 2026-05-02 lies between the bounds; midnight UTC on
        // 2026-05-02 lies BEFORE the bounds (since ET is UTC-4 in May). Note
        // that java.util.Date carries millisecond precision, so the upper-
        // bound nanosecond LocalTime.MAX truncates on conversion through
        // Date.from() — we assert millisecond-level equality.
        assertThat(gte.toInstant()).isEqualTo(SESSION.atStartOfDay(ET).toInstant());
        Instant expectedUpper =
                SESSION.atTime(java.time.LocalTime.MAX).atZone(ET).toInstant();
        // Both sides truncated to millisecond precision (Date round-trip).
        assertThat(lte.toInstant().toEpochMilli()).isEqualTo(expectedUpper.toEpochMilli());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregateReadsSignalHistoryWithCorrectFilter() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> narrativesColl = mock(MongoCollection.class);
        MongoCollection<Document> signalsColl = mock(MongoCollection.class);
        FindIterable<Document> narrativesIter = mock(FindIterable.class);
        FindIterable<Document> signalsIter = mock(FindIterable.class);
        DailyReportRepository repo = mock(DailyReportRepository.class);

        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("trade_narratives")).thenReturn(narrativesColl);
        when(db.getCollection("signal_history")).thenReturn(signalsColl);
        Document signalDoc = new Document()
                .append("tenant_id", TENANT)
                .append("session_date", SESSION.toString())
                .append("signal_id", "SIG_001")
                .append("evaluated_at", Date.from(Instant.parse("2026-05-02T13:32:00Z")))
                .append("side", "CALL")
                .append("level_type", "PDH")
                .append("outcome", "TAKEN")
                .append("reason_code", "ema48_above")
                .append("correlation_id", "corr-001");
        // Pre-build cursors so the inner cursor() stubbing does not interleave
        // with the outer when()/thenReturn() chain.
        MongoCursor<Document> narrativesCursor = cursor(java.util.Collections.<Document>emptyList());
        MongoCursor<Document> signalsCursor = cursor(List.of(signalDoc));

        when(narrativesColl.find(any(Document.class))).thenReturn(narrativesIter);
        when(narrativesIter.sort(any(Document.class))).thenReturn(narrativesIter);
        when(narrativesIter.iterator()).thenReturn(narrativesCursor);

        when(signalsColl.find(any(Document.class))).thenReturn(signalsIter);
        when(signalsIter.sort(any(Document.class))).thenReturn(signalsIter);
        when(signalsIter.iterator()).thenReturn(signalsCursor);

        when(repo.findRecent(any(), any(), eq(SessionJournalAggregator.PRIOR_DAYS_LIMIT)))
                .thenReturn(List.of());

        SessionJournalAggregator aggregator =
                new SessionJournalAggregator(instance, repo, "level_sweep", "trade_narratives", "signal_history");

        ReviewRequest req = aggregator.aggregate(TENANT, SESSION);

        assertThat(req.signalHistory()).hasSize(1);
        SignalEvaluationRecord s = req.signalHistory().get(0);
        assertThat(s.signalId()).isEqualTo("SIG_001");
        assertThat(s.side()).isEqualTo(SignalEvaluationRecord.Side.CALL);
        assertThat(s.levelType()).isEqualTo(SignalEvaluationRecord.LevelType.PDH);
        assertThat(s.outcome()).isEqualTo(SignalEvaluationRecord.Outcome.TAKEN);

        // Verify the signal history filter is keyed by (tenant_id, session_date).
        org.mockito.ArgumentCaptor<Document> filterCaptor = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(signalsColl, times(1)).find(filterCaptor.capture());
        Document filter = filterCaptor.getValue();
        assertThat(filter.getString("tenant_id")).isEqualTo(TENANT);
        assertThat(filter.getString("session_date")).isEqualTo(SESSION.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregateDelegatesPriorFiveDaysToRepository() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true); // skip Mongo for narratives
        DailyReportRepository repo = mock(DailyReportRepository.class);
        DailyReport prior = new DailyReport(
                TENANT,
                LocalDate.of(2026, 4, 30),
                "Prior session.",
                List.of(),
                List.of(),
                DailyReport.Outcome.COMPLETED,
                Instant.parse("2026-04-30T20:30:00Z"),
                "claude-opus-4-7",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                500L,
                new java.math.BigDecimal("0.0150"));
        when(repo.findRecent(TENANT, SESSION, SessionJournalAggregator.PRIOR_DAYS_LIMIT))
                .thenReturn(List.of(prior));

        SessionJournalAggregator aggregator =
                new SessionJournalAggregator(instance, repo, "level_sweep", "trade_narratives", "signal_history");

        ReviewRequest req = aggregator.aggregate(TENANT, SESSION);

        assertThat(req.priorFiveDays()).hasSize(1);
        assertThat(req.priorFiveDays().get(0).sessionDate()).isEqualTo(LocalDate.of(2026, 4, 30));
        verify(repo, times(1)).findRecent(TENANT, SESSION, 5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregateReturnsEmptyRegimeInPhase4() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        DailyReportRepository repo = mock(DailyReportRepository.class);
        when(repo.findRecent(any(), any(), eq(SessionJournalAggregator.PRIOR_DAYS_LIMIT)))
                .thenReturn(List.of());

        SessionJournalAggregator aggregator =
                new SessionJournalAggregator(instance, repo, "level_sweep", "trade_narratives", "signal_history");

        ReviewRequest req = aggregator.aggregate(TENANT, SESSION);

        // Phase 4 always empty — the gap is documented in
        // SessionJournalAggregator.readRegimeContext.
        assertThat(req.regimeContext()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregateSwallowsTradeNarrativesReadException() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> narrativesColl = mock(MongoCollection.class);
        DailyReportRepository repo = mock(DailyReportRepository.class);

        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("trade_narratives")).thenReturn(narrativesColl);
        when(db.getCollection("signal_history")).thenReturn(narrativesColl);
        when(narrativesColl.find(any(Document.class))).thenThrow(new RuntimeException("mongo down"));

        when(repo.findRecent(any(), any(), eq(SessionJournalAggregator.PRIOR_DAYS_LIMIT)))
                .thenReturn(List.of());

        SessionJournalAggregator aggregator =
                new SessionJournalAggregator(instance, repo, "level_sweep", "trade_narratives", "signal_history");

        // Must not throw — aggregator failures are advisory.
        ReviewRequest req = aggregator.aggregate(TENANT, SESSION);
        assertThat(req.sessionJournal()).isEmpty();
        assertThat(req.signalHistory()).isEmpty();
    }

    @Test
    void aggregateRejectsBlankTenantId() {
        Instance<MongoClient> instance = mock(Instance.class);
        DailyReportRepository repo = mock(DailyReportRepository.class);
        SessionJournalAggregator aggregator =
                new SessionJournalAggregator(instance, repo, "level_sweep", "trade_narratives", "signal_history");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> aggregator.aggregate("", SESSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        verify(repo, never()).findRecent(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aggregateRejectsNullSessionDate() {
        Instance<MongoClient> instance = mock(Instance.class);
        DailyReportRepository repo = mock(DailyReportRepository.class);
        SessionJournalAggregator aggregator =
                new SessionJournalAggregator(instance, repo, "level_sweep", "trade_narratives", "signal_history");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> aggregator.aggregate(TENANT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Build a {@link MongoCursor} that delegates to a plain list iterator.
     * The MongoDB driver's iteration uses {@link MongoCursor} (a sub-interface
     * of {@link Iterator}) so a plain list iterator does not satisfy the
     * {@code FindIterable.iterator()} signature.
     */
    @SuppressWarnings("unchecked")
    private static <T> MongoCursor<T> cursor(List<T> items) {
        MongoCursor<T> cursor = mock(MongoCursor.class);
        Iterator<T> it = items.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        return cursor;
    }
}
