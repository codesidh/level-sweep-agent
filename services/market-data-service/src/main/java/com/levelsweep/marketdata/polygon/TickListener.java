package com.levelsweep.marketdata.polygon;

import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;

/**
 * Sink interface implemented by downstream consumers (the bar aggregator in
 * Phase 1 issue #11 will be the first). Decouples the WebSocket transport
 * from message processing — tests substitute a recording listener instead
 * of standing up a WS server.
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
