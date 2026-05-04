package com.levelsweep.userconfig.api;

import com.levelsweep.userconfig.store.TenantConfig;
import com.levelsweep.userconfig.store.TenantConfigRepository;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant configuration CRUD API.
 *
 * <pre>
 * GET  /config/{tenantId}                — fetch the full config row
 * PUT  /config/{tenantId}                — full-replace update (idempotent)
 * GET  /config/{tenantId}/feature-flags  — flags-only read for hot-path consumers
 * </pre>
 *
 * <p>Phase A authentication posture: <b>no AuthN here</b>. The BFF
 * (api-gateway-bff) is the public ingress point and validates the Auth0 JWT,
 * extracts {@code X-Tenant-Id}, and proxies to this service. The K8s
 * NetworkPolicy in the Helm chart restricts ingress to the same namespace
 * (Phase 7 narrows further to the BFF SA) so external traffic cannot reach
 * the endpoints. Phase B per-user OAuth + JWT validation is gated behind the
 * {@code phase-b-multi-tenant-onboarding} flag (CLAUDE.md guardrail #1).
 *
 * <p>Multi-tenant: every endpoint is path-scoped by {@code tenantId} and
 * validated non-blank. There is no cross-tenant query path; we do not ship
 * one. Phase A operates only on {@code OWNER}.
 *
 * <p>PUT semantics: full-replace, idempotent. The body's {@code tenantId} is
 * ignored — the path is authoritative. {@code createdAt} is preserved from
 * the existing row (or set to now() on first insert). {@code updatedAt} is
 * always stamped from the injected clock at the moment of the call.
 */
@RestController
@RequestMapping("/config")
public class TenantConfigController {

    private static final Logger LOG = LoggerFactory.getLogger(TenantConfigController.class);

    private final TenantConfigRepository repository;
    private final Clock clock;

    public TenantConfigController(TenantConfigRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<?> get(@PathVariable("tenantId") String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId must not be blank"));
        }
        Optional<TenantConfig> row = repository.find(tenantId);
        if (row.isEmpty()) {
            LOG.debug("tenant_config not found tenantId={}", tenantId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TenantConfigDto.fromDomain(row.get()));
    }

    @PutMapping("/{tenantId}")
    public ResponseEntity<?> put(@PathVariable("tenantId") String tenantId, @Valid @RequestBody TenantConfigDto body) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId must not be blank"));
        }
        Instant now = clock.instant();
        // Preserve created_at when the row exists (it's immutable post-insert
        // per the schema design); seed it to now() on first insert. The body's
        // created_at is ignored — server-derived only.
        Instant createdAt =
                repository.find(tenantId).map(TenantConfig::createdAt).orElse(now);
        TenantConfig cfg;
        try {
            cfg = body.toDomain(tenantId, createdAt, now);
        } catch (IllegalArgumentException e) {
            // Domain-record validation kicked in (e.g. positionSizePct out of
            // (0, 1]). Surface as 400 rather than 500.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        repository.upsert(cfg);
        LOG.info(
                "tenant_config upserted tenantId={} schemaVersion={} updatedAt={}",
                tenantId,
                cfg.schemaVersion(),
                cfg.updatedAt());
        return ResponseEntity.ok(TenantConfigDto.fromDomain(cfg));
    }

    @GetMapping("/{tenantId}/feature-flags")
    public ResponseEntity<?> getFlags(@PathVariable("tenantId") String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId must not be blank"));
        }
        Optional<TenantConfig> row = repository.find(tenantId);
        if (row.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(FeatureFlagsDto.fromDomain(row.get().featureFlags()));
    }
}
