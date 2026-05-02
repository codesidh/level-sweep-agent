package com.levelsweep.execution.state;

import com.levelsweep.shared.domain.trade.InFlightTrade;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory cache of trades currently held by execution-service.
 * Phase 3 keeps state-of-record here; Phase 7 promotes it to the
 * {@code trades} table (already in V102 schema) so JVM restarts can rehydrate
 * positions on boot.
 *
 * <p>Producers: the S3 fill-listener calls {@link #put(InFlightTrade)} when an
 * entry order's {@code TradeFilled} event arrives, and {@link #remove(String)}
 * when a stop / trailing exit / EOD flatten closes the position. This PR
 * (S6 — EOD flatten saga) only consumes via {@link #snapshot()}.
 *
 * <p>{@code ConcurrentHashMap} is sufficient for the Phase 3 access pattern:
 * a few writes per second under the heaviest signal day, occasional reads
 * from the EOD cron. {@link #snapshot()} returns an unmodifiable view backed
 * by the live values collection — iteration is weakly-consistent against
 * concurrent puts/removes but never throws ConcurrentModificationException.
 * The unmodifiable wrapper prevents callers accidentally mutating the cache
 * via the snapshot reference.
 */
@ApplicationScoped
public class InFlightTradeCache {

    private final ConcurrentMap<String, InFlightTrade> trades = new ConcurrentHashMap<>();

    /**
     * Insert or replace a trade keyed by {@code tradeId}. Idempotent — calling
     * twice with the same {@code tradeId} keeps only the most recent value, so
     * the S3 listener can replay TradeFilled events at-least-once without
     * special-casing duplicates.
     */
    public void put(InFlightTrade trade) {
        Objects.requireNonNull(trade, "trade");
        trades.put(trade.tradeId(), trade);
    }

    /**
     * Remove a trade by {@code tradeId}. No-op if the id is absent — exit-path
     * callers (stop manager, trailing manager, EOD saga) can race to remove a
     * trade and the loser silently no-ops.
     */
    public void remove(String tradeId) {
        Objects.requireNonNull(tradeId, "tradeId");
        trades.remove(tradeId);
    }

    /**
     * Live, read-only view of currently in-flight trades. Iteration is
     * weakly-consistent — concurrent {@code put} / {@code remove} calls are
     * permitted while the caller iterates. The returned collection rejects
     * structural mutation so callers cannot drift out of the cache contract.
     */
    public Collection<InFlightTrade> snapshot() {
        return Collections.unmodifiableCollection(trades.values());
    }

    /** Number of in-flight trades. Used by tests and by the EOD scheduler's structured log. */
    public int size() {
        return trades.size();
    }
}
