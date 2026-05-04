package com.levelsweep.projection.cache;

import com.levelsweep.projection.domain.ProjectionRequest;
import com.levelsweep.projection.domain.ProjectionResult;
import java.time.Instant;
import java.util.Objects;

/**
 * Persisted shape of a projection run in the {@code projections.runs} Mongo
 * collection. One document per (tenantId, requestHash) pair. The same
 * (tenantId, requestHash) pair may occur many times — every POST writes a new
 * document and {@code GET /projection/last/{tenantId}} returns the most
 * recent one by {@code computedAt DESC}. We do NOT update-in-place because
 * the Phase 6 dashboard surfaces a "history" pane in Phase 7 and the audit
 * trail of repeat runs is the source for that panel.
 *
 * <p>This is a hand-rolled persistence record — like
 * {@code journal-service.AuditRecord} — rather than a Spring Data
 * {@code @Document} class. The repository writes raw Bson documents via
 * {@link org.springframework.data.mongodb.core.MongoTemplate} so the wire JSON
 * shape and the on-disk shape stay in lockstep.
 *
 * @param tenantId    multi-tenant key — every document is per-tenant scoped
 * @param requestHash SHA-256 hex of (tenantId, normalised request)
 * @param request     the original request payload (for replay / forensics)
 * @param result      the computed projection result
 * @param computedAt  server-side UTC timestamp at which the run was computed
 */
public record ProjectionRunDocument(
        String tenantId, String requestHash, ProjectionRequest request, ProjectionResult result, Instant computedAt) {

    /** Single Mongo collection per architecture-spec §13.2 family ("projections.runs"). */
    public static final String COLLECTION = "projections.runs";

    public ProjectionRunDocument {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(computedAt, "computedAt");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (requestHash.isBlank()) {
            throw new IllegalArgumentException("requestHash must not be blank");
        }
    }
}
