package com.levelsweep.projection.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.projection.domain.ProjectionRequest;
import com.levelsweep.projection.domain.ProjectionResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Unit tests for {@link ProjectionRunRepository}. Pure Mockito over
 * {@link MongoTemplate} — no real Mongo. Asserts:
 *
 * <ul>
 *   <li>save() forwards the document to the right collection.</li>
 *   <li>findLatest() scopes by tenantId and sorts by computedAt DESC.</li>
 *   <li>tenantId is required (multi-tenant scope).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProjectionRunRepositoryTest {

    @Mock
    private MongoTemplate mongo;

    private static ProjectionRunDocument sampleDoc(String tenantId, String hash) {
        ProjectionRequest req = new ProjectionRequest(tenantId, 10_000.0, 55.0, 50.0, 5, 12, 2.0, 1_000, null);
        ProjectionResult res = new ProjectionResult(8000, 9000, 10000, 11000, 12000, 10000, 0.05, 1_000, hash);
        return new ProjectionRunDocument(tenantId, hash, req, res, Instant.parse("2026-05-02T13:30:00Z"));
    }

    @Test
    void saveForwardsToCorrectCollection() {
        ProjectionRunRepository repo = new ProjectionRunRepository(mongo);
        ProjectionRunDocument doc = sampleDoc("OWNER", "hash-1");

        repo.save(doc);

        verify(mongo).save(doc, ProjectionRunDocument.COLLECTION);
    }

    @Test
    void findLatestScopesByTenantIdAndSortsDescending() {
        ProjectionRunRepository repo = new ProjectionRunRepository(mongo);
        when(mongo.findOne(any(Query.class), eq(ProjectionRunDocument.class), eq(ProjectionRunDocument.COLLECTION)))
                .thenReturn(sampleDoc("OWNER", "hash-1"));

        var found = repo.findLatest("OWNER");

        assertThat(found).isPresent();
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findOne(captor.capture(), eq(ProjectionRunDocument.class), eq(ProjectionRunDocument.COLLECTION));
        Query q = captor.getValue();
        // The query must filter by tenantId.
        assertThat(q.getQueryObject()).containsEntry("tenantId", "OWNER");
        // And sort by computedAt descending (= -1 in BSON sort doc).
        assertThat(q.getSortObject()).containsEntry("computedAt", -1);
        // Limit must be 1 — we only ever want the most recent.
        assertThat(q.getLimit()).isEqualTo(1);
    }

    @Test
    void findLatestReturnsEmptyWhenNoneFound() {
        ProjectionRunRepository repo = new ProjectionRunRepository(mongo);
        when(mongo.findOne(any(Query.class), eq(ProjectionRunDocument.class), eq(ProjectionRunDocument.COLLECTION)))
                .thenReturn(null);

        assertThat(repo.findLatest("OWNER")).isEmpty();
    }

    @Test
    void findLatestRejectsBlankTenantId() {
        ProjectionRunRepository repo = new ProjectionRunRepository(mongo);
        assertThatThrownBy(() -> repo.findLatest(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> repo.findLatest(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentEnforcesNonBlankTenantId() {
        ProjectionRequest req = new ProjectionRequest("OWNER", 10_000.0, 55.0, 50.0, 5, 12, 2.0, 1_000, null);
        ProjectionResult res = new ProjectionResult(0, 0, 0, 0, 0, 0, 0, 0, "h");
        Instant now = Instant.parse("2026-05-02T13:30:00Z");
        assertThatThrownBy(() -> new ProjectionRunDocument("", "h", req, res, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new ProjectionRunDocument("OWNER", "", req, res, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestHash");
    }
}
