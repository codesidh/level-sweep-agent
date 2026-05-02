package com.levelsweep.execution.alpaca;

import com.levelsweep.shared.domain.trade.OrderRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Pure helpers for translating an NBBO quote (bid/ask) into a marketable limit
 * price for an entry / exit order. The Phase 3 strategy crosses the spread by
 * a single penny (paper-trading aggression for fill quality, per
 * requirements.md §11) and rounds to two decimals using HALF_UP — Alpaca
 * rejects sub-penny prices on options.
 *
 * <p>Stateless and clock-free — safe to call from any thread, deterministic
 * across replay.
 *
 * <p>Worked example: NBBO bid 1.20 / ask 1.25 → mid 1.225 → BUY limit 1.235 →
 * rounded 1.24. SELL limit 1.215 → rounded 1.22.
 */
public final class OrderPricing {

    /** Penny we add to (BUY) or subtract from (SELL) the mid to cross the spread. */
    private static final BigDecimal PENNY = new BigDecimal("0.01");

    private static final BigDecimal TWO = new BigDecimal("2");

    private OrderPricing() {}

    /**
     * Compute a marketable limit price from a quoted bid/ask pair for the given
     * side. {@code side} must be {@link OrderRequest#SIDE_BUY} or
     * {@link OrderRequest#SIDE_SELL} (case-sensitive — same string the
     * downstream JSON body carries).
     *
     * @return mid + 0.01 for buy, mid - 0.01 for sell, both rounded HALF_UP to
     *     two decimals.
     * @throws IllegalArgumentException if bid &lt; 0, ask &lt; bid, or side
     *     unknown.
     */
    public static BigDecimal limitPriceFromNbbo(BigDecimal bid, BigDecimal ask, String side) {
        Objects.requireNonNull(bid, "bid");
        Objects.requireNonNull(ask, "ask");
        Objects.requireNonNull(side, "side");
        if (bid.signum() < 0) {
            throw new IllegalArgumentException("bid must be non-negative: " + bid);
        }
        if (ask.compareTo(bid) < 0) {
            throw new IllegalArgumentException("ask must be >= bid: bid=" + bid + " ask=" + ask);
        }

        // Use a high precision intermediate to avoid losing fractional pennies
        // on tight spreads (e.g., 1.20/1.21 → mid 1.205).
        BigDecimal mid = bid.add(ask).divide(TWO, 4, RoundingMode.HALF_UP);

        BigDecimal raw = switch (side) {
            case OrderRequest.SIDE_BUY -> mid.add(PENNY);
            case OrderRequest.SIDE_SELL -> mid.subtract(PENNY);
            default -> throw new IllegalArgumentException("unknown side: " + side);
        };

        BigDecimal rounded = raw.setScale(2, RoundingMode.HALF_UP);
        // Defensive floor — a SELL on a near-zero quote could go negative.
        if (rounded.signum() <= 0) {
            return PENNY;
        }
        return rounded;
    }
}
