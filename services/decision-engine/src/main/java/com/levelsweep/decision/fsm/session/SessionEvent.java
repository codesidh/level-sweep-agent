package com.levelsweep.decision.fsm.session;

/**
 * Events that drive the {@link SessionState} machine.
 *
 * <ul>
 *   <li>{@link #LEVELS_READY} — fired at 09:29:30 ET when reference levels are
 *       computed (requirements.md §4).
 *   <li>{@link #MARKET_OPEN} — fired at 09:30:00 ET (RTH start).
 *   <li>{@link #EOD_TRIGGER} — fired at 15:55:00 ET hard close
 *       (requirements.md §14).
 *   <li>{@link #MARKET_CLOSE} — fired at 16:00:00 ET (RTH end).
 *   <li>{@link #NEWS_BLACKOUT_START} — economic-calendar window opens
 *       (requirements.md §12).
 *   <li>{@link #NEWS_BLACKOUT_END} — blackout clears; the FSM treats this as a
 *       transition with no deterministic destination (the surrounding service
 *       restores the prior state).
 * </ul>
 */
public enum SessionEvent {
    LEVELS_READY,
    MARKET_OPEN,
    EOD_TRIGGER,
    MARKET_CLOSE,
    NEWS_BLACKOUT_START,
    NEWS_BLACKOUT_END
}
