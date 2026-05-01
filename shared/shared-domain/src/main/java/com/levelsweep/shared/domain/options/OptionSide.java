package com.levelsweep.shared.domain.options;

/**
 * 0DTE option side. The Phase 1 strategy trades single-leg CALL or PUT
 * contracts only — multi-leg structures are out of scope per
 * {@code requirements.md} §17.
 *
 * <p>Note for merge coordination: a parallel branch (Signal Engine, S2) may
 * also introduce an {@code OptionSide} enum under
 * {@code com.levelsweep.shared.domain.signal}. Both branches diverge from
 * {@code main}; the canonical home is here under
 * {@code shared-domain/options/}. If both PRs land, the duplication should
 * be resolved at merge time by deleting the {@code signal/} copy and
 * re-pointing imports here. The brief's leader has been notified in the PR
 * body.
 */
public enum OptionSide {
    CALL,
    PUT
}
