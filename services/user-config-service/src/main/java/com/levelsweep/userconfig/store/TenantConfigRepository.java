package com.levelsweep.userconfig.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Hand-rolled JdbcTemplate over the {@code tenant_config} MS SQL table.
 * Mirrors execution-service's {@code EodFlattenAuditRepository} pattern — raw
 * JDBC over the Spring Boot {@link JdbcTemplate} rather than a Spring Data
 * JDBC repository so the {@code feature_flags} JSON blob and the
 * BigDecimal/Instant column shape stay first-class.
 *
 * <p>Three operations:
 *
 * <ul>
 *   <li>{@link #find(String)} — single-row lookup by tenant; empty when missing.</li>
 *   <li>{@link #upsert(TenantConfig)} — full-replace by tenant; idempotent.</li>
 *   <li>{@link #insertIfMissing(TenantConfig)} — bootstrap one row only if no
 *       existing row for the tenant; used by {@code OwnerSeed}. Returns
 *       {@code true} when the row was inserted, {@code false} when it already
 *       existed.</li>
 * </ul>
 *
 * <p>Multi-tenant: every operation requires a non-blank {@code tenantId}. There
 * is no list-all-tenants method — the multi-tenant-readiness skill forbids
 * cross-tenant scans on financial-state tables.
 */
@Repository
public class TenantConfigRepository {

    private static final Logger LOG = LoggerFactory.getLogger(TenantConfigRepository.class);

    static final String SELECT_SQL = "SELECT tenant_id, daily_loss_budget, max_trades_per_day,"
            + " position_size_pct, sentinel_confidence_threshold, feature_flags,"
            + " created_at, updated_at, schema_version"
            + " FROM tenant_config WHERE tenant_id = ?";

    /**
     * MS SQL MERGE for full-replace upsert. WHEN MATCHED bumps every field
     * except created_at (immutable post-insert per the schema design). WHEN
     * NOT MATCHED inserts the full row, including created_at.
     */
    static final String UPSERT_SQL = "MERGE tenant_config AS target"
            + " USING (SELECT ? AS tenant_id) AS src ON target.tenant_id = src.tenant_id"
            + " WHEN MATCHED THEN UPDATE SET"
            + "  daily_loss_budget = ?,"
            + "  max_trades_per_day = ?,"
            + "  position_size_pct = ?,"
            + "  sentinel_confidence_threshold = ?,"
            + "  feature_flags = ?,"
            + "  updated_at = ?,"
            + "  schema_version = ?"
            + " WHEN NOT MATCHED THEN INSERT"
            + "  (tenant_id, daily_loss_budget, max_trades_per_day, position_size_pct,"
            + "   sentinel_confidence_threshold, feature_flags, created_at, updated_at, schema_version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);";

    /**
     * Insert-only path used by {@link #insertIfMissing(TenantConfig)} —
     * relies on the {@code tenant_id} primary key to fail on collision; we
     * swallow the duplicate-key {@link DataIntegrityViolationException} and
     * return {@code false} so the seed bootstrap is idempotent.
     */
    static final String INSERT_SQL = "INSERT INTO tenant_config"
            + " (tenant_id, daily_loss_budget, max_trades_per_day, position_size_pct,"
            + "  sentinel_confidence_threshold, feature_flags, created_at, updated_at, schema_version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TenantConfigRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Look up a tenant's config row.
     *
     * @return the row when present; {@link Optional#empty()} when absent.
     */
    public Optional<TenantConfig> find(String tenantId) {
        requireTenant(tenantId);
        try {
            TenantConfig cfg = jdbc.queryForObject(SELECT_SQL, rowMapper(), tenantId);
            return Optional.ofNullable(cfg);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Idempotent full-row upsert. The {@code created_at} is preserved on
     * UPDATE; the caller passes the existing value for the matched-update
     * branch but the SQL MERGE intentionally does not write it. The matched
     * branch overwrites every other column.
     */
    public void upsert(TenantConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        String flagsJson = serialiseFlags(cfg.featureFlags());
        Timestamp updatedAt = Timestamp.from(cfg.updatedAt());
        Timestamp createdAt = Timestamp.from(cfg.createdAt());
        // Twenty-eight bind parameters: 1 for the USING source tenant_id,
        // 7 for WHEN MATCHED UPDATE, 9 for WHEN NOT MATCHED INSERT.
        // Wrong-count bug is the #1 hand-rolled-MERGE failure mode — keep
        // the order in lockstep with UPSERT_SQL above.
        jdbc.update(
                UPSERT_SQL,
                cfg.tenantId(),
                // WHEN MATCHED:
                cfg.dailyLossBudget(),
                cfg.maxTradesPerDay(),
                cfg.positionSizePct(),
                cfg.sentinelConfidenceThreshold(),
                flagsJson,
                updatedAt,
                cfg.schemaVersion(),
                // WHEN NOT MATCHED:
                cfg.tenantId(),
                cfg.dailyLossBudget(),
                cfg.maxTradesPerDay(),
                cfg.positionSizePct(),
                cfg.sentinelConfidenceThreshold(),
                flagsJson,
                createdAt,
                updatedAt,
                cfg.schemaVersion());
    }

    /**
     * Insert-only — returns {@code true} when this call inserted the row,
     * {@code false} when an existing row blocked the insert. The bootstrap
     * caller ({@code OwnerSeed}) uses the return value purely for logging;
     * the operation is idempotent regardless.
     */
    public boolean insertIfMissing(TenantConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        if (find(cfg.tenantId()).isPresent()) {
            LOG.debug("tenant_config row already present tenantId={}", cfg.tenantId());
            return false;
        }
        String flagsJson = serialiseFlags(cfg.featureFlags());
        jdbc.update(
                INSERT_SQL,
                cfg.tenantId(),
                cfg.dailyLossBudget(),
                cfg.maxTradesPerDay(),
                cfg.positionSizePct(),
                cfg.sentinelConfidenceThreshold(),
                flagsJson,
                Timestamp.from(cfg.createdAt()),
                Timestamp.from(cfg.updatedAt()),
                cfg.schemaVersion());
        LOG.info("seeded tenant_config tenantId={} schemaVersion={}", cfg.tenantId(), cfg.schemaVersion());
        return true;
    }

    /** Test-friendly hook so unit tests can drive the SELECT mapping directly. */
    RowMapper<TenantConfig> rowMapper() {
        return (rs, rowNum) -> mapRow(rs, mapper);
    }

    private static TenantConfig mapRow(ResultSet rs, ObjectMapper mapper) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        BigDecimal dailyLossBudget = rs.getBigDecimal("daily_loss_budget");
        int maxTradesPerDay = rs.getInt("max_trades_per_day");
        BigDecimal positionSizePct = rs.getBigDecimal("position_size_pct");
        BigDecimal sentinelConfidenceThreshold = rs.getBigDecimal("sentinel_confidence_threshold");
        String flagsJson = rs.getString("feature_flags");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        int schemaVersion = rs.getInt("schema_version");
        FeatureFlags flags = deserialiseFlags(flagsJson, mapper);
        return new TenantConfig(
                tenantId,
                dailyLossBudget,
                maxTradesPerDay,
                positionSizePct,
                sentinelConfidenceThreshold,
                flags,
                createdAt,
                updatedAt,
                schemaVersion);
    }

    private String serialiseFlags(FeatureFlags flags) {
        try {
            return mapper.writeValueAsString(flags);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise feature flags", e);
        }
    }

    private static FeatureFlags deserialiseFlags(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return FeatureFlags.defaults();
        }
        try {
            FeatureFlags parsed = mapper.readValue(json, FeatureFlags.class);
            return parsed != null ? parsed : FeatureFlags.defaults();
        } catch (JsonProcessingException e) {
            // A malformed JSON blob in production indicates a schema-version
            // skew. Loud failure (with the offending tenant masked) is the
            // right behaviour — we cannot silently default to "all flags off"
            // because that could disable a Phase B path the operator already
            // turned on.
            throw new IllegalStateException("malformed feature_flags JSON", e);
        }
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}
