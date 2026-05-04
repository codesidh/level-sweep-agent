package com.levelsweep.projection.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.projection.cache.ProjectionRunDocument;
import com.levelsweep.projection.cache.ProjectionRunRepository;
import com.levelsweep.projection.domain.ProjectionRequest;
import com.levelsweep.projection.domain.ProjectionResult;
import com.levelsweep.projection.engine.MonteCarloEngine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
 * Slice test for {@link ProjectionController}. Pure {@code @WebMvcTest} — no
 * Mongo, no real engine bean wiring beyond the mock dependencies. Verifies:
 *
 * <ul>
 *   <li>POST /projection/run wires the engine, stamps the request hash, and
 *       persists the document.</li>
 *   <li>POST /projection/run is deterministic — same input ⇒ same seed ⇒ same
 *       result (asserted via the seed argument captured by the engine mock).</li>
 *   <li>POST /projection/run rejects out-of-range inputs with 400 from Bean
 *       Validation.</li>
 *   <li>GET /projection/last/{tenantId} returns 200 + body when present, 404
 *       when absent.</li>
 * </ul>
 *
 * <p>Active profile {@code test} pins the {@code application-test} block which
 * disables the auto-configured Mongo health probe — required so the slice
 * doesn't fail readiness when there's no real Mongo to ping.
 */
@WebMvcTest(ProjectionController.class)
@Import({ProjectionControllerTest.FixedClockConfig.class, ProjectionRequestHasher.class})
@ActiveProfiles("test")
class ProjectionControllerTest {

    private static final Instant PINNED = Instant.parse("2026-05-02T13:30:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private MonteCarloEngine engine;

    @MockBean
    private ProjectionRunRepository repository;

    @org.springframework.boot.test.context.TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(PINNED, ZoneOffset.UTC);
        }
    }

    private static ProjectionRequest validRequest() {
        return new ProjectionRequest("OWNER", 10_000.0, 55.0, 50.0, 5, 12, 2.0, 1_000, null);
    }

    private static ProjectionResult engineOutput() {
        // Engine returns "" for requestHash — controller stamps the canonical
        // value before responding/persisting.
        return new ProjectionResult(8000, 9000, 10000, 11000, 12000, 10100, 0.04, 1_000, "");
    }

    @Test
    void postRunComputesAndPersistsResult() throws Exception {
        when(engine.run(any(ProjectionRequest.class), anyLong())).thenReturn(engineOutput());

        mvc.perform(post("/projection/run")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.p10").value(8000.0))
                .andExpect(jsonPath("$.p25").value(9000.0))
                .andExpect(jsonPath("$.p50").value(10000.0))
                .andExpect(jsonPath("$.p75").value(11000.0))
                .andExpect(jsonPath("$.p90").value(12000.0))
                .andExpect(jsonPath("$.mean").value(10100.0))
                .andExpect(jsonPath("$.ruinProbability").value(0.04))
                .andExpect(jsonPath("$.simulationsRun").value(1000))
                .andExpect(jsonPath("$.requestHash").isString())
                .andExpect(jsonPath("$.requestHash").isNotEmpty());

        // Verify the run document was persisted with the controller-stamped hash.
        ArgumentCaptor<ProjectionRunDocument> captor = ArgumentCaptor.forClass(ProjectionRunDocument.class);
        verify(repository).save(captor.capture());
        ProjectionRunDocument persisted = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(persisted.tenantId()).isEqualTo("OWNER");
        org.assertj.core.api.Assertions.assertThat(persisted.requestHash()).hasSize(64);
        org.assertj.core.api.Assertions.assertThat(persisted.computedAt()).isEqualTo(PINNED);
    }

    @Test
    void postRunUsesSameDerivedSeedForIdenticalRequests() throws Exception {
        when(engine.run(any(ProjectionRequest.class), anyLong())).thenReturn(engineOutput());

        // Two POSTs with identical body must call engine.run with the same seed.
        ArgumentCaptor<Long> seedCaptor = ArgumentCaptor.forClass(Long.class);

        mvc.perform(post("/projection/run")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());
        mvc.perform(post("/projection/run")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());

        verify(engine, org.mockito.Mockito.times(2)).run(any(ProjectionRequest.class), seedCaptor.capture());

        org.assertj.core.api.Assertions.assertThat(seedCaptor.getAllValues())
                .hasSize(2)
                .satisfies(seeds ->
                        org.assertj.core.api.Assertions.assertThat(seeds.get(0)).isEqualTo(seeds.get(1)));
    }

    @Test
    void postRunUsesExplicitSeedWhenProvided() throws Exception {
        when(engine.run(any(ProjectionRequest.class), anyLong())).thenReturn(engineOutput());

        ProjectionRequest withSeed = new ProjectionRequest("OWNER", 10_000.0, 55.0, 50.0, 5, 12, 2.0, 1_000, 42L);

        mvc.perform(post("/projection/run")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(withSeed)))
                .andExpect(status().isOk());

        ArgumentCaptor<Long> seedCaptor = ArgumentCaptor.forClass(Long.class);
        verify(engine).run(any(ProjectionRequest.class), seedCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(seedCaptor.getValue()).isEqualTo(42L);
    }

    @Test
    void postRunRejectsBlankTenantId() throws Exception {
        // @NotBlank on the DTO catches this before the engine is hit.
        Map<String, Object> body = Map.of(
                "tenantId",
                "",
                "startingEquity",
                10_000.0,
                "winRatePct",
                55.0,
                "lossPct",
                50.0,
                "sessionsPerWeek",
                5,
                "horizonWeeks",
                12,
                "positionSizePct",
                2.0,
                "simulations",
                1_000);

        mvc.perform(post("/projection/run").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verify(engine, never()).run(any(), anyLong());
        verify(repository, never()).save(any());
    }

    @Test
    void postRunRejectsOversizedSimulations() throws Exception {
        // @Max(100_000) on the DTO catches this.
        ProjectionRequest oversized = new ProjectionRequest("OWNER", 10_000.0, 55.0, 50.0, 5, 12, 2.0, 200_000, null);

        mvc.perform(post("/projection/run")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(oversized)))
                .andExpect(status().isBadRequest());

        verify(engine, never()).run(any(), anyLong());
    }

    @Test
    void postRunRejectsOutOfRangeWinRate() throws Exception {
        ProjectionRequest outOfRange = new ProjectionRequest("OWNER", 10_000.0, 150.0, 50.0, 5, 12, 2.0, 1_000, null);

        mvc.perform(post("/projection/run")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(outOfRange)))
                .andExpect(status().isBadRequest());

        verify(engine, never()).run(any(), anyLong());
    }

    @Test
    void postRunRejectsZeroSimulations() throws Exception {
        // @Min(100) on the DTO catches this.
        ProjectionRequest tooSmall = new ProjectionRequest("OWNER", 10_000.0, 55.0, 50.0, 5, 12, 2.0, 0, null);

        mvc.perform(post("/projection/run")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(tooSmall)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getLastReturnsCachedDocument() throws Exception {
        ProjectionRequest req = validRequest();
        ProjectionResult res = new ProjectionResult(8000, 9000, 10000, 11000, 12000, 10100, 0.04, 1_000, "abc123");
        ProjectionRunDocument doc = new ProjectionRunDocument("OWNER", "abc123", req, res, PINNED);

        when(repository.findLatest("OWNER")).thenReturn(Optional.of(doc));

        mvc.perform(get("/projection/last/OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("OWNER"))
                .andExpect(jsonPath("$.requestHash").value("abc123"))
                .andExpect(jsonPath("$.result.p50").value(10000.0))
                .andExpect(jsonPath("$.request.tenantId").value("OWNER"));
    }

    @Test
    void getLastReturns404WhenAbsent() throws Exception {
        when(repository.findLatest("OWNER")).thenReturn(Optional.empty());

        mvc.perform(get("/projection/last/OWNER")).andExpect(status().isNotFound());
    }
}
