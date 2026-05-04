package com.levelsweep.userconfig.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.userconfig.store.TenantConfig;
import com.levelsweep.userconfig.store.TenantConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * Unit tests for {@link OwnerSeed}. Pure Mockito over the repository — no
 * Spring context, no real DataSource. Asserts:
 *
 * <ul>
 *   <li>OWNER row inserted with the canonical Phase A defaults when missing.</li>
 *   <li>Idempotent: re-running the listener does NOT issue a second insert.</li>
 *   <li>Bootstrap tenant id is configurable but defaults to {@code OWNER}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OwnerSeedTest {

    @Mock
    private TenantConfigRepository repository;

    @Mock
    private ApplicationReadyEvent event;

    @Test
    void insertsOwnerRowWhenMissing() {
        Instant pinned = Instant.parse("2026-05-02T13:30:00Z");
        Clock clock = Clock.fixed(pinned, ZoneOffset.UTC);
        when(repository.insertIfMissing(any(TenantConfig.class))).thenReturn(true);

        OwnerSeed seed = new OwnerSeed(repository, clock, "OWNER");
        seed.onApplicationEvent(event);

        ArgumentCaptor<TenantConfig> captor = ArgumentCaptor.forClass(TenantConfig.class);
        verify(repository).insertIfMissing(captor.capture());
        TenantConfig seeded = captor.getValue();

        // Canonical Phase A defaults — locked here so a future change to
        // TenantConfig.defaultsFor surfaces in this test rather than silently
        // shifting the bootstrap row's shape.
        assertThat(seeded.tenantId()).isEqualTo("OWNER");
        assertThat(seeded.dailyLossBudget()).isEqualTo(TenantConfig.DEFAULT_DAILY_LOSS_BUDGET);
        assertThat(seeded.maxTradesPerDay()).isEqualTo(TenantConfig.DEFAULT_MAX_TRADES_PER_DAY);
        assertThat(seeded.positionSizePct()).isEqualTo(TenantConfig.DEFAULT_POSITION_SIZE_PCT);
        assertThat(seeded.sentinelConfidenceThreshold()).isEqualTo(TenantConfig.DEFAULT_SENTINEL_THRESHOLD);
        assertThat(seeded.featureFlags().phaseBMultiTenantOnboarding()).isFalse();
        assertThat(seeded.featureFlags().phaseBAlpacaOAuth()).isFalse();
        assertThat(seeded.featureFlags().phaseBBilling()).isFalse();
        assertThat(seeded.featureFlags().phaseBAiSuggestions()).isFalse();
        assertThat(seeded.createdAt()).isEqualTo(pinned);
        assertThat(seeded.updatedAt()).isEqualTo(pinned);
        assertThat(seeded.schemaVersion()).isEqualTo(TenantConfig.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void skipsInsertWhenRowAlreadyPresent() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-02T13:30:00Z"), ZoneOffset.UTC);
        // Repository's insertIfMissing returns false when the row already
        // exists — the seeder must NOT re-insert.
        when(repository.insertIfMissing(any(TenantConfig.class))).thenReturn(false);

        OwnerSeed seed = new OwnerSeed(repository, clock, "OWNER");
        seed.onApplicationEvent(event);

        // Exactly one call — and the listener does NOT then call upsert /
        // insert directly. The idempotency invariant lives in the repository
        // and the seeder trusts it.
        verify(repository, times(1)).insertIfMissing(any(TenantConfig.class));
    }

    @Test
    void usesConfiguredBootstrapTenantId() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-02T13:30:00Z"), ZoneOffset.UTC);
        when(repository.insertIfMissing(any(TenantConfig.class))).thenReturn(true);

        // Phase B will operate on multiple tenant ids but the seeder is still
        // single-tenant — bootstrap-id is per-pod config, not a list. The
        // operator overrides via levelsweep.tenant.bootstrap-id env.
        OwnerSeed seed = new OwnerSeed(repository, clock, "ALT_OWNER");
        seed.onApplicationEvent(event);

        ArgumentCaptor<TenantConfig> captor = ArgumentCaptor.forClass(TenantConfig.class);
        verify(repository).insertIfMissing(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("ALT_OWNER");
    }

    @Test
    void rejectsBlankBootstrapTenantId() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-02T13:30:00Z"), ZoneOffset.UTC);

        // Construction-time fail is the right behaviour — Spring Boot won't
        // start with a malformed bootstrap-id, surfacing the misconfig at
        // pod startup rather than at first PUT.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OwnerSeed(repository, clock, ""))
                .isInstanceOf(IllegalArgumentException.class);

        // Crucially: nothing got persisted.
        verify(repository, never()).insertIfMissing(any(TenantConfig.class));
    }
}
