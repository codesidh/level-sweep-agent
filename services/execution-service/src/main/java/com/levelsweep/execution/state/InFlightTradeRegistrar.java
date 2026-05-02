package com.levelsweep.execution.state;

import com.levelsweep.shared.domain.trade.InFlightTrade;
import com.levelsweep.shared.domain.trade.TradeEodFlattened;
import com.levelsweep.shared.domain.trade.TradeExitOrderSubmitted;
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
 * CDI bridge that wires the in-flight trade lifecycle into
 * {@link InFlightTradeCache}: register on {@link TradeFilled}, deregister on
 * any of the three terminal events. Phase 3 Step 4/5 (this PR) added the
 * stop / trail trigger events; Phase 3 Step 6 already emits
 * {@link TradeEodFlattened}. Without this bridge the cache is never populated
 * and the EOD scheduler + ExitOrderRouter both lose the contract → quantity
 * lookup they depend on.
 *
 * <p>Idempotent on both ends — a duplicate {@link TradeFilled} replaces the
 * existing entry, a duplicate terminal event is a silent no-op. The
 * deregister side races between stop and trail watchers; whoever fires first
 * wins, the other observes a no-op deregister. Phase 7 will move the
 * source-of-truth to the {@code trades} MS SQL table for restart durability;
 * until then, this in-memory bridge is the single producer.
 *
 * <p>Determinism: pure CDI fan-out over already-deterministic events. Two
 * runs over the same event sequence produce the same cache contents.
 */
@ApplicationScoped
public class InFlightTradeRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(InFlightTradeRegistrar.class);

    private final InFlightTradeCache cache;

    @Inject
    public InFlightTradeRegistrar(InFlightTradeCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    void onTradeFilled(@Observes TradeFilled event) {
        Objects.requireNonNull(event, "event");
        InFlightTrade trade = new InFlightTrade(
                event.tenantId(),
                event.tradeId(),
                event.contractSymbol(),
                event.filledQty(),
                event.filledAt(),
                event.correlationId());
        cache.put(trade);
        LOG.info(
                "in-flight cache: trade registered tenantId={} tradeId={} contractSymbol={} qty={}",
                trade.tenantId(),
                trade.tradeId(),
                trade.contractSymbol(),
                trade.quantity());
    }

    void onTradeStopTriggered(@Observes TradeStopTriggered event) {
        Objects.requireNonNull(event, "event");
        cache.remove(event.tradeId());
    }

    void onTradeTrailBreached(@Observes TradeTrailBreached event) {
        Objects.requireNonNull(event, "event");
        cache.remove(event.tradeId());
    }

    void onTradeEodFlattened(@Observes TradeEodFlattened event) {
        Objects.requireNonNull(event, "event");
        // EodFlattenScheduler already removes from the cache on its FLATTENED
        // success path; this observer is the safety net for any future EOD
        // success path that does not (e.g. a manual flatten command). No-op
        // when the trade is already gone.
        cache.remove(event.tradeId());
    }

    void onTradeExitOrderSubmitted(@Observes TradeExitOrderSubmitted event) {
        Objects.requireNonNull(event, "event");
        // Belt-and-braces — the stop/trail observers above already removed,
        // but if a future exit reason emits TradeExitOrderSubmitted without
        // a matching trigger, we still clear.
        cache.remove(event.tradeId());
    }
}
