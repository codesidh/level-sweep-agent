package com.levelsweep.marketdata.levels;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure-function calculator that derives the four reference levels (PDH, PDL,
 * PMH, PML) from a collection of intraday bars.
 *
 * <p>Inputs:
 *
 * <ul>
 *   <li>{@code rthBars} — bars from the prior RTH session (any intraday
 *       timeframe). The high of the bar with the maximum high is PDH; the low
 *       of the bar with the minimum low is PDL.
 *   <li>{@code overnightBars} — bars from 16:01 ET prior day → 09:29 ET today
 *       (any intraday timeframe). Same high/low extraction yields PMH/PML.
 * </ul>
 *
 * <p>Replay-deterministic: pure logic, no IO, no clock dependency. The
 * {@link SessionWindows} helper produces the input windows; the caller is
 * responsible for filtering bars into the correct buckets.
 */
public final class LevelCalculator {

    private LevelCalculator() {}

    /**
     * Compute Levels for {@code sessionDate}.
     *
     * @param tenantId        tenant scope (Phase A: "OWNER")
     * @param symbol          underlying symbol (Phase 1: SPY)
     * @param sessionDate     trading session in ET
     * @param rthBars         bars within the prior RTH window (see
     *                        {@link SessionWindows#rth})
     * @param overnightBars   bars within the overnight window (see
     *                        {@link SessionWindows#overnight})
     * @return Levels record
     * @throws IllegalArgumentException if either bar collection is empty
     */
    public static Levels compute(
            String tenantId,
            String symbol,
            LocalDate sessionDate,
            Collection<Bar> rthBars,
            Collection<Bar> overnightBars) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(sessionDate, "sessionDate");
        requireNonEmpty(rthBars, "rthBars");
        requireNonEmpty(overnightBars, "overnightBars");
        validateBars(rthBars, symbol, "rthBars");
        validateBars(overnightBars, symbol, "overnightBars");

        BigDecimal pdh = high(rthBars);
        BigDecimal pdl = low(rthBars);
        BigDecimal pmh = high(overnightBars);
        BigDecimal pml = low(overnightBars);
        return new Levels(tenantId, symbol, sessionDate, pdh, pdl, pmh, pml);
    }

    /**
     * Compute only the RTH portion (PDH/PDL). Used when the overnight stream
     * isn't yet available — the caller can compose later.
     */
    public static Optional<RthLevels> computeRth(String symbol, Collection<Bar> rthBars) {
        if (rthBars == null || rthBars.isEmpty()) {
            return Optional.empty();
        }
        validateBars(rthBars, symbol, "rthBars");
        return Optional.of(new RthLevels(high(rthBars), low(rthBars)));
    }

    public record RthLevels(BigDecimal pdh, BigDecimal pdl) {}

    private static BigDecimal high(Collection<Bar> bars) {
        return bars.stream()
                .map(Bar::high)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalArgumentException("no bars"));
    }

    private static BigDecimal low(Collection<Bar> bars) {
        return bars.stream()
                .map(Bar::low)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalArgumentException("no bars"));
    }

    private static void requireNonEmpty(Collection<?> c, String name) {
        if (c == null || c.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
    }

    private static void validateBars(Collection<Bar> bars, String expectedSymbol, String name) {
        for (Bar b : bars) {
            if (!b.symbol().equals(expectedSymbol)) {
                throw new IllegalArgumentException(
                        name + ": symbol mismatch — expected=" + expectedSymbol + " got=" + b.symbol());
            }
            if (!b.timeframe().isIntraday()) {
                throw new IllegalArgumentException(
                        name + ": only intraday bars accepted, got " + b.timeframe());
            }
            if (b.timeframe() == Timeframe.DAILY) {
                // Defensive — already covered by isIntraday but explicit for readers.
                throw new IllegalArgumentException(name + ": daily bar not allowed");
            }
        }
    }
}
