package com.levelsweep.shared.domain.risk;

/**
 * Daily Risk FSM state per {@code requirements.md} §11.
 *
 * <ul>
 *   <li>{@link #HEALTHY} — cumulative realized loss is below 70% of the daily
 *       budget; new trades may enter freely.
 *   <li>{@link #BUDGET_LOW} — between 70% and 100% of the daily budget; new
 *       trades still fire (re-entry is allowed under §11.3 until the budget
 *       is breached) but operators receive a warning.
 *   <li>{@link #HALTED} — daily budget exhausted, max trades taken, or news
 *       blackout (§11.4 / §12). No new entries until the next session's
 *       09:29 ET reset.
 * </ul>
 *
 * <p>The state name is persisted verbatim into {@code daily_state.risk_state}
 * (VARCHAR(16)) and {@code risk_events.from_state}/{@code to_state}.
 */
public enum RiskState {
    HEALTHY,
    BUDGET_LOW,
    HALTED
}
