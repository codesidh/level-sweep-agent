package com.levelsweep.userconfig.store;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable per-tenant configuration row stored in MS SQL {@code tenant_config}
 * (Flyway migration {@code V300__create_tenant_config.sql}).
 *
 * <p>Fields map 1:1 to columns. Numeric ranges are validated in the compact
 * constructor — invalid configs cannot be persisted. Per architecture-spec
 * §13.1 this is financial state and lives in MS SQL alongside trades, orders,
 * and risk events.
 *
 * <p>Determinism (replay-parity skill): the Decision Engine reads
 * {@code positionSizePct}, {@code dailyLossBudget}, {@code maxTradesPerDay},
 * and {@code sentinelConfidenceThreshold} on every signal evaluation. Any
 * change to these fields' semantics must come with a new {@code schemaVersion}
 * and replay-parity coverage.
 *
 * @param tenantId                     multi-tenant primary key — never blank.
 * @param dailyLossBudget              max realised + open loss in USD before
 *     the Risk FSM trips into HALTED (architecture-spec §11). Required &gt; 0.
 * @param maxTradesPerDay              hard cap on entries per RTH session.
 *     Default 5 per requirements §11. Required &gt; 0.
 * @param positionSizePct              fraction of equity per entry. 0 &lt; v ≤ 1.
 *     Phase A default ~0.02 (2% of paper account).
 * @param sentinelConfidenceThreshold  Pre-Trade Sentinel veto threshold. CLAUDE
 *     guardrail #2 mandates ≥ 0.85; Decision Engine refuses to enter a trade
 *     when Sentinel confidence falls below this.
 * @param featureFlags                 Phase B gating flags — see
 *     {@link FeatureFlags}. Stored as a JSON blob in the
 *     {@code feature_flags} column.
 * @param createdAt                    insert timestamp; immutable post-insert.
 * @param updatedAt                    last PUT timestamp; bumped on every
 *     update.
 * @param schemaVersion                config-row schema version (currently 1).
 *     Bumped when this record's shape evolves; old rows are migrated by a
 *     subsequent Flyway migration, never mutated in place.
 */
public record TenantConfig(
        String tenantId,
        BigDecimal dailyLossBudget,
        int maxTradesPerDay,
        BigDecimal positionSizePct,
        BigDecimal sentinelConfidenceThreshold,
        FeatureFlags featureFlags,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {

    /** Phase A default sentinel threshold per CLAUDE.md guardrail #2 (≥ 0.85). */
    public static final BigDecimal DEFAULT_SENTINEL_THRESHOLD = new BigDecimal("0.85");

    /** Phase A default position size — 2% of paper account per requirements §11. */
    public static final BigDecimal DEFAULT_POSITION_SIZE_PCT = new BigDecimal("0.0200");

    /** Phase A default daily loss budget — $300 on the paper account. */
    public static final BigDecimal DEFAULT_DAILY_LOSS_BUDGET = new BigDecimal("300.00");

    /** Phase A default max trades per day per requirements §11. */
    public static final int DEFAULT_MAX_TRADES_PER_DAY = 5;

    /** Current schema version — bump alongside any breaking field-shape change. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TenantConfig {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dailyLossBudget, "dailyLossBudget");
        Objects.requireNonNull(positionSizePct, "positionSizePct");
        Objects.requireNonNull(sentinelConfidenceThreshold, "sentinelConfidenceThreshold");
        Objects.requireNonNull(featureFlags, "featureFlags");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (dailyLossBudget.signum() <= 0) {
            throw new IllegalArgumentException("dailyLossBudget must be > 0");
        }
        if (maxTradesPerDay <= 0) {
            throw new IllegalArgumentException("maxTradesPerDay must be > 0");
        }
        if (positionSizePct.signum() <= 0 || positionSizePct.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("positionSizePct must be in (0, 1]");
        }
        if (sentinelConfidenceThreshold.signum() < 0 || sentinelConfidenceThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("sentinelConfidenceThreshold must be in [0, 1]");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be > 0");
        }
    }

    /**
     * Build the Phase A default config for a tenant (used by {@code OwnerSeed}
     * to bootstrap the OWNER row on first start).
     */
    public static TenantConfig defaultsFor(String tenantId, Instant now) {
        return new TenantConfig(
                tenantId,
                DEFAULT_DAILY_LOSS_BUDGET,
                DEFAULT_MAX_TRADES_PER_DAY,
                DEFAULT_POSITION_SIZE_PCT,
                DEFAULT_SENTINEL_THRESHOLD,
                FeatureFlags.defaults(),
                now,
                now,
                CURRENT_SCHEMA_VERSION);
    }

    /** Return a copy with the given {@code updatedAt} timestamp. */
    public TenantConfig withUpdatedAt(Instant updatedAt) {
        return new TenantConfig(
                tenantId,
                dailyLossBudget,
                maxTradesPerDay,
                positionSizePct,
                sentinelConfidenceThreshold,
                featureFlags,
                createdAt,
                updatedAt,
                schemaVersion);
    }
}
