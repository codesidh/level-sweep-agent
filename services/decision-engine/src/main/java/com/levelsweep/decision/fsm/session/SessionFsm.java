package com.levelsweep.decision.fsm.session;

import com.levelsweep.shared.fsm.Fsm;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * Pure {@link Fsm} for the trading-session lifecycle. Stateless / replay-deterministic
 * — no clock, no IO. The surrounding {@code SessionService} owns the per-tenant
 * current state, persists transitions via the {@code FsmTransitionRepository}, and is
 * the only thing aware of the wall clock that drives {@link SessionEvent} emission.
 *
 * <p>{@link SessionEvent#NEWS_BLACKOUT_END} deliberately resolves to
 * {@link Optional#empty()} from this pure reducer: the resume target depends on the
 * pre-blackout state which the FSM cannot see (the contract is a single-arg state).
 * The service keeps that pre-blackout state outside the FSM and applies it directly
 * when blackout clears.
 */
@ApplicationScoped
public class SessionFsm implements Fsm<SessionState, SessionEvent> {

    /**
     * Bumped whenever the legal transition table changes in a way that breaks replay
     * of historical {@code fsm_transitions} rows.
     */
    public static final int VERSION = 1;

    public static final String KIND = "SESSION";

    @Override
    public int fsmVersion() {
        return VERSION;
    }

    @Override
    public String fsmKind() {
        return KIND;
    }

    @Override
    public Optional<SessionState> next(SessionState currentState, SessionEvent event) {
        if (currentState == null || event == null) {
            return Optional.empty();
        }
        // BLACKOUT can be entered from any non-terminal state. CLOSED is terminal —
        // a stray late-day news event must NOT pull a closed session back into
        // BLACKOUT (otherwise replay-from-CLOSED would diverge).
        if (event == SessionEvent.NEWS_BLACKOUT_START) {
            if (currentState == SessionState.CLOSED || currentState == SessionState.BLACKOUT) {
                return Optional.empty();
            }
            return Optional.of(SessionState.BLACKOUT);
        }
        // Resume target is owned by the service; pure FSM cannot decide here.
        if (event == SessionEvent.NEWS_BLACKOUT_END) {
            return Optional.empty();
        }
        return switch (currentState) {
            case PRE_MARKET -> event == SessionEvent.LEVELS_READY ? Optional.of(SessionState.ARMED) : Optional.empty();
            case ARMED -> event == SessionEvent.MARKET_OPEN ? Optional.of(SessionState.TRADING) : Optional.empty();
            case TRADING -> event == SessionEvent.EOD_TRIGGER ? Optional.of(SessionState.FLATTENING) : Optional.empty();
            case FLATTENING -> event == SessionEvent.MARKET_CLOSE ? Optional.of(SessionState.CLOSED) : Optional.empty();
            case CLOSED, BLACKOUT -> Optional.empty();
        };
    }
}
