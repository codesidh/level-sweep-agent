package com.levelsweep.shared.domain.options;

import java.util.Objects;

/**
 * Sealed result type for the 0DTE strike selector. Either a {@link Selected}
 * with the chosen contract + debug context, or a {@link NoCandidates}
 * sentinel carrying a stable machine-friendly reason code (e.g.
 * {@code "no_strike_passed_liquidity"}, {@code "empty_chain"},
 * {@code "no_atm_band_match"}).
 *
 * <p>Modeled as a sealed interface so downstream consumers (the Trade Saga
 * in S6) can pattern-match exhaustively without resorting to a nullable
 * return.
 */
public sealed interface StrikeSelectionResult {

    /** Wraps a successful {@link StrikeSelection}. */
    record Selected(StrikeSelection selection) implements StrikeSelectionResult {
        public Selected {
            Objects.requireNonNull(selection, "selection");
        }
    }

    /**
     * Indicates the selector could not pick any contract. The
     * {@code reasonCode} is a stable enum-like string suitable for logging
     * and metrics labels; a free-form description belongs in the log.
     */
    record NoCandidates(String reasonCode) implements StrikeSelectionResult {
        public NoCandidates {
            Objects.requireNonNull(reasonCode, "reasonCode");
            if (reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must not be blank");
            }
        }
    }
}
