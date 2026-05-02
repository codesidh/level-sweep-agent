package com.levelsweep.aiagent.cost;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import java.math.BigDecimal;

/**
 * Thrown internally by {@link DailyCostTracker} (or assembled directly) when
 * a pre-flight cap check determines that a projected Anthropic call would
 * push a (tenant, role, day) bucket over its configured cap.
 *
 * <p>{@code AnthropicClient} catches this and surfaces it as
 * {@link com.levelsweep.aiagent.anthropic.AnthropicResponse.CostCapBreached}
 * to the caller — no HTTP call is made (architecture-spec §4.9 +
 * {@code ai-prompt-management} skill rule #4 pre-flight cap).
 */
public class CostCapBreachException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String tenantId;
    private final Role role;
    private final BigDecimal capUsd;
    private final BigDecimal currentSpendUsd;
    private final BigDecimal projectedCallCostUsd;

    public CostCapBreachException(
            String tenantId,
            Role role,
            BigDecimal capUsd,
            BigDecimal currentSpendUsd,
            BigDecimal projectedCallCostUsd) {
        super(String.format(
                "ai cost cap breached: tenantId=%s role=%s capUsd=%s currentSpendUsd=%s projectedCallCostUsd=%s",
                tenantId, role, capUsd, currentSpendUsd, projectedCallCostUsd));
        this.tenantId = tenantId;
        this.role = role;
        this.capUsd = capUsd;
        this.currentSpendUsd = currentSpendUsd;
        this.projectedCallCostUsd = projectedCallCostUsd;
    }

    public String tenantId() {
        return tenantId;
    }

    public Role role() {
        return role;
    }

    public BigDecimal capUsd() {
        return capUsd;
    }

    public BigDecimal currentSpendUsd() {
        return currentSpendUsd;
    }

    public BigDecimal projectedCallCostUsd() {
        return projectedCallCostUsd;
    }
}
