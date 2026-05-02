package com.levelsweep.execution.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.trade.OrderRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * POJO unit tests for {@link OrderPricing}. The strategy-relevant cases:
 *
 * <ul>
 *   <li>typical 0DTE quote (bid 1.20 / ask 1.25) — mid 1.225 → BUY 1.24, SELL 1.21
 *   <li>quote where mid is already a multiple of 0.01 — BUY adds 0.01, SELL subtracts
 *   <li>tight 1-cent spread — must still produce a valid 2-decimal limit
 *   <li>wide spread — verifies no overflow / unexpected rounding
 *   <li>SELL side delegation
 *   <li>negative-input validation
 * </ul>
 */
class OrderPricingTest {

    @Test
    void typicalNbboBuySideAddsPennyToMid() {
        BigDecimal limit = OrderPricing.limitPriceFromNbbo(
                new BigDecimal("1.20"), new BigDecimal("1.25"), OrderRequest.SIDE_BUY);
        // mid = 1.225, +0.01 = 1.235, HALF_UP → 1.24
        assertThat(limit).isEqualByComparingTo("1.24");
    }

    @Test
    void typicalNbboSellSideSubtractsPennyFromMid() {
        BigDecimal limit = OrderPricing.limitPriceFromNbbo(
                new BigDecimal("1.20"), new BigDecimal("1.25"), OrderRequest.SIDE_SELL);
        // mid = 1.225, -0.01 = 1.215, HALF_UP → 1.22
        assertThat(limit).isEqualByComparingTo("1.22");
    }

    @Test
    void midAlreadyOnPennyBoundaryBuyAddsPenny() {
        BigDecimal limit = OrderPricing.limitPriceFromNbbo(
                new BigDecimal("1.40"), new BigDecimal("1.50"), OrderRequest.SIDE_BUY);
        // mid = 1.45, +0.01 = 1.46
        assertThat(limit).isEqualByComparingTo("1.46");
    }

    @Test
    void midAlreadyOnPennyBoundarySellSubtractsPenny() {
        BigDecimal limit = OrderPricing.limitPriceFromNbbo(
                new BigDecimal("1.40"), new BigDecimal("1.50"), OrderRequest.SIDE_SELL);
        // mid = 1.45, -0.01 = 1.44
        assertThat(limit).isEqualByComparingTo("1.44");
    }

    @Test
    void tightOneCentSpreadProducesValidLimit() {
        BigDecimal limit = OrderPricing.limitPriceFromNbbo(
                new BigDecimal("0.50"), new BigDecimal("0.51"), OrderRequest.SIDE_BUY);
        // mid = 0.505, +0.01 = 0.515, HALF_UP → 0.52
        assertThat(limit).isEqualByComparingTo("0.52");
    }

    @Test
    void wideSpreadStillProducesTwoDecimalLimit() {
        BigDecimal limit = OrderPricing.limitPriceFromNbbo(
                new BigDecimal("2.00"), new BigDecimal("4.00"), OrderRequest.SIDE_BUY);
        // mid = 3.00, +0.01 = 3.01
        assertThat(limit).isEqualByComparingTo("3.01");
        assertThat(limit.scale()).isEqualTo(2);
    }

    @Test
    void sellOnNearZeroQuoteIsFlooredToPenny() {
        // bid=0.00, ask=0.01 — mid 0.005, sell would compute 0.005 - 0.01 = -0.005.
        // The helper floors to 0.01 rather than emitting a negative limit.
        BigDecimal limit = OrderPricing.limitPriceFromNbbo(
                new BigDecimal("0.00"), new BigDecimal("0.01"), OrderRequest.SIDE_SELL);
        assertThat(limit).isEqualByComparingTo("0.01");
    }

    @Test
    void resultIsAlwaysScaleTwo() {
        // Mid = 1.235; BUY → 1.245 → HALF_UP → 1.25
        BigDecimal limit = OrderPricing.limitPriceFromNbbo(
                new BigDecimal("1.22"), new BigDecimal("1.25"), OrderRequest.SIDE_BUY);
        assertThat(limit.scale()).isEqualTo(2);
        assertThat(limit).isEqualByComparingTo("1.25");
    }

    @Test
    void rejectsNegativeBid() {
        assertThatThrownBy(() -> OrderPricing.limitPriceFromNbbo(
                        new BigDecimal("-0.01"), new BigDecimal("1.00"), OrderRequest.SIDE_BUY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bid");
    }

    @Test
    void rejectsAskBelowBid() {
        assertThatThrownBy(() -> OrderPricing.limitPriceFromNbbo(
                        new BigDecimal("1.50"), new BigDecimal("1.20"), OrderRequest.SIDE_BUY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ask");
    }

    @Test
    void rejectsUnknownSide() {
        assertThatThrownBy(() -> OrderPricing.limitPriceFromNbbo(
                        new BigDecimal("1.00"), new BigDecimal("1.10"), "hold"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("side");
    }
}
