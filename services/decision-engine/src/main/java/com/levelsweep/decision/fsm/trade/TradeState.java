package com.levelsweep.decision.fsm.trade;

/**
 * Trade FSM states per architecture-spec §10. One instance of this FSM exists per
 * proposed-and-accepted trade; the {@code tradeId} (UUID) is the FSM identity.
 *
 * <ul>
 *   <li>{@link #PROPOSED} — boot state; saga has emitted a candidate, Risk + Strike
 *       have not yet weighed in.
 *   <li>{@link #ENTERED} — risk approved, strike picked, entry order submitted.
 *   <li>{@link #ACTIVE} — Alpaca confirmed the entry fill (requirements.md §15
 *       account-state feed).
 *   <li>{@link #EXITING} — exit triggered (stop hit, profit target hit, or
 *       15:55 ET EOD flatten per requirements.md §14).
 *   <li>{@link #CLOSED} — exit fill confirmed; terminal.
 *   <li>{@link #FAILED} — error path; terminal. Saga compensations route through
 *       this state when entry/exit cannot complete.
 * </ul>
 */
public enum TradeState {
    PROPOSED,
    ENTERED,
    ACTIVE,
    EXITING,
    CLOSED,
    FAILED
}
