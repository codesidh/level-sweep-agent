package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link DailyReportRepository}. Mockito over MongoClient
 * — no real Mongo. Mirrors {@code TradeNarrativeRepositoryTest} pattern.
 */
class DailyReportRepositoryTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-05-02T20:30:00Z");
    private static final LocalDate SESSION = LocalDate.of(2026, 5, 2);

    @Test
    void toDocumentIncludesAllFields() {
        DailyReport r = new DailyReport(
                "OWNER",
                SESSION,
                "Today the strategy executed two trades.",
                List.of("anomaly: VIX rose 0.7 vs 0.2 average"),
                List.of(new ConfigProposal(
                        "raise EMA48 stop tolerance", "two days of false stops", ConfigProposal.Urgency.LOW)),
                DailyReport.Outcome.COMPLETED,
                GENERATED_AT,
                "claude-opus-4-7",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                11_500L,
                new BigDecimal("0.2625"));

        Document d = DailyReportRepository.toDocument(r);

        assertThat(d.getString("tenant_id")).isEqualTo("OWNER");
        assertThat(d.getString("session_date")).isEqualTo("2026-05-02");
        assertThat(d.getString("summary")).isEqualTo("Today the strategy executed two trades.");
        assertThat(d.getList("anomalies", String.class)).containsExactly("anomaly: VIX rose 0.7 vs 0.2 average");
        List<Document> proposals = d.getList("proposals", Document.class);
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).getString("change_spec")).isEqualTo("raise EMA48 stop tolerance");
        assertThat(proposals.get(0).getString("rationale")).isEqualTo("two days of false stops");
        assertThat(proposals.get(0).getString("urgency")).isEqualTo("LOW");
        assertThat(d.getString("outcome")).isEqualTo("COMPLETED");
        assertThat(d.getString("model_used")).isEqualTo("claude-opus-4-7");
        assertThat(d.getString("prompt_hash"))
                .isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        assertThat(d.getLong("total_tokens_used")).isEqualTo(11_500L);
        assertThat(d.getString("cost_usd")).isEqualTo("0.2625");
        assertThat(d.get("generated_at")).isEqualTo(Date.from(GENERATED_AT));
    }

    @Test
    void roundTripFromDocumentPreservesFields() {
        DailyReport original = sampleReport();
        Document d = DailyReportRepository.toDocument(original);

        DailyReport roundTrip = DailyReportRepository.fromDocument(d);

        assertThat(roundTrip.tenantId()).isEqualTo(original.tenantId());
        assertThat(roundTrip.sessionDate()).isEqualTo(original.sessionDate());
        assertThat(roundTrip.summary()).isEqualTo(original.summary());
        assertThat(roundTrip.outcome()).isEqualTo(original.outcome());
        assertThat(roundTrip.modelUsed()).isEqualTo(original.modelUsed());
        assertThat(roundTrip.promptHash()).isEqualTo(original.promptHash());
        assertThat(roundTrip.totalTokensUsed()).isEqualTo(original.totalTokensUsed());
        assertThat(roundTrip.costUsd()).isEqualByComparingTo(original.costUsd());
        assertThat(roundTrip.generatedAt()).isEqualTo(original.generatedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIsNoOpWhenMongoUnsatisfied() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        DailyReportRepository repo = new DailyReportRepository(instance, "level_sweep", "daily_reports");

        repo.save(sampleReport());

        verify(instance, times(1)).isUnsatisfied();
        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIsNoOpWhenReportNull() {
        Instance<MongoClient> instance = mock(Instance.class);
        DailyReportRepository repo = new DailyReportRepository(instance, "level_sweep", "daily_reports");

        repo.save(null);

        verify(instance, never()).isUnsatisfied();
        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveInsertsOneDocumentWithExpectedShape() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("daily_reports")).thenReturn(coll);

        DailyReportRepository repo = new DailyReportRepository(instance, "level_sweep", "daily_reports");
        DailyReport r = sampleReport();

        repo.save(r);

        verify(coll, times(1)).insertOne(eq(DailyReportRepository.toDocument(r)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveSwallowsRuntimeExceptionFromInsert() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("daily_reports")).thenReturn(coll);
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down"))
                .when(coll)
                .insertOne(any(Document.class));

        DailyReportRepository repo = new DailyReportRepository(instance, "level_sweep", "daily_reports");

        // Must not throw — the reviewer is advisory; a Mongo blip can never
        // propagate up the scheduler path (CLAUDE.md guardrail #3).
        repo.save(sampleReport());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findRecentReturnsEmptyWhenMongoUnsatisfied() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        DailyReportRepository repo = new DailyReportRepository(instance, "level_sweep", "daily_reports");

        List<DailyReport> result = repo.findRecent("OWNER", SESSION, 5);

        assertThat(result).isEmpty();
        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findRecentReturnsEmptyForZeroLimit() {
        Instance<MongoClient> instance = mock(Instance.class);
        DailyReportRepository repo = new DailyReportRepository(instance, "level_sweep", "daily_reports");

        List<DailyReport> result = repo.findRecent("OWNER", SESSION, 0);

        assertThat(result).isEmpty();
        // Should not even attempt to check Mongo state.
        verify(instance, never()).isUnsatisfied();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findRecentQueriesCorrectFilterAndReversesToChronological() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> iter = mock(FindIterable.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("daily_reports")).thenReturn(coll);
        when(coll.find(any(Document.class))).thenReturn(iter);
        when(iter.sort(any(Document.class))).thenReturn(iter);
        when(iter.limit(5)).thenReturn(iter);

        // Mongo returns descending; the repository reverses to ascending for
        // the prompt builder. Pre-build the cursor before the outer when()
        // chain so Mockito does not see stubbing-inside-stubbing.
        DailyReport newer = priorReport(LocalDate.of(2026, 5, 1));
        DailyReport older = priorReport(LocalDate.of(2026, 4, 30));
        MongoCursor<Document> resultCursor =
                cursor(List.of(DailyReportRepository.toDocument(newer), DailyReportRepository.toDocument(older)));
        when(iter.iterator()).thenReturn(resultCursor);

        DailyReportRepository repo = new DailyReportRepository(instance, "level_sweep", "daily_reports");
        List<DailyReport> result = repo.findRecent("OWNER", SESSION, 5);

        assertThat(result).hasSize(2);
        // First in the result should be the older one (chronological asc).
        assertThat(result.get(0).sessionDate()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(result.get(1).sessionDate()).isEqualTo(LocalDate.of(2026, 5, 1));

        // Verify filter shape.
        org.mockito.ArgumentCaptor<Document> filterCaptor = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(coll, times(1)).find(filterCaptor.capture());
        Document filter = filterCaptor.getValue();
        assertThat(filter.getString("tenant_id")).isEqualTo("OWNER");
        Document sessionFilter = filter.get("session_date", Document.class);
        assertThat(sessionFilter.getString("$lt")).isEqualTo("2026-05-02");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findRecentSwallowsRuntimeExceptionAndReturnsEmpty() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("daily_reports")).thenReturn(coll);
        when(coll.find(any(Document.class))).thenThrow(new RuntimeException("mongo down"));

        DailyReportRepository repo = new DailyReportRepository(instance, "level_sweep", "daily_reports");

        List<DailyReport> result = repo.findRecent("OWNER", SESSION, 5);
        assertThat(result).isEmpty();
    }

    private static DailyReport sampleReport() {
        return new DailyReport(
                "OWNER",
                SESSION,
                "Today the strategy executed two trades. Both exited via EMA13 stop.",
                List.of(),
                List.of(),
                DailyReport.Outcome.COMPLETED,
                GENERATED_AT,
                "claude-opus-4-7",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                11_500L,
                new BigDecimal("0.2625"));
    }

    /**
     * Build a {@link MongoCursor} that delegates to a plain list iterator.
     * The MongoDB driver's iteration uses {@link MongoCursor} so a plain
     * list iterator does not satisfy {@code FindIterable.iterator()}.
     */
    @SuppressWarnings("unchecked")
    private static <T> MongoCursor<T> cursor(List<T> items) {
        MongoCursor<T> cursor = mock(MongoCursor.class);
        Iterator<T> it = items.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        return cursor;
    }

    private static DailyReport priorReport(LocalDate date) {
        return new DailyReport(
                "OWNER",
                date,
                "Prior session " + date + ".",
                List.of(),
                List.of(),
                DailyReport.Outcome.COMPLETED,
                date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                "claude-opus-4-7",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                500L,
                new BigDecimal("0.0150"));
    }
}
