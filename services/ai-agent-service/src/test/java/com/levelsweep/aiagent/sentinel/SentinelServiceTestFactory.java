package com.levelsweep.aiagent.sentinel;

import com.levelsweep.aiagent.anthropic.AnthropicClient;
import com.levelsweep.aiagent.audit.AiCallAuditWriter;
import com.levelsweep.aiagent.connection.AnthropicConnectionMonitor;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Test-only bridge into {@link SentinelService}'s package-private constructor.
 * Lives in the same package so the replay harness in
 * {@code com.levelsweep.aiagent.sentinel.replay} can pin the executor + http
 * timeout for deterministic timing without exposing those parameters on the
 * production CDI seam.
 */
public final class SentinelServiceTestFactory {

    private SentinelServiceTestFactory() {
        // utility
    }

    public static SentinelService create(
            SentinelPromptBuilder promptBuilder,
            SentinelResponseParser responseParser,
            AnthropicClient anthropicClient,
            AnthropicConnectionMonitor connectionMonitor,
            AiCallAuditWriter auditWriter,
            AiAgentMetrics metrics,
            Clock clock,
            boolean enabled,
            Executor executor,
            Duration httpTimeout) {
        return new SentinelService(
                promptBuilder,
                responseParser,
                anthropicClient,
                connectionMonitor,
                auditWriter,
                metrics,
                clock,
                enabled,
                executor,
                httpTimeout);
    }
}
