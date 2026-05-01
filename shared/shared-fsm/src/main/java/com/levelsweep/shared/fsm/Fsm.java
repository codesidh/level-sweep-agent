package com.levelsweep.shared.fsm;

import java.util.Optional;

/**
 * Pure deterministic state-machine contract.
 *
 * <p>Per architecture-spec §10 every lifecycle (Session, Risk, Trade, Order, Position,
 * Connection) is an explicit FSM. {@code Fsm} captures the canonical reduce step:
 * {@code (state, event) -> Optional<state>} with no side effects, no clock, and no IO.
 * Empty optional means "invalid transition for this state". Concrete services compose
 * the FSM with a {@link FsmTransition} repository so every accepted transition is
 * persisted alongside its {@link #fsmVersion()} for replay-compatibility per §13.1.
 *
 * <p>Implementations must be stateless (one bean) — instance-state belongs to the
 * surrounding service / driver that owns the per-tenant {@code currentState} map.
 *
 * @param <S> the state type (typically an enum)
 * @param <E> the event type (typically an enum)
 */
public interface Fsm<S, E> {

    /**
     * The schema version of this FSM. Bumped whenever the legal state/event set or the
     * transition table changes in a way that breaks bar-for-bar replay of historical
     * {@code fsm_transitions} rows.
     */
    int fsmVersion();

    /**
     * The {@code fsm_kind} discriminator persisted into {@code fsm_transitions} —
     * e.g. {@code "SESSION"}, {@code "TRADE"}, {@code "RISK"}.
     */
    String fsmKind();

    /**
     * Reduce step. Returns the next state, or {@link Optional#empty()} if the event is
     * not legal in the supplied {@code currentState}. Pure: no IO, no clock, no
     * mutation.
     */
    Optional<S> next(S currentState, E event);
}
