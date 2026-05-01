package com.levelsweep.decision.fsm.session;

/**
 * Session FSM states per requirements.md §3 (universe), §14 (EOD), §15 (data feed)
 * and architecture-spec §10. One instance of this FSM exists per (tenant, session
 * date).
 *
 * <ul>
 *   <li>{@link #PRE_MARKET} — boot state at midnight; ingesting overnight data.
 *   <li>{@link #ARMED} — entered at 09:29:30 ET when reference levels (PDH/PDL/
 *       PMH/PML) are computed and ready (requirements.md §4).
 *   <li>{@link #TRADING} — 09:30:00 ET; entry signals can be acted on.
 *   <li>{@link #FLATTENING} — 15:55:00 ET hard close (requirements.md §14);
 *       open positions are force-flattened.
 *   <li>{@link #CLOSED} — 16:00:00 ET; terminal until the next session.
 *   <li>{@link #BLACKOUT} — news event window (requirements.md §12); the previous
 *       state is held by the surrounding service so it can be resumed once the
 *       blackout clears.
 * </ul>
 */
public enum SessionState {
    PRE_MARKET,
    ARMED,
    TRADING,
    FLATTENING,
    CLOSED,
    BLACKOUT
}
