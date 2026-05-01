package com.levelsweep.decision.ingest;

import com.levelsweep.shared.domain.marketdata.Bar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka consumer fan-in for the four bar topics (architecture-spec §12.1):
 *
 * <ul>
 *   <li>{@code market.bars.1m}    via channel {@code bars-1m}
 *   <li>{@code market.bars.2m}    via channel {@code bars-2m}
 *   <li>{@code market.bars.15m}   via channel {@code bars-15m}
 *   <li>{@code market.bars.daily} via channel {@code bars-daily}
 * </ul>
 *
 * <p>All four channels join the same consumer group (configured in
 * {@code application.yml}) so multiple Decision Engine pods share the partition
 * load. The Bar value comes off the wire pre-deserialized via Quarkus's
 * {@code ObjectMapperDeserializer} (target type set per channel), so each
 * {@code @Incoming} method receives a fully-validated {@link Bar} record.
 *
 * <p>Each method delegates to a {@link BarRouter} so Phase 2 Step 2+ modules
 * (Signal Engine, Risk Manager) plug in without touching this class. The default
 * router ({@link NoOpBarRouter}) just counts; replacement is a CDI bean swap.
 *
 * <p>Logging: each consumed bar emits a DEBUG line with symbol, timeframe, open
 * and close times, plus the close price. Volume is intentionally omitted — it is
 * misleading at the bar boundary because aggregator semantics around the open
 * tick can produce small or zero values for low-liquidity symbols, and the field
 * is already retained on the original Kafka record for forensic replay.
 */
@ApplicationScoped
public class BarConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(BarConsumer.class);

    private final BarRouter router;

    @Inject
    public BarConsumer(BarRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    @Incoming("bars-1m")
    public void consumeOneMinBar(Bar bar) {
        dispatch(bar);
    }

    @Incoming("bars-2m")
    public void consumeTwoMinBar(Bar bar) {
        dispatch(bar);
    }

    @Incoming("bars-15m")
    public void consumeFifteenMinBar(Bar bar) {
        dispatch(bar);
    }

    @Incoming("bars-daily")
    public void consumeDailyBar(Bar bar) {
        dispatch(bar);
    }

    private void dispatch(Bar bar) {
        Objects.requireNonNull(bar, "bar");
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "consumed bar symbol={} timeframe={} openTime={} closeTime={} close={}",
                    bar.symbol(),
                    bar.timeframe(),
                    bar.openTime(),
                    bar.closeTime(),
                    bar.close());
        }
        router.onBar(bar);
    }
}
