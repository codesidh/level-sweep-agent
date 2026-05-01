package com.levelsweep.shared.domain.signal;

/**
 * The directional verdict of a {@link SignalEvaluation}.
 *
 * <ul>
 *   <li>{@link #ENTER_LONG} — sweep + bullish EMA stack confirmed; CALL setup
 *   <li>{@link #ENTER_SHORT} — sweep + bearish EMA stack confirmed; PUT setup
 *   <li>{@link #SKIP} — at least one gate failed; see
 *       {@link SignalEvaluation#reasons()} for the audit trail
 * </ul>
 *
 * <p>Per {@code requirements.md} §6 / §8 — the strategy is binary CALL or PUT
 * on 0DTE SPY options, so two enter actions cover the directional space and
 * SKIP captures every no-trade outcome.
 */
public enum SignalAction {
    ENTER_LONG,
    ENTER_SHORT,
    SKIP
}
