package com.levelsweep.marketdata.health;

import com.levelsweep.marketdata.live.LivePipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe wired to {@link LivePipeline}.
 *
 * <p>K8s restarts the pod on liveness failure, so this check is intentionally
 * conservative: a process where the CDI bean exists and exposes its ring buffer
 * is alive in the K8s sense. If the JVM is wedged, the probe fails by HTTP
 * timeout, which K8s already handles. {@link LivePipeline} does not expose its
 * drainer thread directly, so we don't try to introspect it here — and in any
 * case the cheapest reliable signal (the bean is wired and the buffer is
 * non-null) is what we want.
 *
 * <p>Endpoint binding: registered as a SmallRye {@link Liveness} health check;
 * Quarkus exposes it at {@code /q/health/live} automatically (see
 * architecture-spec §17.4 / §21.1).
 *
 * <p>See <a
 * href="https://download.eclipse.org/microprofile/microprofile-health-3.0/microprofile-health-spec-3.0.html">
 * MicroProfile Health 3.0</a>.
 */
@Liveness
@ApplicationScoped
public class MarketDataLivenessCheck implements HealthCheck {

    private static final String NAME = "market-data-pipeline";

    @Inject
    LivePipeline pipeline;

    /** CDI no-arg constructor. */
    public MarketDataLivenessCheck() {
        // Intentionally empty — fields populated via CDI field injection.
    }

    /** Test seam — lets unit tests construct the check with a hand-built pipeline. */
    MarketDataLivenessCheck(LivePipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder b = HealthCheckResponse.named(NAME);
        if (pipeline == null || pipeline.tickRingBuffer() == null) {
            // Defensive: in a properly wired Quarkus runtime this branch is unreachable
            // because @ApplicationScoped beans are always non-null at injection time.
            return b.down().withData("reason", "pipeline-not-wired").build();
        }
        return b.up().withData("ringBufferSize", pipeline.tickRingBuffer().size())
                .withData("offeredCount", pipeline.tickRingBuffer().offeredCount())
                .build();
    }
}
