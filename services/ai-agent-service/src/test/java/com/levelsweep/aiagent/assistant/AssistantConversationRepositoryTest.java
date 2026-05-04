package com.levelsweep.aiagent.assistant;

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
import com.mongodb.client.model.Filters;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link AssistantConversationRepository}. Mockito over
 * MongoClient — no real Mongo. Mirrors {@code TradeNarrativeRepositoryTest}.
 */
class AssistantConversationRepositoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-04T13:30:00Z"), ZoneOffset.UTC);
    private static final String DB = "level_sweep";
    private static final String COLL = "assistant_conversations";

    @Test
    void toDocumentIncludesAllFields() {
        AssistantConversation conv = new AssistantConversation(
                "OWNER",
                "conv-1",
                Instant.parse("2026-05-04T13:30:00Z"),
                Instant.parse("2026-05-04T13:35:00Z"),
                List.of(
                        AssistantTurn.user("what was the R-multiple?", Instant.parse("2026-05-04T13:31:00Z")),
                        AssistantTurn.assistant(
                                "+1.2R on TR_001.", Instant.parse("2026-05-04T13:32:00Z"), new BigDecimal("0.0150"))),
                new BigDecimal("0.0150"));

        Document d = AssistantConversationRepository.toDocument(conv);

        assertThat(d.getString("tenant_id")).isEqualTo("OWNER");
        assertThat(d.getString("conversation_id")).isEqualTo("conv-1");
        assertThat(d.getString("total_cost_usd")).isEqualTo("0.0150");
        @SuppressWarnings("unchecked")
        List<Document> turns = (List<Document>) d.get("turns");
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).getString("role")).isEqualTo("user");
        assertThat(turns.get(1).getString("role")).isEqualTo("assistant");
        assertThat(turns.get(1).getString("cost_usd")).isEqualTo("0.0150");
    }

    @Test
    void fromDocumentRoundTripsTurns() {
        AssistantConversation original = new AssistantConversation(
                "OWNER",
                "conv-2",
                Instant.parse("2026-05-04T13:30:00Z"),
                Instant.parse("2026-05-04T13:35:00Z"),
                List.of(AssistantTurn.user("hi", Instant.parse("2026-05-04T13:31:00Z"))),
                BigDecimal.ZERO);
        Document d = AssistantConversationRepository.toDocument(original);
        AssistantConversation parsed = AssistantConversationRepository.fromDocument(d);

        assertThat(parsed.tenantId()).isEqualTo("OWNER");
        assertThat(parsed.conversationId()).isEqualTo("conv-2");
        assertThat(parsed.turns()).hasSize(1);
        assertThat(parsed.totalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByIdReturnsEmptyWhenMongoUnsatisfied() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);

        assertThat(repo.findById("OWNER", "conv-1")).isEmpty();
        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByIdReturnsParsedConversation() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> findIt = mock(FindIterable.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase(DB)).thenReturn(db);
        when(db.getCollection(COLL)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(findIt);
        Document found = AssistantConversationRepository.toDocument(new AssistantConversation(
                "OWNER",
                "conv-1",
                Instant.parse("2026-05-04T13:30:00Z"),
                Instant.parse("2026-05-04T13:35:00Z"),
                List.of(),
                BigDecimal.ZERO));
        when(findIt.first()).thenReturn(found);

        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);
        assertThat(repo.findById("OWNER", "conv-1"))
                .map(AssistantConversation::conversationId)
                .contains("conv-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByIdSwallowsRuntimeException() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase(DB)).thenReturn(db);
        when(db.getCollection(COLL)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenThrow(new RuntimeException("mongo down"));

        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);
        assertThat(repo.findById("OWNER", "conv-1")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createNewGeneratesUuidAndStubModeSkipsInsert() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);

        AssistantConversation conv = repo.createNew("OWNER");

        assertThat(conv.tenantId()).isEqualTo("OWNER");
        assertThat(conv.conversationId()).hasSize(36); // UUID v4
        assertThat(conv.turns()).isEmpty();
        assertThat(conv.createdAt()).isEqualTo(FIXED_CLOCK.instant());
        assertThat(conv.updatedAt()).isEqualTo(FIXED_CLOCK.instant());
        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createNewInsertsDocumentWhenMongoSatisfied() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase(DB)).thenReturn(db);
        when(db.getCollection(COLL)).thenReturn(coll);

        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);
        AssistantConversation conv = repo.createNew("OWNER");

        verify(coll, times(1)).insertOne(any(Document.class));
        assertThat(conv.tenantId()).isEqualTo("OWNER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendTurnIsNoOpInStubMode() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);

        repo.appendTurn("OWNER", "conv-1", AssistantTurn.user("hi", FIXED_CLOCK.instant()), BigDecimal.ZERO);

        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendTurnIssuesUpdateOne() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase(DB)).thenReturn(db);
        when(db.getCollection(COLL)).thenReturn(coll);

        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);
        repo.appendTurn(
                "OWNER",
                "conv-1",
                AssistantTurn.assistant("hello", FIXED_CLOCK.instant(), new BigDecimal("0.0150")),
                new BigDecimal("0.0150"));

        verify(coll, times(1)).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendTurnSwallowsRuntimeException() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase(DB)).thenReturn(db);
        when(db.getCollection(COLL)).thenReturn(coll);
        when(coll.updateOne(any(Bson.class), any(Bson.class))).thenThrow(new RuntimeException("mongo down"));

        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);
        // Must not propagate.
        repo.appendTurn("OWNER", "conv-1", AssistantTurn.user("hi", FIXED_CLOCK.instant()), BigDecimal.ZERO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentForTenantReturnsEmptyOnZeroLimit() {
        Instance<MongoClient> instance = mock(Instance.class);
        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);

        assertThat(repo.recentForTenant("OWNER", 0)).isEmpty();
        verify(instance, never()).isUnsatisfied();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentForTenantReturnsParsedList() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> findIt = mock(FindIterable.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase(DB)).thenReturn(db);
        when(db.getCollection(COLL)).thenReturn(coll);
        when(coll.find(eq(Filters.eq("tenant_id", "OWNER")))).thenReturn(findIt);
        when(findIt.sort(any(Bson.class))).thenReturn(findIt);
        when(findIt.limit(20)).thenReturn(findIt);
        Document one = new Document();
        one.put("tenant_id", "OWNER");
        one.put("conversation_id", "conv-x");
        one.put("created_at", Date.from(FIXED_CLOCK.instant()));
        one.put("updated_at", Date.from(FIXED_CLOCK.instant()));
        one.put("turns", Collections.emptyList());
        one.put("total_cost_usd", "0");
        // FindIterable extends MongoIterable which extends Iterable<T>; the
        // for-each loop calls iterator() returning MongoCursor. Stub a cursor
        // that surfaces our single doc.
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(one);
        when(findIt.iterator()).thenReturn(cursor);

        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);
        List<AssistantConversation> recent = repo.recentForTenant("OWNER", 20);

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).conversationId()).isEqualTo("conv-x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentForTenantSwallowsRuntimeException() {
        Instance<MongoClient> instance = mock(Instance.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(client);
        when(client.getDatabase(DB)).thenReturn(db);
        when(db.getCollection(COLL)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenThrow(new RuntimeException("mongo down"));

        AssistantConversationRepository repo = new AssistantConversationRepository(instance, FIXED_CLOCK, DB, COLL);
        assertThat(repo.recentForTenant("OWNER", 20)).isEmpty();
    }
}
