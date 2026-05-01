package com.levelsweep.shared.domain.options;

/**
 * 0DTE option side. The Phase 1 strategy trades single-leg CALL or PUT
 * contracts only — multi-leg structures are out of scope per
 * {@code requirements.md} §17.
 *
 * <p>Canonical home for the enum. The Signal Engine (S2) and Strike Selector
 * (S4) merged in parallel each introducing a copy; the Trade Saga (S6)
 * consolidated to this single location. Re-import from here.
 */
public enum OptionSide {
    CALL,
    PUT
}
