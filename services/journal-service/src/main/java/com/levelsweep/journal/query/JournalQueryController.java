package com.levelsweep.journal.query;

import com.levelsweep.journal.audit.AuditRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trade Journal query API.
 *
 * <pre>
 * GET /journal/{tenantId}?type=FILL&amp;from=2026-05-01T00:00:00Z&amp;to=2026-05-02T00:00:00Z&amp;page=0&amp;size=50
 * </pre>
 *
 * <p>Returns audit rows from {@code audit_log.events} per architecture-spec
 * §13.2, scoped to the tenant in the URL. The dashboard's "Trade Journal"
 * pane (Phase 7) is the primary client; operators can also curl the endpoint
 * for ad-hoc forensics.
 *
 * <p>Phase A: <b>owner-only</b> — no authentication is enforced here. The
 * BFF (api-gateway-bff) sits in front and validates Auth0 JWT + extracts
 * {@code X-Tenant-Id}; that wiring is Phase 5. Until then the endpoint is
 * inside-cluster only (NetworkPolicy in the Helm chart denies external
 * ingress) and Phase B per-user OAuth lands behind the
 * {@code phase-a-b-feature-flags} skill.
 *
 * <p>Pagination: offset-based with {@code page} (default 0) and {@code size}
 * (default 50, max 500). The {@link AuditRepository} clamps both. Cursor-
 * based pagination is a Phase 7 follow-up if page-skip cost on large tenants
 * becomes measurable.
 *
 * <p>Multi-tenant: every query is per-tenant scoped — the {@code tenantId} is
 * mandatory in the path. There is no "give me all tenants" endpoint and we
 * do not ship one.
 */
@RestController
@RequestMapping("/journal")
public class JournalQueryController {

    private static final Logger LOG = LoggerFactory.getLogger(JournalQueryController.class);

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 50;

    private final AuditRepository repository;

    public JournalQueryController(AuditRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<Map<String, Object>> query(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "50") int size) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId must not be blank"));
        }
        Optional<String> typeOpt = Optional.ofNullable(type).filter(s -> !s.isBlank());
        Optional<Instant> fromOpt;
        Optional<Instant> toOpt;
        try {
            fromOpt = parseInstant(from);
            toOpt = parseInstant(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "from/to must be ISO-8601 instants"));
        }

        int pageNo = Math.max(0, page);
        int pageSize = clamp(size, 1, 500);

        List<Document> rows = repository.find(tenantId, typeOpt, fromOpt, toOpt, pageNo, pageSize);
        long total = repository.count(tenantId, typeOpt, fromOpt, toOpt);

        LOG.debug(
                "journal query tenant={} type={} from={} to={} page={} size={} returned={} total={}",
                tenantId,
                typeOpt.orElse(null),
                fromOpt.orElse(null),
                toOpt.orElse(null),
                pageNo,
                pageSize,
                rows.size(),
                total);

        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "page", pageNo,
                "size", pageSize,
                "total", total,
                "rows", rows);
        return ResponseEntity.ok(body);
    }

    private static Optional<Instant> parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Instant.parse(s));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
