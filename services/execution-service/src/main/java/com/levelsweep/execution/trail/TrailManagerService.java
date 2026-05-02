package com.levelsweep.execution.trail;

import com.levelsweep.shared.domain.trade.TradeEodFlattened;
import com.levelsweep.shared.domain.trade.TradeFilled;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3 Step 5 trail manager top-level coordinator. Owns the registry
 * lifecycle:
 *
 * <ul>
 *   <li>{@link TradeFilled} — register a fresh {@link TrailState} (entry
 *       premium = filled avg price, qty = filled quantity).
 *   <li>{@link TradeStopTriggered} / {@link TradeEodFlattened} /
 *       {@link TradeTrailBreached} — deregister whichever trade just
 *       reached terminal. {@link TrailPollScheduler#pollOnce} drives the
 *       FSM independently.
 * </ul>
 *
 * <p>Determinism: pure CDI fan-out + a single {@link TrailRegistry#register}
 * per fill. The state-machine transitions live in {@link TrailStateMachine};
 * this class is just plumbing.
 */
@ApplicationScoped
public class TrailManagerService {

    private static final Logger LOG = LoggerFactory.getLogger(TrailManagerService.class);

    private final TrailRegistry registry;

    @Inject
    public TrailManagerService(TrailRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    void onTradeFilled(@Observes TradeFilled event) {
        Objects.requireNonNull(event, "event");
        if (event.filledAvgPrice().signum() <= 0) {
            // Defensive — TradeFilled validation already guarantees
            // non-negative, but a literal-zero entry premium would divide
            // by zero in TrailStateMachine.uplPct. Skip rather than crash.
            LOG.warn(
                    "trail manager: skip register, non-positive entry premium tenantId={} tradeId={} avgPrice={}",
                    event.tenantId(),
                    event.tradeId(),
                    event.filledAvgPrice());
            return;
        }
        TrailState state = new TrailState(
                event.tenantId(),
                event.tradeId(),
                event.contractSymbol(),
                event.filledAvgPrice(),
                event.filledQty(),
                event.correlationId());
        registry.register(state);
    }

    void onTradeStopTriggered(@Observes TradeStopTriggered event) {
        Objects.requireNonNull(event, "event");
        registry.deregister(event.tradeId());
    }

    void onTradeTrailBreached(@Observes TradeTrailBreached event) {
        Objects.requireNonNull(event, "event");
        // Defensive — TrailPollScheduler already deregisters before firing,
        // but a direct external producer of TradeTrailBreached would still
        // need cleanup.
        registry.deregister(event.tradeId());
    }

    void onTradeEodFlattened(@Observes TradeEodFlattened event) {
        Objects.requireNonNull(event, "event");
        registry.deregister(event.tradeId());
    }
}
