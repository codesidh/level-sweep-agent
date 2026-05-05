package com.levelsweep.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

class ProjectionRouteControllerTest {

    @Test
    void getLastProxiesToDownstream() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("{\"projection\":\"data\"}");

        ProjectionRouteController c = new ProjectionRouteController(client);
        ResponseEntity<?> r = c.last("OWNER");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("{\"projection\":\"data\"}");
        verify(uriSpec).uri("/projection/last/OWNER");
        verify(headerSpec).header("X-Tenant-Id", "OWNER");
    }

    @Test
    void postRunProxiesBodyToDownstream() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("{\"status\":\"queued\"}");

        ProjectionRouteController c = new ProjectionRouteController(client);
        String requestJson = "{\"tenantId\":\"OWNER\",\"simulations\":1000}";
        ResponseEntity<?> r = c.run("OWNER", requestJson);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("{\"status\":\"queued\"}");
        verify(uriSpec).uri("/projection/run");
        verify(bodySpec).header("X-Tenant-Id", "OWNER");
        verify(bodySpec).contentType(MediaType.APPLICATION_JSON);
        verify(bodySpec).body(requestJson);
    }
}
