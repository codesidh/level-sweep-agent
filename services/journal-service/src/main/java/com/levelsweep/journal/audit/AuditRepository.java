package com.levelsweep.journal.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Hand-rolled query API over the {@code audit_log.events} collection. Mirrors
 * the {@code execution-service}'s {@code EodFlattenAuditRepository} pattern —
 * raw {@link MongoTemplate} access rather than a Spring Data repository so
 * the polymorphic {@code payload} {@link Document} stays first-class on both
 * the write path and the read path.
 *
 * <p>Phase A query shape (Phase 6 cut): {@code GET /journal/{tenantId}} with
 * optional {@code type}, {@code from}, {@code to}, {@code page}, {@code size}.
 * Default ordering is {@code occurred_at DESC} — the dashboard surfaces the
 * most recent activity first. Pagination is offset-based for Phase 6
 * simplicity; cursor-based pagination is a Phase 7 follow-up if the page-skip
 * cost on large tenants becomes measurable (cold path; currently not a worry).
 *
 * <p>Multi-tenant: every query method requires {@code tenantId}. Constructing
 * a query that omits it would scan across tenants and is a hard violation of
 * the multi-tenant-readiness skill. The query API is intentionally narrow.
 */
@Repository
public class AuditRepository {

    private final MongoTemplate mongo;

    public AuditRepository(MongoTemplate mongo) {
        this.mongo = Objects.requireNonNull(mongo, "mongo");
    }

    /**
     * Page through audit rows for a single tenant.
     *
     * @param tenantId  required, never blank
     * @param eventType optional discriminator filter (e.g. "FILL")
     * @param from      optional inclusive lower bound on {@code occurred_at}
     * @param to        optional inclusive upper bound on {@code occurred_at}
     * @param page      0-based page index; clamped to ≥ 0
     * @param size      page size; clamped to range [1, 500] to prevent
     *                  accidental "give me the whole tenant" scans
     * @return list of raw {@link Document} rows; empty when no match
     */
    public List<Document> find(
            String tenantId,
            Optional<String> eventType,
            Optional<Instant> from,
            Optional<Instant> to,
            int page,
            int size) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        int safePage = Math.max(0, page);
        int safeSize = clamp(size, 1, 500);

        Query query = Query.query(buildCriteria(tenantId, eventType, from, to))
                .with(Sort.by(Sort.Direction.DESC, "occurred_at"))
                .skip((long) safePage * safeSize)
                .limit(safeSize);

        return new ArrayList<>(mongo.find(query, Document.class, AuditWriter.COLLECTION));
    }

    /** Count matching rows; same filter semantics as {@link #find}. */
    public long count(String tenantId, Optional<String> eventType, Optional<Instant> from, Optional<Instant> to) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return mongo.count(Query.query(buildCriteria(tenantId, eventType, from, to)), AuditWriter.COLLECTION);
    }

    /**
     * Compose the per-tenant criteria with optional event_type and occurred_at
     * range filters. Pulled out so {@link #find} and {@link #count} stay in
     * lockstep — a filter added to one always lands in the other.
     *
     * <p>Note on the $and composition: when both date bounds are present, we
     * wrap the tenant_id criteria + the occurred_at criteria into a top-level
     * $and. Chaining .and("occurred_at").gte(...).lte(...) on the same
     * criteria builder overwrites the $gte with $lte (Spring Data quirk),
     * so the $and wrapper is required for correct upper+lower bound queries.
     */
    private static Criteria buildCriteria(
            String tenantId, Optional<String> eventType, Optional<Instant> from, Optional<Instant> to) {
        Criteria base = Criteria.where("tenant_id").is(tenantId);
        if (eventType.isPresent()) {
            base = base.and("event_type").is(eventType.get());
        }
        if (from.isPresent() || to.isPresent()) {
            Criteria occurredAt = Criteria.where("occurred_at");
            if (from.isPresent()) {
                occurredAt = occurredAt.gte(from.get());
            }
            if (to.isPresent()) {
                occurredAt = occurredAt.lte(to.get());
            }
            return new Criteria().andOperator(base, occurredAt);
        }
        return base;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
