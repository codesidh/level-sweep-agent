package com.levelsweep.userconfig.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.levelsweep.userconfig.store.TenantConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire shape for {@link TenantConfig} on the REST boundary.
 *
 * <p>{@code tenantId}, {@code createdAt}, {@code schemaVersion}, and
 * {@code updatedAt} are server-derived on PUT (the controller takes the
 * tenant from the URL path, preserves the existing {@code createdAt} when
 * the row exists, and stamps {@code updatedAt} from the system clock).
 *
 * <p>Bean Validation constraints enforce the same numeric ranges the domain
 * record validates in its compact constructor — duplicated deliberately so
 * malformed PUTs return HTTP 400 with a structured error rather than a
 * 500 from the deeper domain check.
 */
public record TenantConfigDto(
        @JsonProperty("tenant_id") String tenantId,
        @NotNull
                @DecimalMin(value = "0.01", inclusive = true)
                @DecimalMax(value = "1000000.00", inclusive = true)
                @JsonProperty("daily_loss_budget")
                BigDecimal dailyLossBudget,
        @Min(1) @JsonProperty("max_trades_per_day") int maxTradesPerDay,
        @NotNull
                @DecimalMin(value = "0.0001", inclusive = true)
                @DecimalMax(value = "1.0000", inclusive = true)
                @JsonProperty("position_size_pct")
                BigDecimal positionSizePct,
        @NotNull
                @DecimalMin(value = "0.00", inclusive = true)
                @DecimalMax(value = "1.00", inclusive = true)
                @JsonProperty("sentinel_confidence_threshold")
                BigDecimal sentinelConfidenceThreshold,
        @NotNull @Valid @JsonProperty("feature_flags") FeatureFlagsDto featureFlags,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("schema_version") int schemaVersion) {

    @JsonCreator
    public TenantConfigDto {
        // No-op compact constructor: validation is by Bean Validation
        // annotations on the controller, plus the domain record's deep
        // checks at toDomain() time.
    }

    /**
     * Project to the persistence record. The caller passes the path-derived
     * tenantId, the resolved createdAt (existing row's value or now() for a
     * fresh insert), and the system-clock now() for updatedAt — none of
     * those are trusted from the request body.
     */
    public TenantConfig toDomain(String tenantId, Instant createdAt, Instant updatedAt) {
        return new TenantConfig(
                tenantId,
                dailyLossBudget,
                maxTradesPerDay,
                positionSizePct,
                sentinelConfidenceThreshold,
                featureFlags.toDomain(),
                createdAt,
                updatedAt,
                schemaVersion <= 0 ? TenantConfig.CURRENT_SCHEMA_VERSION : schemaVersion);
    }

    /** Project a persisted record to the wire shape. */
    public static TenantConfigDto fromDomain(TenantConfig cfg) {
        return new TenantConfigDto(
                cfg.tenantId(),
                cfg.dailyLossBudget(),
                cfg.maxTradesPerDay(),
                cfg.positionSizePct(),
                cfg.sentinelConfidenceThreshold(),
                FeatureFlagsDto.fromDomain(cfg.featureFlags()),
                cfg.createdAt(),
                cfg.updatedAt(),
                cfg.schemaVersion());
    }
}
