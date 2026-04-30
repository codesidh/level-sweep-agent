package com.levelsweep.marketdata.api;

import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;

/**
 * Provider-agnostic sink for market data events. Implementations subscribe
 * to (a) trade ticks for the bar aggregator and (b) NBBO quotes for the
 * trail manager (Phase 3).
 *
 * <p>Decouples the WebSocket transport from message processing — tests
 * substitute a recording listener instead of standing up a WS server.
 */
public interface TickListener {

    /** Called for every parsed trade tick. Must be non-blocking. */
    void onTick(Tick tick);

    /** Called for every parsed NBBO quote. Must be non-blocking. */
    void onQuote(Quote quote);

    /** Called when the WebSocket emits a non-data status frame. */
    default void onStatus(String status, String message) {
        // default no-op — most listeners don't care about status frames.
    }
}
