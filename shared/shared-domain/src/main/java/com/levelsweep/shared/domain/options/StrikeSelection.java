package com.levelsweep.shared.domain.options;

import java.util.List;
import java.util.Objects;

/**
 * Successful outcome of {@code StrikeSelector#select}: the chosen contract,
 * a human-readable {@code reason} string identifying which rule picked it,
 * and the list of candidates that were considered but failed liquidity
 * checks (kept for observability — useful when the chosen strike's spread
 * is borderline and an operator wants to see what was on the bench).
 */
public record StrikeSelection(OptionContract chosen, String reason, List<OptionContract> rejected) {

    public StrikeSelection {
        Objects.requireNonNull(chosen, "chosen");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(rejected, "rejected");
        rejected = List.copyOf(rejected);
    }
}
