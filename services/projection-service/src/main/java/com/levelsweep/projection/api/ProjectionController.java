package com.levelsweep.projection.api;

import com.levelsweep.projection.cache.ProjectionRunDocument;
import com.levelsweep.projection.cache.ProjectionRunRepository;
import com.levelsweep.projection.domain.ProjectionRequest;
import com.levelsweep.projection.domain.ProjectionResult;
import com.levelsweep.projection.engine.MonteCarloEngine;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Projection REST API.
 *
 * <pre>
 * POST /projection/run               — body: ProjectionRequest; runs Monte Carlo, returns ProjectionResult
 * GET  /projection/last/{tenantId}   — returns the most recent cached run for the tenant
 * </pre>
 *
 * <p>Phase A authentication: <b>none enforced here</b>. The BFF
 * (api-gateway-bff) is the public ingress point and validates the Auth0 JWT;
 * this service is reachable inside-cluster only (NetworkPolicy restricts
 * ingress to the same namespace). Phase B per-user OAuth + JWT validation is
 * gated behind the {@code phase-b-multi-tenant-onboarding} flag (CLAUDE.md
 * guardrail #1).
 *
 * <p>Multi-tenant: every endpoint is tenantId-scoped. There is no cross-tenant
 * query path; we do not ship one. Phase A operates only on {@code OWNER}.
 *
 * <p>Determinism: when the request omits {@code seed}, the controller derives
 * a deterministic seed from {@code SHA-256(tenantId, normalised request)} via
 * {@link ProjectionRequestHasher}. Identical requests from the same tenant
 * therefore produce identical results across replays — the contract asserted
 * by {@code ProjectionControllerTest}.
 */
@RestController
@RequestMapping("/projection")
public class ProjectionController {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectionController.class);

    private final MonteCarloEngine engine;
    private final ProjectionRunRepository repository;
    private final ProjectionRequestHasher hasher;
    private final Clock clock;

    public ProjectionController(
            MonteCarloEngine engine, ProjectionRunRepository repository, ProjectionRequestHasher hasher, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@Valid @RequestBody ProjectionRequest request) {
        // Bean Validation has already enforced ranges; the only remaining
        // sanity check is the hard-cap on simulations, defensive against
        // future @Max bumps.
        if (request.simulations() > ProjectionRequest.MAX_SIMULATIONS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "simulations capped at " + ProjectionRequest.MAX_SIMULATIONS));
        }

        String requestHash = hasher.hash(request);
        long seed = request.seed() != null ? request.seed() : hasher.seedFromHash(requestHash);

        ProjectionResult engineOutput = engine.run(request, seed);
        // Stamp the canonical request hash on the result so the wire shape
        // carries it back to the caller (engine returns "" for the hash field).
        ProjectionResult result = new ProjectionResult(
                engineOutput.p10(),
                engineOutput.p25(),
                engineOutput.p50(),
                engineOutput.p75(),
                engineOutput.p90(),
                engineOutput.mean(),
                engineOutput.ruinProbability(),
                engineOutput.simulationsRun(),
                requestHash);

        ProjectionRunDocument doc =
                new ProjectionRunDocument(request.tenantId(), requestHash, request, result, clock.instant());
        repository.save(doc);

        LOG.info(
                "projection run tenant={} requestHash={} sims={} p50={} ruinProb={}",
                request.tenantId(),
                requestHash,
                result.simulationsRun(),
                result.p50(),
                result.ruinProbability());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/last/{tenantId}")
    public ResponseEntity<?> last(@PathVariable("tenantId") String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId must not be blank"));
        }
        Optional<ProjectionRunDocument> doc = repository.findLatest(tenantId);
        if (doc.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // The dashboard's "Projection" panel needs both the inputs and the
        // output for the most recent run; return the full document.
        return ResponseEntity.ok(doc.get());
    }
}
