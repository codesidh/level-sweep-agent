package com.levelsweep.execution.stopwatch;

import com.levelsweep.execution.persistence.StopAuditRepository;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3 Step 4 Stop Watcher orchestrator.
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>Bars from {@code bars-2m} and indicators from {@code indicators-2m}
 *       arrive via {@link BarIndicatorConsumer}.
 *   <li>{@link BarIndicatorJoiner} pairs them by {@code (symbol, timestamp)}
 *       within a 5-second tolerance window (per ADR-0005 §1).
 *   <li>For each joined pair, iterate {@link StopWatchRegistry} for trades
 *       on this symbol. Evaluate via {@link StopTriggerEvaluator} (pure
 *       §9.1 + §9.2 logic).
 *   <li>On a firing trigger: persist a {@code stop_breach_audit} row, fire
 *       a {@link TradeStopTriggered} CDI event, deregister the trade.
 * </ol>
 *
 * <p>Watcher arming: the registry observes {@code TradeFilled} directly
 * (see {@link StopWatchRegistry#onTradeFilled}); the watcher service does
 * NOT need to listen for fills — it only needs the trigger evaluation
 * pipeline.
 *
 * <p>Determinism: every step is pure given (joined pair, registered stop).
 * The single clock read is the {@link TradeStopTriggered#triggeredAt()}
 * timestamp; threaded through audit + event so replay parity holds.
 *
 * <p>Single-position assumption (Phase A): one held trade at a time
 * (CLAUDE.md scope), so the registry iteration is O(1). Phase B may pivot
 * to a per-symbol index if held positions grow.
 */
@ApplicationScoped
public class StopWatcherService {

    private static final Logger LOG = LoggerFactory.getLogger(StopWatcherService.class);

    private final StopWatchRegistry registry;
    private final StopAuditRepository audit;
    private final Event<TradeStopTriggered> stopEvent;
    private final Clock clock;
    private BarIndicatorJoiner joiner;

    @Inject
    public StopWatcherService(
            StopWatchRegistry registry, StopAuditRepository audit, Event<TradeStopTriggered> stopEvent, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.stopEvent = Objects.requireNonNull(stopEvent, "stopEvent");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostConstruct
    void init() {
        this.joiner = new BarIndicatorJoiner(clock, this::onJoined);
    }

    /** Test seam — wires a custom joiner so the 5-second window can be exercised. */
    void replaceJoiner(BarIndicatorJoiner replacement) {
        this.joiner = Objects.requireNonNull(replacement, "replacement");
    }

    /** Inbound bar from the Kafka consumer. */
    public void acceptBar(Bar bar) {
        joiner.onBar(bar);
    }

    /** Inbound indicator snapshot from the Kafka consumer. */
    public void acceptIndicator(IndicatorSnapshot indicator) {
        joiner.onIndicator(indicator);
    }

    /**
     * Joiner callback — runs §9 trigger evaluation against every registered
     * stop on the bar's underlying symbol.
     */
    void onJoined(Bar bar, IndicatorSnapshot indicator) {
        for (StopWatchRegistry.RegisteredStop reg : registry.snapshot()) {
            if (!reg.underlyingSymbol().equals(bar.symbol())) {
                continue;
            }
            Optional<StopTriggerEvaluator.Decision> decision =
                    StopTriggerEvaluator.evaluate(bar, indicator, reg.side());
            if (decision.isEmpty()) {
                continue;
            }
            StopTriggerEvaluator.Decision d = decision.get();
            TradeStopTriggered evt = new TradeStopTriggered(
                    reg.tenantId(),
                    reg.tradeId(),
                    reg.alpacaOrderId(),
                    reg.contractSymbol(),
                    bar.closeTime(),
                    d.barClose(),
                    d.stopReference(),
                    clock.instant(),
                    reg.correlationId());

            // Audit first — best-effort, swallow failures (the saga still proceeds).
            try {
                audit.record(evt);
            } catch (RuntimeException e) {
                LOG.warn(
                        "stop watcher: audit write failed tenantId={} tradeId={} reason={}",
                        evt.tenantId(),
                        evt.tradeId(),
                        e.toString());
            }

            LOG.info(
                    "stop watcher fired tenantId={} tradeId={} contractSymbol={} side={} reference={} barClose={} barTs={}",
                    evt.tenantId(),
                    evt.tradeId(),
                    evt.contractSymbol(),
                    reg.side(),
                    evt.stopReference(),
                    evt.barClose(),
                    evt.barTimestamp());

            // Deregister BEFORE firing the CDI event so a synchronous
            // observer that races to deregister the trade (the trail
            // manager) sees an already-deregistered stop and silently
            // no-ops. ExitOrderRouter only needs the contractSymbol +
            // tenantId from the event, not the registry.
            registry.deregister(reg.tradeId());
            stopEvent.fire(evt);
        }
    }
}
