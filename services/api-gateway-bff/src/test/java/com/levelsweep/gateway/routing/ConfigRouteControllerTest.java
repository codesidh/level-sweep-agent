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

class ConfigRouteControllerTest {

    @Test
    void proxiesGetAndForwardsTenantHeader() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("{\"tenant_id\":\"OWNER\"}");

        ConfigRouteController c = new ConfigRouteController(client);
        ResponseEntity<?> r = c.config("OWNER");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("{\"tenant_id\":\"OWNER\"}");
        verify(uriSpec).uri("/config/OWNER");
        verify(headerSpec).header("X-Tenant-Id", "OWNER");
    }

    @Test
    void surfacesDownstream5xxStatus() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        RestClientResponseException ex = new RestClientResponseException(
                "boom",
                HttpStatusCode.valueOf(503),
                "SU",
                new HttpHeaders(),
                "{\"error\":\"db\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(respSpec.body(String.class)).thenThrow(ex);

        ConfigRouteController c = new ConfigRouteController(client);
        ResponseEntity<?> r = c.config("OWNER");

        assertThat(r.getStatusCode().value()).isEqualTo(503);
    }
}
