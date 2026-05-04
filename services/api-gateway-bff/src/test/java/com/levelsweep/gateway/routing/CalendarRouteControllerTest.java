package com.levelsweep.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.gateway.auth.BypassAuthFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CalendarRouteControllerTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void todayProxiesAndForwardsTenantFromRequestAttribute() {
        // Stub a current request that carries the tenantId attribute the
        // BypassAuthFilter would have set in production.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/calendar/today");
        req.setAttribute(BypassAuthFilter.MDC_TENANT_KEY, "OWNER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("{\"date\":\"2026-05-04\",\"isOpen\":true}");

        CalendarRouteController c = new CalendarRouteController(client);
        ResponseEntity<?> r = c.today();

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(uriSpec).uri("/calendar/today");
        verify(headerSpec).header("X-Tenant-Id", "OWNER");
    }

    @Test
    void todayFallsBackToOwnerWhenAttributeMissing() {
        // No request context — Calendar service is tenant-agnostic so the
        // controller defaults to OWNER for log correlation.
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn("{}");

        CalendarRouteController c = new CalendarRouteController(client);
        c.today();

        verify(headerSpec).header("X-Tenant-Id", "OWNER");
    }
}
