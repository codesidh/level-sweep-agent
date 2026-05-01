package com.levelsweep.shared.domain.options;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable carrier for the subset of an Alpaca option-chain snapshot the
 * 0DTE strike selector needs to choose a contract.
 *
 * <p>Per {@code requirements.md} §13, the entry vehicle is a single-leg 0DTE
 * SPY option. The selector evaluates strikes near spot ({@code |strike - spot|}
 * within the configured ATM band) and rejects illiquid candidates by NBBO
 * width and open interest before picking the winner.
 *
 * <p>Field semantics:
 *
 * <ul>
 *   <li>{@link #symbol} — OCC contract symbol, e.g. {@code SPY250130C00600000}.
 *   <li>{@link #underlying} — the deliverable's symbol (e.g. {@code SPY}).
 *   <li>{@link #expiry} — contract expiration date (calendar day, exchange tz).
 *       0DTE filtering compares this to the trading-session date supplied by
 *       the caller.
 *   <li>{@link #strike} — option strike price in dollars.
 *   <li>{@link #side} — CALL or PUT.
 *   <li>{@link #bidPrice}, {@link #askPrice} — latest NBBO from the chain
 *       snapshot. Both are required (zero is allowed; empty quotes upstream
 *       should be filtered out before constructing this record).
 *   <li>{@link #openInterest}, {@link #volume}, {@link #impliedVolatility},
 *       {@link #delta} — optional fields the snapshot may omit; the selector
 *       treats absent open-interest / volume as zero.
 * </ul>
 */
public record OptionContract(
        String symbol,
        String underlying,
        LocalDate expiry,
        BigDecimal strike,
        OptionSide side,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        Optional<Integer> openInterest,
        Optional<Integer> volume,
        Optional<BigDecimal> impliedVolatility,
        Optional<BigDecimal> delta) {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public OptionContract {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(underlying, "underlying");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(strike, "strike");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(bidPrice, "bidPrice");
        Objects.requireNonNull(askPrice, "askPrice");
        Objects.requireNonNull(openInterest, "openInterest");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(impliedVolatility, "impliedVolatility");
        Objects.requireNonNull(delta, "delta");
        if (strike.signum() <= 0) {
            throw new IllegalArgumentException("strike must be positive: " + strike);
        }
        if (bidPrice.signum() < 0) {
            throw new IllegalArgumentException("bidPrice must be non-negative: " + bidPrice);
        }
        if (askPrice.signum() < 0) {
            throw new IllegalArgumentException("askPrice must be non-negative: " + askPrice);
        }
        if (askPrice.compareTo(bidPrice) < 0) {
            throw new IllegalArgumentException(
                    "askPrice must be >= bidPrice: bid=" + bidPrice + " ask=" + askPrice);
        }
    }

    /**
     * Mid price = (bid + ask) / 2, rounded to 4dp half-up. Returns zero when
     * both bid and ask are zero (no live quote).
     */
    public BigDecimal mid() {
        return bidPrice.add(askPrice).divide(TWO, 4, RoundingMode.HALF_UP);
    }

    /**
     * NBBO spread as a percentage of mid: {@code (ask - bid) / mid * 100}.
     * Returns 100.0 when mid is zero — a non-quoted contract is treated as
     * maximally illiquid, which causes the selector to reject it.
     */
    public BigDecimal spreadPct() {
        BigDecimal mid = mid();
        if (mid.signum() == 0) {
            return HUNDRED;
        }
        BigDecimal spread = askPrice.subtract(bidPrice);
        return spread.divide(mid, 4, RoundingMode.HALF_UP).multiply(HUNDRED);
    }

    /** Absolute NBBO width in dollars — {@code ask - bid}. */
    public BigDecimal spreadAbs() {
        return askPrice.subtract(bidPrice);
    }
}
