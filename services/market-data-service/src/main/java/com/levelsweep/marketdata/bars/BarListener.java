package com.levelsweep.marketdata.bars;

import com.levelsweep.shared.domain.marketdata.Bar;

/**
 * Sink for completed bars. Phase 1 — the Indicator Engine (#13) and Level
 * Calculator (#12) will subscribe. In production the journal service also
 * subscribes for audit + Mongo write. Multiple listeners can be composed via
 * a fan-out wrapper.
 */
public interface BarListener {

    /** Called when a bar's timeframe boundary has closed. Must be non-blocking. */
    void onBar(Bar bar);
}
