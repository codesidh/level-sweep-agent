package com.levelsweep.aiagent.narrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link TradeNarrativeRepository}. Mockito over MongoClient —
 * no real Mongo. Mirrors {@code DailyCostMongoRepositoryTest} pattern.
 */
class TradeNarrativeRepositoryTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-05-02T15:30:00Z");

    @Test
    void toDocumentIncludesAllFields() {
        TradeNarrative n = new TradeNarrative(
                "OWNER",
                "TR_001",
                "Entry order filled at $1.42 for 2 contracts.",
                GENERATED_AT,
                "claude-sonnet-4-6",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");

        Document d = TradeNarrativeRepository.toDocument(n, "FILL");

        assertThat(d.getString("tenant_id")).isEqualTo("OWNER");
        assertThat(d.getString("trade_id")).isEqualTo("TR_001");
        assertThat(d.getString("event_type")).isEqualTo("FILL");
        assertThat(d.getString("narrative")).isEqualTo("Entry order filled at $1.42 for 2 contracts.");
        assertThat(d.getString("model_used")).isEqualTo("claude-sonnet-4-6");
        assertThat(d.getString("prompt_hash"))
                .isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        assertThat(d.get("generated_at")).isEqualTo(Date.from(GENERATED_AT));
    }

    @Test
    void toDocumentNullEventTypeIsBlankNotNull() {
        TradeNarrative n = sampleNarrative();
        Document d = TradeNarrativeRepository.toDocument(n, null);

        // We never persist a null — empty string is the canonical "unknown".
        assertThat(d.getString("event_type")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIsNoOpWhenMongoUnsatisfied() {
        Instance<MongoClient> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        TradeNarrativeRepository repo = new TradeNarrativeRepository(instance, "level_sweep", "trade_narratives");

        repo.save(sampleNarrative(), "FILL");

        verify(instance, times(1)).isUnsatisfied();
        verify(instance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIsNoOpWhenNarrativeNull() {
        Instance<MongoClient> instance = mock(Instance.class);
        TradeNarrativeRepository repo = new TradeNarrativeRepository(instance, "level_sweep", "trade_narratives");

        repo.save(null, "FILL");

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
        when(db.getCollection("trade_narratives")).thenReturn(coll);

        TradeNarrativeRepository repo = new TradeNarrativeRepository(instance, "level_sweep", "trade_narratives");
        TradeNarrative n = sampleNarrative();

        repo.save(n, "STOP");

        verify(coll, times(1)).insertOne(eq(TradeNarrativeRepository.toDocument(n, "STOP")));
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
        when(db.getCollection("trade_narratives")).thenReturn(coll);
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down"))
                .when(coll)
                .insertOne(any(Document.class));

        TradeNarrativeRepository repo = new TradeNarrativeRepository(instance, "level_sweep", "trade_narratives");

        // Must not throw — narrator advisory means a Mongo blip can never
        // propagate up the listener path (CLAUDE.md guardrail #3).
        repo.save(sampleNarrative(), "FILL");
    }

    private static TradeNarrative sampleNarrative() {
        return new TradeNarrative(
                "OWNER",
                "TR_001",
                "Entry order filled at $1.42 for 2 contracts.",
                GENERATED_AT,
                "claude-sonnet-4-6",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
    }
}
