package com.levelsweep.execution.ingest;

import com.levelsweep.shared.domain.trade.TradeProposed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka consumer for the {@code tenant.commands} topic
 * (architecture-spec §12.1 row 5) joined under consumer group
 * {@code execution-service}. The Trade Saga in decision-engine produces the
 * upstream stream via {@code com.levelsweep.decision.saga.TradeProposedKafkaPublisher}.
 *
 * <p>The {@link TradeProposed} value comes off the wire pre-deserialized via
 * {@link TradeProposedDeserializer} (a typed
 * {@code ObjectMapperDeserializer<TradeProposed>}), so this method receives a
 * fully-validated record.
 *
 * <p>Each event delegates to a {@link TradeRouter} so Phase 3 Step 2+ modules
 * (Alpaca order placement, fill listener, stop watcher, EOD flatten) plug in
 * without touching this class. The default router ({@link NoOpTradeRouter})
 * just counts; replacement is a CDI bean swap.
 *
 * <p>Logging: each consumed event emits an INFO line with tenant, tradeId,
 * underlying, side, contract symbol, and correlationId. INFO (not DEBUG) is
 * deliberate — trade volume is low (≤ 5 trades/day per tenant per requirements
 * §11) and operators want every order routed visible in the steady-state log
 * stream. Mirrors decision-engine's {@code BarConsumer} pattern.
 */
@ApplicationScoped
public class TradeProposedConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TradeProposedConsumer.class);

    private final TradeRouter router;

    @Inject
    public TradeProposedConsumer(TradeRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    @Incoming("trade-proposed-in")
    public void consume(TradeProposed event) {
        Objects.requireNonNull(event, "event");
        LOG.info(
                "received trade-proposed tenant={} tradeId={} symbol={} side={} contractSymbol={} correlationId={}",
                event.tenantId(),
                event.tradeId(),
                event.underlying(),
                event.side(),
                event.contractSymbol(),
                event.correlationId());
        router.onTradeProposed(event);
    }
}
