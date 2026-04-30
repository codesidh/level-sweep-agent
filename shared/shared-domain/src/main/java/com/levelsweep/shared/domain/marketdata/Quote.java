package com.levelsweep.shared.domain.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable NBBO quote snapshot.
 *
 * <p>Phase 1 (data layer) — used by the trailing manager (Phase 3) to evaluate
 * the option premium mid for trail-floor ratchets.
 */
public record Quote(
        String symbol,
        BigDecimal bidPrice,
        long bidSize,
        BigDecimal askPrice,
        long askSize,
        Instant timestamp) {

    public Quote {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(bidPrice, "bidPrice");
        Objects.requireNonNull(askPrice, "askPrice");
        Objects.requireNonNull(timestamp, "timestamp");
        if (bidPrice.signum() < 0 || askPrice.signum() < 0) {
            throw new IllegalArgumentException(
                    "bid/ask must be non-negative: bid=" + bidPrice + ", ask=" + askPrice);
        }
        if (bidSize < 0 || askSize < 0) {
            throw new IllegalArgumentException(
                    "sizes must be non-negative: bidSize=" + bidSize + ", askSize=" + askSize);
        }
    }

    /** Mid-price between bid and ask. Used by the trailing manager. */
    public BigDecimal mid() {
        return bidPrice.add(askPrice).divide(BigDecimal.valueOf(2));
    }
}
