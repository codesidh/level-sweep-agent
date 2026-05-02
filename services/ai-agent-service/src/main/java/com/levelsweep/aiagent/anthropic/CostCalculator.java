package com.levelsweep.aiagent.anthropic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/**
 * Pure helper that converts {@code (model, inputTokens, outputTokens, cachedTokens)}
 * into a USD cost. Applied both pre-flight (caller estimates with
 * {@code maxTokens} for the output side) and post-call (audit reconciliation
 * against {@code response.usage}).
 *
 * <p>Anthropic public pricing snapshot (2026, per architecture-spec §4.8):
 *
 * <table>
 *   <caption>Per-million-token rates</caption>
 *   <tr><th>Model</th><th>Input</th><th>Output</th></tr>
 *   <tr><td>claude-haiku-4-5</td><td>$1.00</td><td>$5.00</td></tr>
 *   <tr><td>claude-sonnet-4-6</td><td>$3.00</td><td>$15.00</td></tr>
 *   <tr><td>claude-opus-4-7</td><td>$15.00</td><td>$75.00</td></tr>
 * </table>
 *
 * <p><b>Cached input tokens</b> are billed at 10% of base input rate (Anthropic
 * prompt-caching read pricing). Tokens that hit cache are passed in
 * {@code cachedTokens} and DEDUCTED from {@code inputTokens} by the caller
 * before invoking; this helper bills cached tokens at the discounted rate and
 * the remaining (uncached) input tokens at the full rate.
 *
 * <p><b>Rounding</b>: results rounded HALF_UP at 4 decimal places per
 * architecture-spec §4.10. Anthropic itself reports usage as integer tokens,
 * but the per-token math produces fractional pennies that need a stable scale
 * for audit reconciliation.
 *
 * <p>Pricing changes require an ADR update (ADR-0006 §Decision: Anthropic API
 * surface is a moving target — bumping the model version or rates is
 * NOT a silent dependency upgrade).
 */
public final class CostCalculator {

    /** Million-token denominator. */
    private static final BigDecimal MTOK = new BigDecimal(1_000_000);

    /** Cached input tokens are 10% of base input rate. */
    private static final BigDecimal CACHE_DISCOUNT = new BigDecimal("0.10");

    /** Audit + cap math runs at 4 decimal places HALF_UP per architecture-spec §4.10. */
    public static final int SCALE = 4;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * Per-model pricing table. Keys match the {@code anthropic.models.*}
     * config values from {@code application.yml}. New models added via
     * an ADR — never as a silent code change.
     */
    private static final Map<String, ModelPricing> PRICES = Map.of(
            "claude-haiku-4-5", new ModelPricing(new BigDecimal("1.00"), new BigDecimal("5.00")),
            "claude-sonnet-4-6", new ModelPricing(new BigDecimal("3.00"), new BigDecimal("15.00")),
            "claude-opus-4-7", new ModelPricing(new BigDecimal("15.00"), new BigDecimal("75.00")));

    private CostCalculator() {
        // utility
    }

    /**
     * Compute USD cost for one Anthropic call. All token counts are
     * non-negative; {@code cachedTokens} is a subset of total input tokens
     * (caller-side accounting — see Javadoc above).
     *
     * @param model model id (must be in the pricing table)
     * @param inputTokens uncached input tokens (post-cache deduction)
     * @param outputTokens completion tokens
     * @param cachedTokens cache-hit input tokens (billed at 10%)
     * @return USD cost rounded to {@link #SCALE} decimal places HALF_UP
     */
    public static BigDecimal compute(String model, int inputTokens, int outputTokens, int cachedTokens) {
        Objects.requireNonNull(model, "model");
        if (inputTokens < 0 || outputTokens < 0 || cachedTokens < 0) {
            throw new IllegalArgumentException("token counts must be non-negative");
        }
        ModelPricing p = PRICES.get(model);
        if (p == null) {
            throw new IllegalArgumentException("unknown model for pricing: " + model);
        }
        // input cost: (uncached × inRate + cached × inRate × 0.10) / 1_000_000
        BigDecimal uncachedCost = p.inputPerMTok.multiply(BigDecimal.valueOf(inputTokens));
        BigDecimal cachedCost = p.inputPerMTok.multiply(CACHE_DISCOUNT).multiply(BigDecimal.valueOf(cachedTokens));
        BigDecimal outputCost = p.outputPerMTok.multiply(BigDecimal.valueOf(outputTokens));
        BigDecimal totalNumerator = uncachedCost.add(cachedCost).add(outputCost);
        return totalNumerator.divide(MTOK, SCALE, ROUNDING);
    }

    /** {@code true} if the calculator knows how to bill the given model. */
    public static boolean knowsModel(String model) {
        return PRICES.containsKey(model);
    }

    private record ModelPricing(BigDecimal inputPerMTok, BigDecimal outputPerMTok) {}
}
