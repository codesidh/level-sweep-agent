package com.levelsweep.projection.cache;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Hand-rolled write-through cache for projection runs. Mirrors the
 * {@code journal-service.AuditRepository} pattern — raw {@link MongoTemplate}
 * over a single collection ({@link ProjectionRunDocument#COLLECTION}) rather
 * than a Spring Data repository so the polymorphic request/result payloads
 * stay first-class on both write and read.
 *
 * <p>Two operations:
 *
 * <ul>
 *   <li>{@link #save(ProjectionRunDocument)} — append a new run document.
 *       Every POST /projection/run writes one row regardless of whether the
 *       same (tenantId, requestHash) pair already exists; the
 *       {@code computedAt DESC} sort on read returns the most recent.</li>
 *   <li>{@link #findLatest(String)} — return the most recent run for a tenant
 *       (by {@code computedAt DESC}).</li>
 * </ul>
 *
 * <p>Multi-tenant: every operation requires a non-blank {@code tenantId}. There
 * is no "list all tenants" endpoint and we do not ship one — multi-tenant-
 * readiness skill rules out cross-tenant scans on per-tenant collections.
 */
@Repository
public class ProjectionRunRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectionRunRepository.class);

    private final MongoTemplate mongo;
    private final Clock clock;

    @Autowired
    public ProjectionRunRepository(MongoTemplate mongo) {
        this(mongo, Clock.systemUTC());
    }

    /** Test-friendly constructor — inject a fixed clock for computed_at assertions. */
    public ProjectionRunRepository(MongoTemplate mongo, Clock clock) {
        this.mongo = Objects.requireNonNull(mongo, "mongo");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Persist one run document. Throws on Mongo failure — the controller
     * catches and surfaces as 500. We do NOT swallow {@link DataAccessException}
     * because a silent cache write failure would leave the dashboard with a
     * "computed but never stored" run — confusing on subsequent GET-last calls.
     */
    public void save(ProjectionRunDocument doc) {
        Objects.requireNonNull(doc, "doc");
        try {
            mongo.save(doc, ProjectionRunDocument.COLLECTION);
        } catch (DataAccessException e) {
            LOG.error(
                    "projection insert failed tenant={} requestHash={} cause={}",
                    doc.tenantId(),
                    doc.requestHash(),
                    e.toString());
            throw e;
        }
    }

    /**
     * Return the most-recent projection run for a tenant, or
     * {@link Optional#empty()} when the tenant has none.
     */
    public Optional<ProjectionRunDocument> findLatest(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Query query = Query.query(Criteria.where("tenantId").is(tenantId))
                .with(Sort.by(Sort.Direction.DESC, "computedAt"))
                .limit(1);
        ProjectionRunDocument doc = mongo.findOne(query, ProjectionRunDocument.class, ProjectionRunDocument.COLLECTION);
        return Optional.ofNullable(doc);
    }

    /** Exposed for tests that need to assert clock-stamped writes. */
    Clock clock() {
        return clock;
    }
}
