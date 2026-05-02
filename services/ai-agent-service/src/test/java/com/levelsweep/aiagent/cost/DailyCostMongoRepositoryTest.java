package com.levelsweep.aiagent.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
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
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link DailyCostMongoRepository}. Mockito over MongoClient —
 * no real Mongo. Mirrors {@code MongoBarRepositoryTest}'s pattern of verifying
 * the static document-shape helper directly, plus a handful of integration-style
 * checks against the mocked MongoCollection.
 */
class DailyCostMongoRepositoryTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-05-02T15:00:00Z");
    private static final LocalDate ET_DATE = LocalDate.of(2026, 5, 2);

    @Test
    void toDocumentIncludesAllAuditFields() {
        Document d = DailyCostMongoRepository.toDocument(
                "OWNER", Role.SENTINEL, ET_DATE, new BigDecimal("0.0042"), OCCURRED_AT);

        assertThat(d.getString("tenant_id")).isEqualTo("OWNER");
        assertThat(d.getString("role")).isEqualTo("sentinel");
        assertThat(d.getString("date")).isEqualTo("2026-05-02");
        assertThat(d.getString("cost_usd")).isEqualTo("0.0042");
        assertThat(d.get("occurred_at")).isEqualTo(Date.from(OCCURRED_AT));
    }

    @Test
    void toDocumentRoleKeyMatchesConfigKey() {
        for (Role r : Role.values()) {
            Document d = DailyCostMongoRepository.toDocument("OWNER", r, ET_DATE, BigDecimal.ZERO, OCCURRED_AT);
            assertThat(d.getString("role")).isEqualTo(r.configKey());
        }
    }

    @Test
    void toDocumentPreservesBigDecimalScale() {
        // High-precision cost — should round-trip via toPlainString without loss.
        BigDecimal cost = new BigDecimal("0.00012345");
        Document d = DailyCostMongoRepository.toDocument("OWNER", Role.SENTINEL, ET_DATE, cost, OCCURRED_AT);

        assertThat(d.getString("cost_usd")).isEqualTo("0.00012345");
        assertThat(new BigDecimal(d.getString("cost_usd"))).isEqualByComparingTo(cost);
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendIsNoOpWhenMongoUnsatisfied() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        DailyCostMongoRepository repo = new DailyCostMongoRepository(instance, "level_sweep", "daily_cost");

        // Must not throw; returns silently.
        repo.append("OWNER", Role.SENTINEL, ET_DATE, new BigDecimal("0.10"), OCCURRED_AT);

        verify(instance, times(1)).isUnsatisfied();
        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sumByDayReturnsZeroWhenMongoUnsatisfied() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        DailyCostMongoRepository repo = new DailyCostMongoRepository(instance, "level_sweep", "daily_cost");

        BigDecimal sum = repo.sumByDay("OWNER", Role.SENTINEL, ET_DATE);

        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sumByDaySumsRowsFromCollection() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("daily_cost")).thenReturn(coll);
        when(coll.find(any(Document.class))).thenReturn(findIterable);

        // Two rows for OWNER/SENTINEL/2026-05-02 — $0.10 + $0.05.
        Document d1 = new Document("cost_usd", "0.10");
        Document d2 = new Document("cost_usd", "0.05");
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(d1, d2);

        DailyCostMongoRepository repo = new DailyCostMongoRepository(instance, "level_sweep", "daily_cost");

        BigDecimal sum = repo.sumByDay("OWNER", Role.SENTINEL, ET_DATE);

        assertThat(sum).isEqualByComparingTo(new BigDecimal("0.15"));
        verify(coll)
                .find(eq(new Document("tenant_id", "OWNER")
                        .append("role", "sentinel")
                        .append("date", "2026-05-02")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendInsertsOneDocumentWithExpectedShape() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("daily_cost")).thenReturn(coll);

        DailyCostMongoRepository repo = new DailyCostMongoRepository(instance, "level_sweep", "daily_cost");

        repo.append("OWNER", Role.NARRATOR, ET_DATE, new BigDecimal("0.0042"), OCCURRED_AT);

        // Index ensure + insert. We verify insertOne was called with the expected shape.
        verify(coll, times(1))
                .insertOne(eq(DailyCostMongoRepository.toDocument(
                        "OWNER", Role.NARRATOR, ET_DATE, new BigDecimal("0.0042"), OCCURRED_AT)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sumByDayHandlesEmptyCollection() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase("level_sweep")).thenReturn(db);
        when(db.getCollection("daily_cost")).thenReturn(coll);
        when(coll.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        DailyCostMongoRepository repo = new DailyCostMongoRepository(instance, "level_sweep", "daily_cost");

        assertThat(repo.sumByDay("OWNER", Role.SENTINEL, ET_DATE)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
