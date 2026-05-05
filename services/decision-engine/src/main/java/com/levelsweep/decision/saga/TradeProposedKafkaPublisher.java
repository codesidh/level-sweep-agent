package com.levelsweep.decision.saga;

import com.levelsweep.shared.domain.trade.TradeProposed;
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
 * CDI bridge that relays the {@link TradeProposed} CDI event fired by
 * {@link TradeSaga} onto the Kafka {@code tenant.commands} topic
 * (architecture-spec §12.1 row 5). The Phase 3 execution-service is the primary
 * downstream consumer.
 *
 * <p>Wiring: the saga continues to fire {@code Event<TradeProposed>} via the
 * standard CDI bus; this observer is a thin {@code @Observes} adapter that
 * translates the CDI event into a Kafka {@link Record} keyed by
 * {@link TradeProposed#tenantId()}. Keying by tenant preserves per-tenant order
 * across the partition and lets the broker scale horizontally as the multi-tenant
 * onboarding flag flips on in Phase B.
 *
 * <p>Phase 7 enabled this in production: Strimzi/Kafka now runs in the dev
 * cluster (`infra/k8s-dev/kafka.yaml`). The previous `@UnlessBuildProfile("prod")`
 * gate has been removed.
 *
 * <p>Emit pattern: fire-and-forget. {@code MutinyEmitter#send} returns a
 * {@code Uni<Void>} which we subscribe to with a no-op success handler and a
 * log-on-failure handler. This avoids blocking the saga's bar-consumer thread on
 * Kafka acks while still surfacing publish failures via the log. Quarkus's
 * outgoing channel buffers internally so a transient broker hiccup does not
 * stall saga evaluation. The CDI event is still fired (and observed by other
 * application-scoped subscribers, e.g. Micrometer counters) regardless of
 * whether the Kafka publish succeeds — the saga's audit trail does not depend
 * on broker availability.
 */
@ApplicationScoped
public class TradeProposedKafkaPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(TradeProposedKafkaPublisher.class);

    private final MutinyEmitter<Record<String, TradeProposed>> emitter;

    @Inject
    public TradeProposedKafkaPublisher(
            @Channel("trade-proposed-out") MutinyEmitter<Record<String, TradeProposed>> emitter) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    /**
     * Observes every {@link TradeProposed} fired by {@link TradeSaga} and relays
     * it to the {@code tenant.commands} Kafka topic. Keyed by {@code tenantId} so
     * the broker partitions per-tenant — preserves ordering for any one tenant's
     * trade stream, and gives the partition layout headroom for Phase B
     * multi-tenant onboarding.
     */
    public void onTradeProposed(@Observes TradeProposed event) {
        Record<String, TradeProposed> rec = Record.of(event.tenantId(), event);
        emitter.send(rec)
                .subscribe()
                .with(
                        ignored -> {},
                        failure -> LOG.warn(
                                "kafka publish failed tenant={} tradeId={} cause={}",
                                event.tenantId(),
                                event.tradeId(),
                                failure.toString()));
    }
}
