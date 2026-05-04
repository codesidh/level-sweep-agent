package com.levelsweep.gateway.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Per-downstream {@link RestClient} bean wiring.
 *
 * <p>Each downstream service gets its own RestClient bean, named after the
 * service, so the routing controllers can be wired explicitly via
 * {@code @Qualifier} or constructor-name resolution. Connect / read timeouts
 * are tight by design (architecture-spec §16.4 calls for sub-second BFF
 * latency on the dashboard summary aggregation):
 *
 * <ul>
 *   <li>Connect: 2 seconds — anything longer means the downstream pod is
 *       not in Service rotation; bail and let the aggregator's degraded
 *       path return a partial response.</li>
 *   <li>Read: 5 seconds — the slowest legitimate downstream is the
 *       projection-service (lazy table read), well under that.</li>
 * </ul>
 *
 * <p>Phase 7 swaps in resilience4j circuit-breakers + retries + bulkheads
 * around each client. Phase 6 keeps it simple: a hung downstream times out,
 * the aggregator marks {@code degraded: true} and returns a 200 with a
 * partial body. (CLAUDE.md guardrail #3 — fail-closed on the order path —
 * does NOT apply here; the BFF is read-only and serves the dashboard, not
 * the trade saga.)
 */
@Configuration
public class RestClientConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RestClientConfig.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean(name = "journalRestClient")
    public RestClient journalRestClient(@Value("${levelsweep.downstream.journal-url}") String baseUrl) {
        LOG.info("journalRestClient → {}", baseUrl);
        return build(baseUrl);
    }

    @Bean(name = "userConfigRestClient")
    public RestClient userConfigRestClient(@Value("${levelsweep.downstream.user-config-url}") String baseUrl) {
        LOG.info("userConfigRestClient → {}", baseUrl);
        return build(baseUrl);
    }

    @Bean(name = "projectionRestClient")
    public RestClient projectionRestClient(@Value("${levelsweep.downstream.projection-url}") String baseUrl) {
        LOG.info("projectionRestClient → {}", baseUrl);
        return build(baseUrl);
    }

    @Bean(name = "calendarRestClient")
    public RestClient calendarRestClient(@Value("${levelsweep.downstream.calendar-url}") String baseUrl) {
        LOG.info("calendarRestClient → {}", baseUrl);
        return build(baseUrl);
    }

    @Bean(name = "aiAgentRestClient")
    public RestClient aiAgentRestClient(@Value("${levelsweep.downstream.ai-agent-url}") String baseUrl) {
        LOG.info("aiAgentRestClient → {}", baseUrl);
        return build(baseUrl);
    }

    private RestClient build(String baseUrl) {
        // Spring Boot 3.3.x exposes ClientHttpRequestFactorySettings.DEFAULTS
        // (a record constant) rather than a defaults() method. The Jdk-backed
        // factory honours both timeouts.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
