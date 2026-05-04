package com.levelsweep.journal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Unit tests for {@link AuditRepository}. Pure Mockito — no real Mongo. Asserts:
 *
 * <ul>
 *   <li>tenantId is always part of the criteria (multi-tenant scope).</li>
 *   <li>Optional filters (eventType, from, to) compose correctly when
 *       any/all are present.</li>
 *   <li>Pagination clamping — page ≥ 0, size ∈ [1, 500].</li>
 *   <li>Sort is occurred_at DESC — dashboard wants most-recent first.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuditRepositoryTest {

    @Mock
    private MongoTemplate mongo;

    @Test
    void findScopesEveryQueryByTenantId() {
        AuditRepository repo = new AuditRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("audit_log.events")))
                .thenReturn(List.of());

        repo.find("OWNER", Optional.empty(), Optional.empty(), Optional.empty(), 0, 50);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(Document.class), eq("audit_log.events"));
        Document criteriaDoc = captor.getValue().getQueryObject();
        assertThat(criteriaDoc).containsEntry("tenant_id", "OWNER");
    }

    @Test
    void findWithEventTypeFilterAddsDiscriminator() {
        AuditRepository repo = new AuditRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("audit_log.events")))
                .thenReturn(List.of());

        repo.find("OWNER", Optional.of("FILL"), Optional.empty(), Optional.empty(), 0, 50);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(Document.class), eq("audit_log.events"));
        Document criteriaDoc = captor.getValue().getQueryObject();
        assertThat(criteriaDoc).containsEntry("tenant_id", "OWNER");
        assertThat(criteriaDoc).containsEntry("event_type", "FILL");
    }

    @Test
    void findWithDateRangeAddsBothBounds() {
        AuditRepository repo = new AuditRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("audit_log.events")))
                .thenReturn(List.of());

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-02T00:00:00Z");
        repo.find("OWNER", Optional.empty(), Optional.of(from), Optional.of(to), 0, 50);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(Document.class), eq("audit_log.events"));
        Query q = captor.getValue();
        // The composed query includes an $and with the two range bounds.
        // Render to string for a stable assertion that the bounds are wired
        // (the exact internal Criteria shape is a Spring Data implementation
        // detail).
        String rendered = q.getQueryObject().toString();
        assertThat(rendered).contains("tenant_id");
        assertThat(rendered).contains("occurred_at");
        // $gte and $lte both rendered.
        assertThat(rendered).contains("$gte");
        assertThat(rendered).contains("$lte");
    }

    @Test
    void findWithFromOnlyOmitsTo() {
        AuditRepository repo = new AuditRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("audit_log.events")))
                .thenReturn(List.of());

        repo.find(
                "OWNER", Optional.empty(), Optional.of(Instant.parse("2026-05-01T00:00:00Z")), Optional.empty(), 0, 50);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(Document.class), eq("audit_log.events"));
        String rendered = captor.getValue().getQueryObject().toString();
        assertThat(rendered).contains("$gte");
        assertThat(rendered).doesNotContain("$lte");
    }

    @Test
    void findSortsByOccurredAtDescending() {
        AuditRepository repo = new AuditRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("audit_log.events")))
                .thenReturn(List.of());

        repo.find("OWNER", Optional.empty(), Optional.empty(), Optional.empty(), 0, 50);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(Document.class), eq("audit_log.events"));
        Document sortDoc = captor.getValue().getSortObject();
        // Dashboard surfaces most recent first per architecture-spec §10.
        assertThat(sortDoc).containsEntry("occurred_at", -1);
    }

    @Test
    void findClampsPageAndSize() {
        AuditRepository repo = new AuditRepository(mongo);
        when(mongo.find(any(Query.class), eq(Document.class), eq("audit_log.events")))
                .thenReturn(List.of());

        // Negative page → 0; zero size → 1; oversized → 500.
        repo.find("OWNER", Optional.empty(), Optional.empty(), Optional.empty(), -5, 0);
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(Document.class), eq("audit_log.events"));
        Query q = captor.getValue();
        assertThat(q.getSkip()).isEqualTo(0L);
        assertThat(q.getLimit()).isEqualTo(1);

        repo.find("OWNER", Optional.empty(), Optional.empty(), Optional.empty(), 2, 100_000);
        verify(mongo, org.mockito.Mockito.times(2)).find(captor.capture(), eq(Document.class), eq("audit_log.events"));
        Query q2 = captor.getValue();
        assertThat(q2.getSkip()).isEqualTo(1000L); // page=2 * size=500
        assertThat(q2.getLimit()).isEqualTo(500);
    }

    @Test
    void findRejectsBlankTenantId() {
        AuditRepository repo = new AuditRepository(mongo);
        assertThatThrownBy(() -> repo.find("", Optional.empty(), Optional.empty(), Optional.empty(), 0, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> repo.find(null, Optional.empty(), Optional.empty(), Optional.empty(), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countMirrorsFindFilters() {
        AuditRepository repo = new AuditRepository(mongo);
        when(mongo.count(any(Query.class), eq("audit_log.events"))).thenReturn(7L);

        long total = repo.count("OWNER", Optional.of("FILL"), Optional.empty(), Optional.empty());

        assertThat(total).isEqualTo(7L);
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captor.capture(), eq("audit_log.events"));
        Document criteriaDoc = captor.getValue().getQueryObject();
        assertThat(criteriaDoc).containsEntry("tenant_id", "OWNER");
        assertThat(criteriaDoc).containsEntry("event_type", "FILL");
    }
}
