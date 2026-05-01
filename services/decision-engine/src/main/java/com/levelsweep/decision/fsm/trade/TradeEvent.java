package com.levelsweep.decision.fsm.trade;

/**
 * Events that drive the {@link TradeState} machine.
 *
 * <ul>
 *   <li>{@link #RISK_APPROVED} — Risk Manager (requirements.md §11) approves; the
 *       saga has picked a strike and submitted the entry order.
 *   <li>{@link #FILL_CONFIRMED} — Alpaca reports the entry fill.
 *   <li>{@link #STOP_HIT} — stop-loss triggered (requirements.md §9).
 *   <li>{@link #PROFIT_TARGET_HIT} — profit target / trailing stop triggered
 *       (requirements.md §10).
 *   <li>{@link #EOD_FLATTEN} — 15:55 ET EOD hard close (requirements.md §14).
 *   <li>{@link #EXIT_FILL_CONFIRMED} — Alpaca reports the exit fill.
 *   <li>{@link #ERROR} — any error path; lands the trade in {@link TradeState#FAILED}.
 * </ul>
 */
public enum TradeEvent {
    RISK_APPROVED,
    FILL_CONFIRMED,
    STOP_HIT,
    PROFIT_TARGET_HIT,
    EOD_FLATTEN,
    EXIT_FILL_CONFIRMED,
    ERROR
}
