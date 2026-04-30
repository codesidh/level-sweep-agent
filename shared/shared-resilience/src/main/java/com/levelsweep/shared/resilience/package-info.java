/**
 * Resilience4j configuration helpers.
 *
 * <p>Per architecture-spec §17 every external dependency has explicit CB / bulkhead /
 * retry / time-limiter policies. This module centralizes the per-dependency profile
 * so each service only references a named profile (e.g. {@code "alpaca"}, {@code "polygon"},
 * {@code "anthropic"}, {@code "mssql"}) rather than re-declaring thresholds.
 *
 * <p>Phase 0 placeholder. Concrete profiles are added per service in Phase 7.
 */
package com.levelsweep.shared.resilience;
