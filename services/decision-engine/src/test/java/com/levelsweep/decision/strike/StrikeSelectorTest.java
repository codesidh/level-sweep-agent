package com.levelsweep.decision.strike;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.options.OptionContract;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.options.StrikeSelectionResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link StrikeSelector}. We construct the
 * selector with the brief's default thresholds (10% spread, $0.10 absolute,
 * 100 OI, $2 ATM band) and exercise each filter rule independently.
 */
class StrikeSelectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 30);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final BigDecimal SPOT = new BigDecimal("600.50");

    private final StrikeSelector selector = newSelector();

    private static StrikeSelector newSelector() {
        return new StrikeSelector(new BigDecimal("10.0"), new BigDecimal("0.10"), 100, new BigDecimal("2.0"));
    }

    @Test
    void emptyChainReturnsNoCandidates() {
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.NoCandidates.class);
        assertThat(((StrikeSelectionResult.NoCandidates) result).reasonCode()).isEqualTo("empty_chain");
    }

    @Test
    void wrongExpiryFilteredOut() {
        OptionContract notToday = liquidCall("SPY", TOMORROW, "600", "1.10", "1.15");
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(notToday), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.NoCandidates.class);
        assertThat(((StrikeSelectionResult.NoCandidates) result).reasonCode()).isEqualTo("no_atm_band_match");
    }

    @Test
    void wrongSideFilteredOut() {
        OptionContract put = liquidPut("SPY", TODAY, "600", "1.10", "1.15");
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(put), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.NoCandidates.class);
        assertThat(((StrikeSelectionResult.NoCandidates) result).reasonCode()).isEqualTo("no_atm_band_match");
    }

    @Test
    void outOfAtmBandRejected() {
        OptionContract farFromMoney = liquidCall("SPY", TODAY, "610", "1.10", "1.15"); // 9.50 from spot
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(farFromMoney), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.NoCandidates.class);
        assertThat(((StrikeSelectionResult.NoCandidates) result).reasonCode()).isEqualTo("no_atm_band_match");
    }

    @Test
    void wideSpreadRejected() {
        // Spread = 0.30, mid = 1.15 → 26% spread > 10% threshold AND $0.30 > $0.10.
        OptionContract wide = call("SPY", TODAY, "600", "1.00", "1.30", 500);
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(wide), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.NoCandidates.class);
        assertThat(((StrikeSelectionResult.NoCandidates) result).reasonCode()).isEqualTo("no_strike_passed_liquidity");
    }

    @Test
    void tightPercentButWideAbsoluteRejected() {
        // 5% spread is fine percent-wise (high-priced contract), but $0.50 > $0.10 absolute.
        // bid=10.00 ask=10.50 → mid 10.25, spread 0.50 → 4.88% pct, $0.50 abs.
        OptionContract pricey = call("SPY", TODAY, "600", "10.00", "10.50", 500);
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(pricey), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.NoCandidates.class);
        assertThat(((StrikeSelectionResult.NoCandidates) result).reasonCode()).isEqualTo("no_strike_passed_liquidity");
    }

    @Test
    void lowOpenInterestRejected() {
        OptionContract thinOi = call("SPY", TODAY, "600", "1.10", "1.15", 50); // 50 OI < 100 threshold
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(thinOi), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.NoCandidates.class);
        assertThat(((StrikeSelectionResult.NoCandidates) result).reasonCode()).isEqualTo("no_strike_passed_liquidity");
    }

    @Test
    void multipleSurvivorsClosestToSpotWins() {
        // 600 is 0.50 from 600.50; 601 is 0.50 from 600.50; 599 is 1.50 from 600.50.
        OptionContract c599 = call("SPY", TODAY, "599", "1.10", "1.15", 500);
        OptionContract c600 = call("SPY", TODAY, "600", "1.05", "1.10", 500);
        OptionContract c601 = call("SPY", TODAY, "601", "1.10", "1.15", 500);
        // c600 and c601 both at distance 0.50; c600 has tighter spread (4.65% vs 4.44%? recompute)
        // c600: spread 0.05 / mid 1.075 = 4.65%
        // c601: spread 0.05 / mid 1.125 = 4.44%
        // Both within band; on equal distance, tighter spread wins → c601 (4.44 < 4.65).
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(c599, c600, c601), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.Selected.class);
        StrikeSelectionResult.Selected selected = (StrikeSelectionResult.Selected) result;
        // c600 strike (600) has |600 - 600.50| = 0.50, c601 has 0.50. Tie broken by spread%.
        assertThat(selected.selection().chosen().strike()).isEqualByComparingTo(new BigDecimal("601"));
    }

    @Test
    void closestToSpotBeatsTighterSpread() {
        // 600 strike: distance 0.50, spread 5.0%
        // 599 strike: distance 1.50, spread 1.0% — tighter but farther.
        // Distance is the primary sort key, so 600 wins.
        OptionContract closer = call("SPY", TODAY, "600", "1.00", "1.05", 500); // ~4.88%
        OptionContract tighter = call("SPY", TODAY, "599", "1.000", "1.010", 500); // ~1.00%
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(closer, tighter), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.Selected.class);
        assertThat(((StrikeSelectionResult.Selected) result)
                        .selection()
                        .chosen()
                        .strike())
                .isEqualByComparingTo(new BigDecimal("600"));
    }

    @Test
    void rejectedCandidatesAreCarriedOnSelection() {
        OptionContract chosen = call("SPY", TODAY, "600", "1.05", "1.10", 500);
        OptionContract bench = call("SPY", TODAY, "601", "1.00", "1.40", 500); // wide spread, rejected
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.CALL, List.of(chosen, bench), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.Selected.class);
        StrikeSelectionResult.Selected selected = (StrikeSelectionResult.Selected) result;
        assertThat(selected.selection().chosen().symbol()).isEqualTo(chosen.symbol());
        assertThat(selected.selection().rejected())
                .extracting(OptionContract::symbol)
                .contains(bench.symbol());
        assertThat(selected.selection().reason()).contains("atm_band_distance=").contains("spread_pct=");
    }

    @Test
    void putSideSelectionWorks() {
        OptionContract p = liquidPut("SPY", TODAY, "600", "1.05", "1.10");
        StrikeSelectionResult result = selector.select(SPOT, OptionSide.PUT, List.of(p), TODAY);

        assertThat(result).isInstanceOf(StrikeSelectionResult.Selected.class);
        assertThat(((StrikeSelectionResult.Selected) result)
                        .selection()
                        .chosen()
                        .side())
                .isEqualTo(OptionSide.PUT);
    }

    // -- builders --

    private static OptionContract liquidCall(String und, LocalDate exp, String strike, String bid, String ask) {
        return call(und, exp, strike, bid, ask, 500);
    }

    private static OptionContract liquidPut(String und, LocalDate exp, String strike, String bid, String ask) {
        return put(und, exp, strike, bid, ask, 500);
    }

    private static OptionContract call(String und, LocalDate exp, String strike, String bid, String ask, int oi) {
        return contract(und, exp, strike, OptionSide.CALL, bid, ask, oi);
    }

    private static OptionContract put(String und, LocalDate exp, String strike, String bid, String ask, int oi) {
        return contract(und, exp, strike, OptionSide.PUT, bid, ask, oi);
    }

    private static OptionContract contract(
            String und, LocalDate exp, String strike, OptionSide side, String bid, String ask, int oi) {
        // Build a synthetic OCC symbol for round-trip realism in test names.
        String symbol = String.format(
                "%s%02d%02d%02d%s%08d",
                und,
                exp.getYear() - 2000,
                exp.getMonthValue(),
                exp.getDayOfMonth(),
                side == OptionSide.CALL ? "C" : "P",
                new BigDecimal(strike).multiply(BigDecimal.valueOf(1000)).intValueExact());
        return new OptionContract(
                symbol,
                und,
                exp,
                new BigDecimal(strike),
                side,
                new BigDecimal(bid),
                new BigDecimal(ask),
                Optional.of(oi),
                Optional.of(1000),
                Optional.empty(),
                Optional.empty());
    }
}
