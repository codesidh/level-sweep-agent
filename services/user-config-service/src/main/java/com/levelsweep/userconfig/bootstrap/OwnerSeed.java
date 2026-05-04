package com.levelsweep.userconfig.bootstrap;

import com.levelsweep.userconfig.store.TenantConfig;
import com.levelsweep.userconfig.store.TenantConfigRepository;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Application-ready listener that ensures a {@code tenant_config} row exists
 * for the bootstrap (Phase A owner) tenant.
 *
 * <p>Per CLAUDE.md guardrail #1 the system is "Phase A only — single-user
 * (owner) operation"; the {@code OWNER} tenant must always have a config row
 * so the Decision Engine can pull {@code position_size_pct} +
 * {@code daily_loss_budget} on the first signal evaluation after a fresh
 * deployment without a manual {@code PUT}.
 *
 * <p>Idempotent: {@link TenantConfigRepository#insertIfMissing(TenantConfig)}
 * is a no-op when the row already exists (subsequent restarts log a DEBUG
 * line and return). The first-run insert uses {@link TenantConfig#defaultsFor}
 * — the same defaults documented in the {@code TenantConfig} record header.
 *
 * <p>Why {@link ApplicationReadyEvent} not {@code @PostConstruct}: Flyway
 * runs migrations between bean construction and {@code ApplicationReadyEvent}
 * (Spring Boot wires Flyway into the {@code DataSourceInitialization}
 * lifecycle). A {@code @PostConstruct} in the seeder would race the schema
 * migration on a fresh deployment and fail with a "table not found" SQL
 * error. {@code ApplicationReadyEvent} fires after Flyway completes.
 */
@Component
public class OwnerSeed implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(OwnerSeed.class);

    private final TenantConfigRepository repository;
    private final Clock clock;
    private final String bootstrapTenantId;

    public OwnerSeed(
            TenantConfigRepository repository,
            Clock clock,
            @Value("${levelsweep.tenant.bootstrap-id:OWNER}") String bootstrapTenantId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bootstrapTenantId = Objects.requireNonNull(bootstrapTenantId, "bootstrapTenantId");
        if (this.bootstrapTenantId.isBlank()) {
            throw new IllegalArgumentException("bootstrap-id must not be blank");
        }
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        TenantConfig defaults = TenantConfig.defaultsFor(bootstrapTenantId, clock.instant());
        boolean inserted = repository.insertIfMissing(defaults);
        if (inserted) {
            LOG.info(
                    "bootstrap tenant_config row inserted tenantId={} dailyLossBudget={} maxTradesPerDay={}"
                            + " positionSizePct={} sentinelConfidenceThreshold={}",
                    defaults.tenantId(),
                    defaults.dailyLossBudget(),
                    defaults.maxTradesPerDay(),
                    defaults.positionSizePct(),
                    defaults.sentinelConfidenceThreshold());
        } else {
            LOG.info("bootstrap tenant_config row already present, skipping seed tenantId={}", bootstrapTenantId);
        }
    }
}
