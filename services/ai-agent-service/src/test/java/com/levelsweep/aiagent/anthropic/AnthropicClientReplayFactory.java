package com.levelsweep.aiagent.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.aiagent.connection.AnthropicConnectionMonitor;
import com.levelsweep.aiagent.cost.DailyCostTracker;
import com.levelsweep.aiagent.observability.AiAgentMetrics;
import java.time.Clock;
import java.util.Optional;

/**
 * Test-only bridge into {@link AnthropicClient}'s package-private {@code Fetcher}
 * constructor. Lives in the {@code com.levelsweep.aiagent.anthropic} package so
 * the replay harness in {@code com.levelsweep.aiagent.sentinel.replay} can
 * instantiate a fixture-backed client without a live HTTP socket.
 *
 * <p>Per ADR-0007 §5: the replay runner injects a fixture-backed
 * {@link AnthropicClient.Fetcher} that returns the recorded HTTP response (or
 * synthesizes a transport-failure / circuit-breaker-open exception) for the
 * pre-hashed request key. No HTTP call leaves the JVM during a replay run.
 */
public final class AnthropicClientReplayFactory {

    private AnthropicClientReplayFactory() {
        // utility
    }

    /** Build an {@link AnthropicClient} wired to a fixture-backed {@link AnthropicClient.Fetcher}. */
    public static AnthropicClient withFetcher(
            AnthropicClient.Fetcher fetcher,
            DailyCostTracker costTracker,
            AnthropicConnectionMonitor connectionMonitor,
            AiAgentMetrics metrics,
            Clock clock) {
        return new AnthropicClient(
                "https://replay.invalid",
                Optional.of("REPLAY_KEY"),
                /* promptCachingEnabled */ true,
                clock,
                new ObjectMapper(),
                fetcher,
                costTracker,
                connectionMonitor,
                metrics);
    }
}
