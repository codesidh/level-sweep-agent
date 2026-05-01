package com.levelsweep.shared.domain.risk;

/**
 * Type discriminator for {@link RiskEvent}. Persisted verbatim into
 * {@code risk_events.event_type} (VARCHAR(32)) for the append-only budget log
 * (architecture-spec §13.1).
 *
 * <ul>
 *   <li>{@link #BUDGET_CONSUMED} — a fill realized a P&L delta against the
 *       daily budget. {@link RiskEvent#deltaAmount()} is the consumption (≥0).
 *   <li>{@link #HALT_TRIGGERED} — Risk FSM forced into {@link RiskState#HALTED}
 *       because a halt threshold (loss / max-trades / news / manual) was met.
 *   <li>{@link #STATE_TRANSITION} — any FSM state change. Carries
 *       {@link RiskEvent#fromState()} and {@link RiskEvent#toState()}.
 *   <li>{@link #DAILY_RESET} — emitted at 09:29 ET when the FSM resets to
 *       HEALTHY for the new trading session.
 * </ul>
 */
public enum RiskEventType {
    BUDGET_CONSUMED,
    HALT_TRIGGERED,
    STATE_TRANSITION,
    DAILY_RESET
}
