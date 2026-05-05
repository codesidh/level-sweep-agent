package com.levelsweep.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Slice tests for {@link JournalRouteController}. Mocks the {@link RestClient}
 * fluent chain — no real HTTP traffic. RestClient's builder-style API forces
 * us to stub each chain link separately (uri → header → retrieve → body).
 */
class JournalRouteControllerTest {

    @Test
    void proxiesGetWithQueryParamsAndForwardsTenantHeader() {
        // Stub the full RestClient chain.
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        // doReturn-typed casting because of the wildcard generics on the
        // RestClient fluent chain.
        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("[{\"event\":\"trade\"}]");

        JournalRouteController c = new JournalRouteController(client);
        ResponseEntity<?> r = c.journal("OWNER", "2026-05-01", "2026-05-02", "trade");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("[{\"event\":\"trade\"}]");
        verify(uriSpec).uri("/journal/OWNER?from=2026-05-01&to=2026-05-02&type=trade");
        verify(headerSpec).header("X-Tenant-Id", "OWNER");
    }

    @Test
    void omitsBlankQueryParams() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("[]");

        JournalRouteController c = new JournalRouteController(client);
        c.journal("OWNER", null, "", "  ");

        // Only the bare path; no query params.
        verify(uriSpec).uri("/journal/OWNER");
    }

    @Test
    void surfacesDownstream4xxAsSameStatus() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        // Simulate a 404 by throwing the error RestClient would surface.
        RestClientResponseException ex = new RestClientResponseException(
                "not found",
                HttpStatusCode.valueOf(404),
                "Not Found",
                new HttpHeaders(),
                "{\"error\":\"missing\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(respSpec.body(String.class)).thenThrow(ex);

        JournalRouteController c = new JournalRouteController(client);
        ResponseEntity<?> r = c.journal("OWNER", null, null, null);

        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).isEqualTo("{\"error\":\"missing\"}");
    }
}
