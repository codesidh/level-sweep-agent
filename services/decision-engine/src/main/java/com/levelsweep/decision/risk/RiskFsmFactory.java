package com.levelsweep.decision.risk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.math.BigDecimal;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CDI producer for the {@link RiskFsm} singleton. The FSM itself is pure logic
 * (no CDI dependencies, no {@link java.time.Clock}) so it stays trivially unit-
 * testable; this factory bridges the configuration layer.
 *
 * <p>Configuration keys (all under {@code levelsweep.risk.*}):
 *
 * <ul>
 *   <li>{@code max-trades-per-day} — daily trade cap; default
 *       {@link RiskFsm#DEFAULT_MAX_TRADES_PER_DAY}. Caps the operator-discipline
 *       halt path (requirements.md §11 / §16).
 *   <li>{@code budget-low-fraction} — warn threshold as a fraction of the
 *       daily budget; default {@link RiskFsm#DEFAULT_BUDGET_LOW_FRACTION} (0.7).
 * </ul>
 */
@ApplicationScoped
public class RiskFsmFactory {

    @ConfigProperty(name = "levelsweep.risk.max-trades-per-day", defaultValue = "5")
    int maxTradesPerDay;

    @ConfigProperty(name = "levelsweep.risk.budget-low-fraction", defaultValue = "0.70")
    BigDecimal budgetLowFraction;

    @Produces
    @ApplicationScoped
    public RiskFsm riskFsm() {
        return new RiskFsm(maxTradesPerDay, budgetLowFraction);
    }
}
