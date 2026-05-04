package com.levelsweep.gateway.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link DashboardAggregator}. Each downstream is mocked at
 * the RestClient level (uri → header → retrieve → body); we exercise the
 * happy path AND the partial-degraded path where one of the four downstreams
 * throws.
 *
 * <p>Per CLAUDE.md guardrail #3, fail-closed applies to the order path —
 * NOT here. The BFF is read-only and a partial dashboard is more useful
 * than a 500. The aggregator MUST always return a 200 with whatever it
 * could collect.
 */
class DashboardAggregatorTest {

    private record StubBundle(RestClient client) {}

    private static StubBundle stubOk(String body) {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenReturn(body);
        return new StubBundle(client);
    }

    private static StubBundle stubFailure(RuntimeException ex) {
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headerSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);

        when(client.get()).thenAnswer(inv -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.header(anyString(), anyString())).thenAnswer(inv -> headerSpec);
        when(headerSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.body(String.class)).thenThrow(ex);
        return new StubBundle(client);
    }

    @Test
    void happyPathComposesAllFourSectionsAndDegradedFalse() {
        DashboardAggregator agg = new DashboardAggregator(
                stubOk("[{\"event\":\"trade1\"}]").client,
                stubOk("{\"tenant_id\":\"OWNER\"}").client,
                stubOk("{\"projection\":\"data\"}").client,
                stubOk("{\"date\":\"2026-05-04\"}").client);

        Map<String, Object> out = agg.compose("OWNER");

        assertThat(out).containsEntry("tenant_id", "OWNER");
        assertThat(out).containsEntry("config", "{\"tenant_id\":\"OWNER\"}");
        assertThat(out).containsEntry("journal", "[{\"event\":\"trade1\"}]");
        assertThat(out).containsEntry("projection", "{\"projection\":\"data\"}");
        assertThat(out).containsEntry("calendar", "{\"date\":\"2026-05-04\"}");
        assertThat(out).containsEntry("degraded", false);
    }

    @Test
    void degradedTrueWhenOneDownstreamThrows() {
        // user-config-service is the failing one; the other three return OK.
        DashboardAggregator agg = new DashboardAggregator(
                stubOk("[{\"event\":\"trade1\"}]").client,
                stubFailure(new ResourceAccessException("connection refused")).client,
                stubOk("{\"projection\":\"data\"}").client,
                stubOk("{\"date\":\"2026-05-04\"}").client);

        Map<String, Object> out = agg.compose("OWNER");

        assertThat(out).containsEntry("degraded", true);
        // Failing section carries an error map (NOT the original RuntimeException).
        assertThat(out.get("config")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> configErr = (Map<String, Object>) out.get("config");
        assertThat(configErr.get("error").toString()).contains("config failed");
        // The OK sections still carry their bodies — partial response is the contract.
        assertThat(out).containsEntry("journal", "[{\"event\":\"trade1\"}]");
        assertThat(out).containsEntry("projection", "{\"projection\":\"data\"}");
        assertThat(out).containsEntry("calendar", "{\"date\":\"2026-05-04\"}");
    }

    @Test
    void degradedTrueWhenAllDownstreamsThrow() {
        DashboardAggregator agg = new DashboardAggregator(
                stubFailure(new ResourceAccessException("nope")).client,
                stubFailure(new ResourceAccessException("nope")).client,
                stubFailure(new ResourceAccessException("nope")).client,
                stubFailure(new ResourceAccessException("nope")).client);

        Map<String, Object> out = agg.compose("OWNER");
        assertThat(out).containsEntry("degraded", true);
        assertThat(out.get("config")).isInstanceOf(Map.class);
        assertThat(out.get("journal")).isInstanceOf(Map.class);
        assertThat(out.get("projection")).isInstanceOf(Map.class);
        assertThat(out.get("calendar")).isInstanceOf(Map.class);
    }

    @Test
    void rejectsBlankTenantId() {
        DashboardAggregator agg =
                new DashboardAggregator(stubOk("").client, stubOk("").client, stubOk("").client, stubOk("").client);

        assertThatThrownBy(() -> agg.compose(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> agg.compose(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
