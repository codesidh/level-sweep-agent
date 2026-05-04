package com.levelsweep.journal.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.levelsweep.journal.audit.AuditRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link JournalQueryController}. Pure {@code @WebMvcTest} —
 * no Mongo, no Kafka, no real datasource. The controller and its bean
 * dependencies are wired with a mocked {@link AuditRepository}.
 *
 * <p>Active profile {@code test} pins the {@code application-test} block
 * which disables auto-configured Mongo health probes — required so the
 * slice doesn't fail readiness when there's no real Mongo to ping.
 */
@WebMvcTest(JournalQueryController.class)
@ActiveProfiles("test")
class JournalQueryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AuditRepository repository;

    @Test
    void returnsPaginatedRowsForTenant() throws Exception {
        Document row = new Document()
                .append("tenant_id", "OWNER")
                .append("event_type", "FILL")
                .append("source_service", "execution-service");
        when(repository.find(eq("OWNER"), any(), any(), any(), eq(0), eq(50))).thenReturn(List.of(row));
        when(repository.count(eq("OWNER"), any(), any(), any())).thenReturn(1L);

        mvc.perform(get("/journal/OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("OWNER"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].tenant_id").value("OWNER"))
                .andExpect(jsonPath("$.rows[0].event_type").value("FILL"));
    }

    @Test
    void filtersPropagateToRepository() throws Exception {
        when(repository.find(
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(repository.count(any(), any(), any(), any())).thenReturn(0L);

        mvc.perform(get("/journal/OWNER")
                        .param("type", "FILL")
                        .param("from", "2026-05-01T00:00:00Z")
                        .param("to", "2026-05-02T00:00:00Z")
                        .param("page", "2")
                        .param("size", "25"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<String>> typeCaptor = ArgumentCaptor.forClass(Optional.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<Instant>> fromCaptor = ArgumentCaptor.forClass(Optional.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<Instant>> toCaptor = ArgumentCaptor.forClass(Optional.class);
        verify(repository)
                .find(eq("OWNER"), typeCaptor.capture(), fromCaptor.capture(), toCaptor.capture(), eq(2), eq(25));

        org.assertj.core.api.Assertions.assertThat(typeCaptor.getValue()).contains("FILL");
        org.assertj.core.api.Assertions.assertThat(fromCaptor.getValue())
                .contains(Instant.parse("2026-05-01T00:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(toCaptor.getValue()).contains(Instant.parse("2026-05-02T00:00:00Z"));
    }

    @Test
    void rejectsBadInstantFormat() throws Exception {
        mvc.perform(get("/journal/OWNER").param("from", "not-an-instant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ISO-8601")));
    }

    @Test
    void clampsOversizedPageRequest() throws Exception {
        when(repository.find(
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(repository.count(any(), any(), any(), any())).thenReturn(0L);

        mvc.perform(get("/journal/OWNER").param("size", "100000").param("page", "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(500))
                .andExpect(jsonPath("$.page").value(0));
    }
}
