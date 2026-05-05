package com.levelsweep.execution.messaging;

import com.levelsweep.shared.domain.trade.TradeFilled;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI bridge that relays the {@link TradeFilled} CDI event fired by the
 * Alpaca trade-updates listener onto the Kafka {@code tenant.fills} topic
 * (architecture-spec §12.1). The decision-engine's per-trade FSM is the
 * primary downstream consumer — it advances ENTERED → ACTIVE on first fill.
 *
 * <p>Wiring: the WebSocket listener
 * ({@link com.levelsweep.execution.fill.AlpacaTradeUpdatesStream}) fires
 * {@code Event<TradeFilled>} via the standard CDI bus; this observer is a
 * thin {@code @Observes} adapter that translates the CDI event into a Kafka
 * {@link Record} keyed by {@link TradeFilled#tenantId()}. Keying by tenant
 * preserves per-tenant order across the partition and lets the broker scale
 * horizontally as the multi-tenant onboarding flag flips on in Phase B.
 *
 * <p>Phase 7 enabled this in production: Strimzi/Kafka now runs in the dev
 * cluster (`infra/k8s-dev/kafka.yaml`). The previous `@UnlessBuildProfile("prod")`
 * gate has been removed.
 *
 * <p>Emit pattern: fire-and-forget. {@code MutinyEmitter#send} returns a
 * {@code Uni<Void>} which we subscribe to with a no-op success handler and a
 * log-on-failure handler. This avoids blocking the listener's WS callback
 * thread on Kafka acks while still surfacing publish failures via the log.
 */
@ApplicationScoped
public class TradeFilledKafkaPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(TradeFilledKafkaPublisher.class);

    private final MutinyEmitter<Record<String, TradeFilled>> emitter;

    @Inject
    public TradeFilledKafkaPublisher(@Channel("trade-filled-out") MutinyEmitter<Record<String, TradeFilled>> emitter) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    public void onTradeFilled(@Observes TradeFilled event) {
        Record<String, TradeFilled> rec = Record.of(event.tenantId(), event);
        emitter.send(rec)
                .subscribe()
                .with(
                        ignored -> {},
                        failure -> LOG.warn(
                                "kafka publish failed tenant={} tradeId={} alpacaOrderId={} cause={}",
                                event.tenantId(),
                                event.tradeId(),
                                event.alpacaOrderId(),
                                failure.toString()));
    }
}
