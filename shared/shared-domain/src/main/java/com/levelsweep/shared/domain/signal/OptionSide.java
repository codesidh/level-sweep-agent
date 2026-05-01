package com.levelsweep.shared.domain.signal;

/**
 * 0DTE option side, mapped 1:1 from a directional {@link SignalAction}:
 *
 * <ul>
 *   <li>{@link SignalAction#ENTER_LONG} → {@link #CALL}
 *   <li>{@link SignalAction#ENTER_SHORT} → {@link #PUT}
 * </ul>
 *
 * <p>Per {@code requirements.md} §3 — the agent only trades single-leg 0DTE
 * SPY options; multi-leg strategies are out of scope (§17).
 *
 * <p><b>Merge coordination</b>: a parallel branch (Strike Selector, S4) also
 * introduces an {@code OptionSide} enum under
 * {@code com.levelsweep.shared.domain.options} with the same shape. Both
 * branches diverged from {@code main} concurrently. When both PRs land the
 * duplicate should be reconciled at merge time — pick one canonical home and
 * re-point imports. Until then, this Signal Engine branch is self-contained
 * and does not depend on the Strike branch's enum.
 */
public enum OptionSide {
    CALL,
    PUT
}
