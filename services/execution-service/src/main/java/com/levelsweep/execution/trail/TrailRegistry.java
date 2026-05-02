package com.levelsweep.execution.trail;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe in-memory registry of trades being trailed for the §10
 * profit-target stop. Owned by {@link TrailManagerService}; populated on
 * {@code TradeFilled} and depleted on stop / trail / EOD terminal events.
 *
 * <p>Pure storage — the FSM transitions live in {@link TrailStateMachine}.
 * Mirrors {@link com.levelsweep.execution.stopwatch.StopWatchRegistry}'s
 * shape; not collapsed into one class because the per-trade state is very
 * different (the stop watcher needs side + symbol, the trail manager needs
 * entry premium + floor + sustainment counters).
 *
 * <p>{@code ConcurrentHashMap} is sufficient for the Phase A access pattern
 * (≤ 1 SPY 0DTE trade at a time per CLAUDE.md scope). The TrailPollScheduler
 * iterates the registry once per second; ratchet/exit transitions mutate
 * state inside the {@link TrailState} wrapper under the registry's
 * synchronization scope.
 */
@ApplicationScoped
public class TrailRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TrailRegistry.class);

    private final ConcurrentMap<String, TrailState> states = new ConcurrentHashMap<>();

    /** Register a trade for trailing. Idempotent — duplicate replaces. */
    public void register(TrailState state) {
        Objects.requireNonNull(state, "state");
        states.put(state.tradeId(), state);
        LOG.info(
                "trail registered tenantId={} tradeId={} contractSymbol={} entryPremium={} qty={}",
                state.tenantId(),
                state.tradeId(),
                state.contractSymbol(),
                state.entryPremium(),
                state.qty());
    }

    /**
     * Deregister a trade. Idempotent — silent no-op when absent so the
     * stop watcher / trail manager / EOD saga can race without a deadlock.
     */
    public void deregister(String tradeId) {
        Objects.requireNonNull(tradeId, "tradeId");
        TrailState removed = states.remove(tradeId);
        if (removed != null) {
            LOG.info(
                    "trail deregistered tenantId={} tradeId={} contractSymbol={}",
                    removed.tenantId(),
                    removed.tradeId(),
                    removed.contractSymbol());
        }
    }

    /** Live, read-only view of registered trail states. Iteration is weakly-consistent. */
    public Collection<TrailState> snapshot() {
        return Collections.unmodifiableCollection(states.values());
    }

    /** Number of registered trails — used by tests + structured logs. */
    public int size() {
        return states.size();
    }
}
