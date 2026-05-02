package com.levelsweep.execution.stopwatch;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka consumer for the {@code market.bars.2m} and {@code market.indicators.2m}
 * topics, routed via the {@code bars-2m} and {@code indicators-2m} channels.
 * Pure Quarkus reactive-messaging adapter — both methods forward the typed
 * record to the {@link StopWatcherService} which holds the join logic.
 *
 * <p>Pre-deserialized via {@link BarDeserializer} and
 * {@link IndicatorSnapshotDeserializer} (configured in {@code application.yml})
 * so each {@code @Incoming} method receives a fully-validated record.
 *
 * <p>Mirrors decision-engine's {@code BarConsumer} + {@code IndicatorSnapshotConsumer}
 * pattern. Joins the {@code execution-service} consumer group so multiple
 * pods (Phase B) share the partition load.
 */
@ApplicationScoped
public class BarIndicatorConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(BarIndicatorConsumer.class);

    private final StopWatcherService watcher;

    @Inject
    public BarIndicatorConsumer(StopWatcherService watcher) {
        this.watcher = Objects.requireNonNull(watcher, "watcher");
    }

    @Incoming("bars-2m")
    public void consumeBar(Bar bar) {
        Objects.requireNonNull(bar, "bar");
        if (LOG.isDebugEnabled()) {
            LOG.debug("execution stop-watcher bar received symbol={} closeTime={}", bar.symbol(), bar.closeTime());
        }
        watcher.acceptBar(bar);
    }

    @Incoming("indicators-2m")
    public void consumeIndicator(IndicatorSnapshot indicator) {
        Objects.requireNonNull(indicator, "indicator");
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "execution stop-watcher indicator received symbol={} timestamp={}",
                    indicator.symbol(),
                    indicator.timestamp());
        }
        watcher.acceptIndicator(indicator);
    }
}
