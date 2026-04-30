package com.levelsweep.marketdata;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Market Data Service entry point.
 *
 * <p>Phase 1 — wires the live Alpaca WebSocket adapter, ring-buffer drainer, bar
 * aggregator, and indicator engine via {@link com.levelsweep.marketdata.live.LivePipeline}
 * (CDI-managed; observes {@code StartupEvent} / {@code ShutdownEvent}).
 *
 * <p>This class only handles the JVM-level entry point + a startup banner. The actual
 * wiring lives in {@code LivePipeline} so the lifecycle is driven by Quarkus rather
 * than by {@link Quarkus#waitForExit()}.
 */
@QuarkusMain
public class Application implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    @Inject
    AlpacaConfig alpacaConfig;

    public static void main(String[] args) {
        Quarkus.run(Application.class, args);
    }

    @Override
    public int run(String... args) {
        boolean credentialed = !alpacaConfig.apiKey().isBlank();
        LOG.info(
                "market-data-service starting symbols={} ringBufferCapacity={} feed={} credentialed={}",
                alpacaConfig.symbols(),
                alpacaConfig.ringBufferCapacity(),
                alpacaConfig.feed(),
                credentialed);
        Quarkus.waitForExit();
        return 0;
    }
}
