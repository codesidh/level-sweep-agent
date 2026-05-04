package com.levelsweep.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Slice tests for {@link AssistantRouteController}. Mocks the
 * {@link RestClient} fluent chain — no real HTTP traffic. Mirrors
 * {@code JournalRouteControllerTest}'s pattern.
 */
class AssistantRouteControllerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void chatPostsBodyAndForwardsTenantHeader() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("{\"conversationId\":\"x\"}");

        AssistantRouteController c = new AssistantRouteController(client);
        ResponseEntity<?> r = c.chat("{\"tenantId\":\"OWNER\",\"userMessage\":\"hi\"}", "OWNER");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("{\"conversationId\":\"x\"}");
        verify(uriSpec).uri("/api/v1/assistant/chat");
        verify(bodySpec).header("X-Tenant-Id", "OWNER");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void chatFallsBackToOwnerTenantWhenQueryParamMissing() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("{}");

        AssistantRouteController c = new AssistantRouteController(client);
        c.chat("{}", null);

        verify(bodySpec).header("X-Tenant-Id", "OWNER");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listConversationsForwardsTenantAndLimitQueryParams() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("[]");

        AssistantRouteController c = new AssistantRouteController(client);
        ResponseEntity<?> r = c.listConversations("OWNER", 10);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(uriSpec).uri("/api/v1/assistant/conversations?tenantId=OWNER&limit=10");
        verify(headerSpec).header("X-Tenant-Id", "OWNER");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listConversationsOmitsLimitWhenAbsent() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("[]");

        AssistantRouteController c = new AssistantRouteController(client);
        c.listConversations("OWNER", null);

        verify(uriSpec).uri("/api/v1/assistant/conversations?tenantId=OWNER");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void getConversationProxiesWithTenantParam() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("{\"conversationId\":\"abc\"}");

        AssistantRouteController c = new AssistantRouteController(client);
        ResponseEntity<?> r = c.getConversation("abc", "OWNER");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(uriSpec).uri("/api/v1/assistant/conversations/abc?tenantId=OWNER");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void surfacesDownstream4xxAsSameStatus() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        RestClientResponseException ex = new RestClientResponseException(
                "not found",
                HttpStatusCode.valueOf(404),
                "Not Found",
                new HttpHeaders(),
                "{\"error\":\"missing\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(respSpec.body(String.class)).thenThrow(ex);

        AssistantRouteController c = new AssistantRouteController(client);
        ResponseEntity<?> r = c.getConversation("missing", "OWNER");

        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).isEqualTo("{\"error\":\"missing\"}");
    }
}
