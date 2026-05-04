package com.levelsweep.userconfig.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link TenantConfigRepository}. Pure Mockito over
 * {@link JdbcTemplate} — no real MS SQL, no testcontainers. Covers:
 *
 * <ul>
 *   <li>find: returns {@link Optional#empty()} on
 *       {@link EmptyResultDataAccessException}, the present row otherwise.</li>
 *   <li>upsert: 17 bind parameters, in the expected order, FlagsJson serialised.</li>
 *   <li>insertIfMissing: skips when row exists, inserts when absent.</li>
 *   <li>Blank tenantId rejected at the API boundary.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TenantConfigRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void findReturnsEmptyWhenNoRow() {
        TenantConfigRepository repo = new TenantConfigRepository(jdbc, mapper);
        when(jdbc.queryForObject(eq(TenantConfigRepository.SELECT_SQL), any(RowMapper.class), eq("OWNER")))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<TenantConfig> result = repo.find("OWNER");

        assertThat(result).isEmpty();
    }

    @Test
    void findReturnsRowWhenPresent() {
        TenantConfigRepository repo = new TenantConfigRepository(jdbc, mapper);
        TenantConfig stored = TenantConfig.defaultsFor("OWNER", Instant.parse("2026-05-02T12:00:00Z"));
        when(jdbc.queryForObject(eq(TenantConfigRepository.SELECT_SQL), any(RowMapper.class), eq("OWNER")))
                .thenReturn(stored);

        Optional<TenantConfig> result = repo.find("OWNER");

        assertThat(result).contains(stored);
    }

    @Test
    void findRejectsBlankTenant() {
        TenantConfigRepository repo = new TenantConfigRepository(jdbc, mapper);

        assertThatThrownBy(() -> repo.find(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> repo.find(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void upsertBindsAllParametersInOrder() throws Exception {
        TenantConfigRepository repo = new TenantConfigRepository(jdbc, mapper);
        Instant created = Instant.parse("2026-05-01T00:00:00Z");
        Instant updated = Instant.parse("2026-05-02T00:00:00Z");
        TenantConfig cfg = new TenantConfig(
                "OWNER",
                new BigDecimal("250.00"),
                4,
                new BigDecimal("0.0150"),
                new BigDecimal("0.90"),
                new FeatureFlags(false, false, false, false),
                created,
                updated,
                1);

        repo.upsert(cfg);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(eq(TenantConfigRepository.UPSERT_SQL), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();

        // 1 (USING) + 7 (UPDATE) + 9 (INSERT) = 17 bind parameters.
        assertThat(args).hasSize(17);
        assertThat(args[0]).isEqualTo("OWNER"); // USING source tenant_id
        // WHEN MATCHED UPDATE block:
        assertThat(args[1]).isEqualTo(new BigDecimal("250.00"));
        assertThat(args[2]).isEqualTo(4);
        assertThat(args[3]).isEqualTo(new BigDecimal("0.0150"));
        assertThat(args[4]).isEqualTo(new BigDecimal("0.90"));
        // feature_flags JSON
        String flagsJson = (String) args[5];
        FeatureFlags roundTrip = mapper.readValue(flagsJson, FeatureFlags.class);
        assertThat(roundTrip).isEqualTo(cfg.featureFlags());
        assertThat(args[6]).isEqualTo(java.sql.Timestamp.from(updated));
        assertThat(args[7]).isEqualTo(1);
        // WHEN NOT MATCHED INSERT block:
        assertThat(args[8]).isEqualTo("OWNER");
        assertThat(args[9]).isEqualTo(new BigDecimal("250.00"));
        assertThat(args[10]).isEqualTo(4);
        assertThat(args[11]).isEqualTo(new BigDecimal("0.0150"));
        assertThat(args[12]).isEqualTo(new BigDecimal("0.90"));
        assertThat(args[13]).isEqualTo(flagsJson);
        assertThat(args[14]).isEqualTo(java.sql.Timestamp.from(created));
        assertThat(args[15]).isEqualTo(java.sql.Timestamp.from(updated));
        assertThat(args[16]).isEqualTo(1);
    }

    @Test
    void insertIfMissingSkipsWhenRowExists() {
        TenantConfigRepository repo = new TenantConfigRepository(jdbc, mapper);
        TenantConfig cfg = TenantConfig.defaultsFor("OWNER", Instant.parse("2026-05-02T12:00:00Z"));
        when(jdbc.queryForObject(eq(TenantConfigRepository.SELECT_SQL), any(RowMapper.class), eq("OWNER")))
                .thenReturn(cfg);

        boolean inserted = repo.insertIfMissing(cfg);

        assertThat(inserted).isFalse();
        // Crucially: no INSERT was issued. Mockito 5.x's varargs handling
        // makes (Object[]) any() flaky in negative assertions; verify zero
        // jdbc.update interactions with the INSERT_SQL by counting captured
        // calls instead.
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, never()).update(eq(TenantConfigRepository.INSERT_SQL), argsCaptor.capture());
    }

    @Test
    void insertIfMissingInsertsWhenAbsent() {
        TenantConfigRepository repo = new TenantConfigRepository(jdbc, mapper);
        TenantConfig cfg = TenantConfig.defaultsFor("OWNER", Instant.parse("2026-05-02T12:00:00Z"));
        when(jdbc.queryForObject(eq(TenantConfigRepository.SELECT_SQL), any(RowMapper.class), eq("OWNER")))
                .thenThrow(new EmptyResultDataAccessException(1));
        // No need to stub jdbc.update — its int return is ignored by the
        // repository, and Mockito's default zero-int return is fine.

        boolean inserted = repo.insertIfMissing(cfg);

        assertThat(inserted).isTrue();
        // Capturing the varargs into a positional Object[] is the only
        // reliable way to verify a varargs JdbcTemplate.update under
        // Mockito 5.x — the matcher form (Object[]) any() is interpreted
        // as a single null-array element by the varargs spreader.
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(1)).update(eq(TenantConfigRepository.INSERT_SQL), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).hasSize(9);
    }

    @Test
    void insertIfMissingBindsCanonicalParameters() throws Exception {
        TenantConfigRepository repo = new TenantConfigRepository(jdbc, mapper);
        Instant now = Instant.parse("2026-05-02T12:00:00Z");
        TenantConfig cfg = TenantConfig.defaultsFor("OWNER", now);
        when(jdbc.queryForObject(eq(TenantConfigRepository.SELECT_SQL), any(RowMapper.class), eq("OWNER")))
                .thenThrow(new EmptyResultDataAccessException(1));

        repo.insertIfMissing(cfg);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(eq(TenantConfigRepository.INSERT_SQL), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();

        assertThat(args).hasSize(9);
        assertThat(args[0]).isEqualTo("OWNER");
        assertThat(args[1]).isEqualTo(TenantConfig.DEFAULT_DAILY_LOSS_BUDGET);
        assertThat(args[2]).isEqualTo(TenantConfig.DEFAULT_MAX_TRADES_PER_DAY);
        assertThat(args[3]).isEqualTo(TenantConfig.DEFAULT_POSITION_SIZE_PCT);
        assertThat(args[4]).isEqualTo(TenantConfig.DEFAULT_SENTINEL_THRESHOLD);
        // feature_flags JSON round-trips to defaults().
        FeatureFlags roundTrip = mapper.readValue((String) args[5], FeatureFlags.class);
        assertThat(roundTrip).isEqualTo(FeatureFlags.defaults());
        assertThat(args[6]).isEqualTo(java.sql.Timestamp.from(now));
        assertThat(args[7]).isEqualTo(java.sql.Timestamp.from(now));
        assertThat(args[8]).isEqualTo(TenantConfig.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void domainRecordRejectsInvalidNumericRanges() {
        Instant now = Instant.parse("2026-05-02T12:00:00Z");
        FeatureFlags flags = FeatureFlags.defaults();

        // dailyLossBudget must be > 0
        assertThatThrownBy(() -> new TenantConfig(
                        "OWNER",
                        BigDecimal.ZERO,
                        5,
                        new BigDecimal("0.02"),
                        new BigDecimal("0.85"),
                        flags,
                        now,
                        now,
                        1))
                .isInstanceOf(IllegalArgumentException.class);

        // positionSizePct must be in (0, 1]
        assertThatThrownBy(() -> new TenantConfig(
                        "OWNER",
                        new BigDecimal("100"),
                        5,
                        new BigDecimal("1.5"),
                        new BigDecimal("0.85"),
                        flags,
                        now,
                        now,
                        1))
                .isInstanceOf(IllegalArgumentException.class);

        // sentinelConfidenceThreshold must be in [0, 1]
        assertThatThrownBy(() -> new TenantConfig(
                        "OWNER",
                        new BigDecimal("100"),
                        5,
                        new BigDecimal("0.02"),
                        new BigDecimal("1.5"),
                        flags,
                        now,
                        now,
                        1))
                .isInstanceOf(IllegalArgumentException.class);

        // maxTradesPerDay must be > 0
        assertThatThrownBy(() -> new TenantConfig(
                        "OWNER",
                        new BigDecimal("100"),
                        0,
                        new BigDecimal("0.02"),
                        new BigDecimal("0.85"),
                        flags,
                        now,
                        now,
                        1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void featureFlagsDefaultsAreAllOff() {
        // Phase A safety: every Phase B path must default to OFF per
        // CLAUDE.md guardrail #1 (gated behind RIA / broker-dealer review).
        FeatureFlags flags = FeatureFlags.defaults();
        assertThat(flags.phaseBMultiTenantOnboarding()).isFalse();
        assertThat(flags.phaseBAlpacaOAuth()).isFalse();
        assertThat(flags.phaseBBilling()).isFalse();
        assertThat(flags.phaseBAiSuggestions()).isFalse();
    }

    @Test
    void upsertSerialisesFlagsAsJson() throws Exception {
        TenantConfigRepository repo = new TenantConfigRepository(jdbc, mapper);
        Instant now = Instant.parse("2026-05-02T12:00:00Z");
        FeatureFlags allOn = new FeatureFlags(true, true, true, true);
        TenantConfig cfg = new TenantConfig(
                "OWNER",
                new BigDecimal("100.00"),
                5,
                new BigDecimal("0.02"),
                new BigDecimal("0.85"),
                allOn,
                now,
                now,
                1);

        repo.upsert(cfg);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(eq(TenantConfigRepository.UPSERT_SQL), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        String flagsJson = (String) args[5];

        FeatureFlags roundTrip = mapper.readValue(flagsJson, FeatureFlags.class);
        assertThat(roundTrip).isEqualTo(allOn);
    }

    @SuppressWarnings("unused")
    private static int unusedAnyInt() {
        return anyInt();
    }
}
