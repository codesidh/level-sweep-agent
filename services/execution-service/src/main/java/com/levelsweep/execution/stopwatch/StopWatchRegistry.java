package com.levelsweep.execution.stopwatch;

import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeFilled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe in-memory registry of trades being watched for the §9 stop
 * trigger. The {@link com.levelsweep.execution.fill.FillListenerService}'s
 * {@code @Observes TradeFilled} fan-out hits {@link #onTradeFilled} which
 * registers the trade; the trade is deregistered when a stop fires (the
 * stop watcher calls {@link #deregister(String)}), an EOD flatten closes
 * the position, or a trail breach takes precedence.
 *
 * <p>{@link RegisteredStop} carries everything the watcher needs to assemble
 * a {@link com.levelsweep.shared.domain.trade.TradeStopTriggered} event:
 * tenantId / tradeId / correlationId / contractSymbol / alpacaOrderId /
 * side. Underlying SPY symbol is derived once at registration so the
 * watcher's bar/indicator routing is O(1).
 *
 * <p>{@code ConcurrentHashMap} is sufficient for the Phase A access pattern
 * (≤ 1 SPY 0DTE trade at a time per CLAUDE.md scope). Multi-tenant Phase B
 * pivots may need partitioned maps if the held-position count per pod grows
 * beyond a few hundred.
 */
@ApplicationScoped
public class StopWatchRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(StopWatchRegistry.class);

    private final ConcurrentMap<String, RegisteredStop> registered = new ConcurrentHashMap<>();

    /**
     * Register a trade for stop-watching when its entry fills. Idempotent —
     * a duplicate {@link TradeFilled} (at-least-once delivery from the WS
     * listener) replaces the existing entry. Side is parsed from the OCC
     * contract symbol via {@link #parseSide(String)}.
     */
    public void onTradeFilled(@Observes TradeFilled event) {
        Objects.requireNonNull(event, "event");
        OptionSide side;
        try {
            side = parseSide(event.contractSymbol());
        } catch (IllegalArgumentException e) {
            LOG.warn(
                    "stop watch: cannot parse OCC side from contractSymbol={} tenantId={} tradeId={} — skipping registration",
                    event.contractSymbol(),
                    event.tenantId(),
                    event.tradeId());
            return;
        }
        RegisteredStop stop = new RegisteredStop(
                event.tenantId(),
                event.tradeId(),
                event.alpacaOrderId(),
                event.contractSymbol(),
                underlyingSymbol(event.contractSymbol()),
                side,
                event.correlationId(),
                event.filledAt());
        registered.put(event.tradeId(), stop);
        LOG.info(
                "stop watch registered tenantId={} tradeId={} contractSymbol={} side={} underlying={}",
                stop.tenantId(),
                stop.tradeId(),
                stop.contractSymbol(),
                stop.side(),
                stop.underlyingSymbol());
    }

    /**
     * Deregister a trade. Idempotent — if the tradeId is unknown (the trail
     * watcher already deregistered, an exit already fired) we silently
     * no-op so racing callers never deadlock.
     */
    public void deregister(String tradeId) {
        Objects.requireNonNull(tradeId, "tradeId");
        RegisteredStop removed = registered.remove(tradeId);
        if (removed != null) {
            LOG.info(
                    "stop watch deregistered tenantId={} tradeId={} contractSymbol={}",
                    removed.tenantId(),
                    removed.tradeId(),
                    removed.contractSymbol());
        }
    }

    /** Live, read-only view of registered stops. Iteration is weakly-consistent. */
    public Collection<RegisteredStop> snapshot() {
        return Collections.unmodifiableCollection(registered.values());
    }

    /** Number of registered stops — used by tests + structured logs. */
    public int size() {
        return registered.size();
    }

    /**
     * Parse the OCC contract symbol's side. OCC layout: {@code <root><yymmdd><C|P><strike8>}.
     * The side character is at position {@code length - 9}.
     */
    static OptionSide parseSide(String contractSymbol) {
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        if (contractSymbol.length() < 15) {
            throw new IllegalArgumentException("contract symbol too short for OCC: " + contractSymbol);
        }
        char c = contractSymbol.charAt(contractSymbol.length() - 9);
        return switch (c) {
            case 'C' -> OptionSide.CALL;
            case 'P' -> OptionSide.PUT;
            default -> throw new IllegalArgumentException("OCC side must be 'C' or 'P', got '" + c + "'");
        };
    }

    /**
     * Extract the underlying root from an OCC contract symbol — everything
     * before the 6-digit yymmdd date. The Stop Watcher uses this to route
     * incoming bars/indicators (which are keyed by underlying symbol, e.g.
     * {@code SPY}) to the correct registered stops.
     */
    static String underlyingSymbol(String contractSymbol) {
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        // OCC tail is yymmdd[CP]nnnnnnnn → 15 chars.
        if (contractSymbol.length() < 15) {
            throw new IllegalArgumentException("contract symbol too short for OCC: " + contractSymbol);
        }
        return contractSymbol.substring(0, contractSymbol.length() - 15);
    }

    /**
     * Snapshot of one registered trade's stop configuration. Captured at
     * register-time and immutable thereafter — the watcher never mutates it.
     */
    public record RegisteredStop(
            String tenantId,
            String tradeId,
            String alpacaOrderId,
            String contractSymbol,
            String underlyingSymbol,
            OptionSide side,
            String correlationId,
            Instant registeredAt) {

        public RegisteredStop {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(tradeId, "tradeId");
            Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
            Objects.requireNonNull(contractSymbol, "contractSymbol");
            Objects.requireNonNull(underlyingSymbol, "underlyingSymbol");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(registeredAt, "registeredAt");
        }
    }
}
