package com.levelsweep.userconfig.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.userconfig.store.FeatureFlags;
import com.levelsweep.userconfig.store.TenantConfig;
import com.levelsweep.userconfig.store.TenantConfigRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link TenantConfigController}. Pure {@code @WebMvcTest} —
 * no MS SQL, no Flyway, no real DataSource. The controller and its bean
 * dependencies are wired with a mocked {@link TenantConfigRepository} and a
 * fixed {@link Clock} for deterministic timestamp assertions.
 *
 * <p>Active profile {@code test} pins the {@code application-test} block
 * which disables auto-configured DB health probes — required so the slice
 * doesn't fail readiness when there's no real MS SQL to ping.
 */
@WebMvcTest(TenantConfigController.class)
@Import(TenantConfigControllerTest.FixedClockConfig.class)
@ActiveProfiles("test")
class TenantConfigControllerTest {

    private static final Instant PINNED = Instant.parse("2026-05-02T13:30:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private TenantConfigRepository repository;

    @org.springframework.boot.test.context.TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(PINNED, ZoneOffset.UTC);
        }
    }

    private static TenantConfig sampleOwnerRow() {
        return new TenantConfig(
                "OWNER",
                new BigDecimal("300.00"),
                5,
                new BigDecimal("0.0200"),
                new BigDecimal("0.85"),
                new FeatureFlags(false, false, false, false),
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"),
                1);
    }

    @Test
    void getReturnsRowForTenant() throws Exception {
        when(repository.find("OWNER")).thenReturn(Optional.of(sampleOwnerRow()));

        mvc.perform(get("/config/OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value("OWNER"))
                .andExpect(jsonPath("$.daily_loss_budget").value(300.00))
                .andExpect(jsonPath("$.max_trades_per_day").value(5))
                .andExpect(jsonPath("$.position_size_pct").value(0.02))
                .andExpect(jsonPath("$.sentinel_confidence_threshold").value(0.85))
                .andExpect(jsonPath("$.feature_flags.phase-b-multi-tenant-onboarding")
                        .value(false))
                .andExpect(jsonPath("$.feature_flags.phase-b-alpaca-oauth").value(false))
                .andExpect(jsonPath("$.feature_flags.phase-b-billing").value(false))
                .andExpect(jsonPath("$.feature_flags.phase-b-ai-suggestions").value(false))
                .andExpect(jsonPath("$.schema_version").value(1));
    }

    @Test
    void getReturns404WhenAbsent() throws Exception {
        when(repository.find("OWNER")).thenReturn(Optional.empty());

        mvc.perform(get("/config/OWNER")).andExpect(status().isNotFound());
    }

    @Test
    void getFeatureFlagsReturnsOnlyFlags() throws Exception {
        when(repository.find("OWNER")).thenReturn(Optional.of(sampleOwnerRow()));

        mvc.perform(get("/config/OWNER/feature-flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase-b-multi-tenant-onboarding").value(false))
                .andExpect(jsonPath("$.phase-b-alpaca-oauth").value(false))
                .andExpect(jsonPath("$.phase-b-billing").value(false))
                .andExpect(jsonPath("$.phase-b-ai-suggestions").value(false))
                // The flags-only endpoint must NOT echo back the heavy fields
                // — Decision Engine only needs the flag map. Asserting absence
                // pins the contract for hot-path consumers.
                .andExpect(jsonPath("$.daily_loss_budget").doesNotExist())
                .andExpect(jsonPath("$.position_size_pct").doesNotExist());
    }

    @Test
    void getFeatureFlagsReturns404WhenAbsent() throws Exception {
        when(repository.find("OWNER")).thenReturn(Optional.empty());

        mvc.perform(get("/config/OWNER/feature-flags")).andExpect(status().isNotFound());
    }

    @Test
    void putUpsertsAndStampsUpdatedAtFromClock() throws Exception {
        // Existing row supplies the immutable created_at; the PUT body's
        // created_at is ignored.
        TenantConfig existing = sampleOwnerRow();
        when(repository.find("OWNER")).thenReturn(Optional.of(existing));

        TenantConfigDto body = new TenantConfigDto(
                "WRONG-TENANT-IGNORED", // path tenant wins
                new BigDecimal("250.00"),
                4,
                new BigDecimal("0.0150"),
                new BigDecimal("0.90"),
                new FeatureFlagsDto(true, false, false, false),
                Instant.parse("2030-01-01T00:00:00Z"), // ignored
                Instant.parse("2030-01-01T00:00:00Z"), // ignored
                1);

        mvc.perform(put("/config/OWNER").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value("OWNER"))
                .andExpect(jsonPath("$.daily_loss_budget").value(250.00))
                .andExpect(jsonPath("$.max_trades_per_day").value(4))
                .andExpect(jsonPath("$.position_size_pct").value(0.015))
                .andExpect(jsonPath("$.sentinel_confidence_threshold").value(0.90))
                .andExpect(jsonPath("$.feature_flags.phase-b-multi-tenant-onboarding")
                        .value(true))
                // updated_at must be the fixed-clock instant; created_at preserved.
                .andExpect(jsonPath("$.updated_at").value(PINNED.toString()))
                .andExpect(jsonPath("$.created_at").value(existing.createdAt().toString()));

        ArgumentCaptor<TenantConfig> captor = ArgumentCaptor.forClass(TenantConfig.class);
        verify(repository).upsert(captor.capture());
        TenantConfig persisted = captor.getValue();
        // Path tenantId is authoritative — body's "WRONG-TENANT-IGNORED" must
        // not reach the repository.
        assert persisted.tenantId().equals("OWNER");
        assert persisted.createdAt().equals(existing.createdAt());
        assert persisted.updatedAt().equals(PINNED);
    }

    @Test
    void putSetsCreatedAtToNowWhenRowAbsent() throws Exception {
        when(repository.find("OWNER")).thenReturn(Optional.empty());

        TenantConfigDto body = new TenantConfigDto(
                "OWNER",
                new BigDecimal("100.00"),
                3,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new FeatureFlagsDto(false, false, false, false),
                null,
                null,
                1);

        mvc.perform(put("/config/OWNER").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created_at").value(PINNED.toString()))
                .andExpect(jsonPath("$.updated_at").value(PINNED.toString()));

        ArgumentCaptor<TenantConfig> captor = ArgumentCaptor.forClass(TenantConfig.class);
        verify(repository).upsert(captor.capture());
        TenantConfig persisted = captor.getValue();
        assert persisted.createdAt().equals(PINNED);
        assert persisted.updatedAt().equals(PINNED);
    }

    @Test
    void putRejectsOutOfRangePositionSizePct() throws Exception {
        // Bean Validation @DecimalMax on the DTO catches this BEFORE the
        // domain record's IllegalArgumentException — the controller returns
        // 400 from the framework's MethodArgumentNotValidException handling.
        TenantConfigDto body = new TenantConfigDto(
                "OWNER",
                new BigDecimal("100.00"),
                5,
                new BigDecimal("1.5"), // > 1.0 — invalid
                new BigDecimal("0.85"),
                new FeatureFlagsDto(false, false, false, false),
                null,
                null,
                1);

        mvc.perform(put("/config/OWNER").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void putRejectsZeroDailyLossBudget() throws Exception {
        TenantConfigDto body = new TenantConfigDto(
                "OWNER",
                new BigDecimal("0.00"), // < 0.01 minimum
                5,
                new BigDecimal("0.02"),
                new BigDecimal("0.85"),
                new FeatureFlagsDto(false, false, false, false),
                null,
                null,
                1);

        mvc.perform(put("/config/OWNER").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void putRejectsZeroMaxTradesPerDay() throws Exception {
        TenantConfigDto body = new TenantConfigDto(
                "OWNER",
                new BigDecimal("100.00"),
                0, // @Min(1) violation
                new BigDecimal("0.02"),
                new BigDecimal("0.85"),
                new FeatureFlagsDto(false, false, false, false),
                null,
                null,
                1);

        mvc.perform(put("/config/OWNER").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void featureFlagsDtoRoundTripsThroughJson() throws Exception {
        // Jackson must use the kebab-case @JsonProperty names — Decision
        // Engine's flag-fetcher relies on that wire shape.
        FeatureFlagsDto dto = new FeatureFlagsDto(true, false, true, false);
        String json = mapper.writeValueAsString(dto);

        // Asserting via json text means a future Jackson default change
        // (e.g. propertyNamingStrategy SNAKE_CASE) doesn't silently break
        // the wire shape.
        assert json.contains("\"phase-b-multi-tenant-onboarding\":true");
        assert json.contains("\"phase-b-alpaca-oauth\":false");
        assert json.contains("\"phase-b-billing\":true");
        assert json.contains("\"phase-b-ai-suggestions\":false");

        FeatureFlagsDto parsed = mapper.readValue(json, FeatureFlagsDto.class);
        assert parsed.equals(dto);
    }

    @SuppressWarnings("unused")
    private static void unusedSilencingHelper() {
        eq("OWNER"); // keep the static import in case future tests need it
    }
}
