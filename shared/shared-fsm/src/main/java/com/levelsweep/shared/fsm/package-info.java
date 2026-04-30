/**
 * FSM abstract base + persistence pattern.
 *
 * <p>Per architecture-spec §10 every lifecycle (Session, Risk, Trade, Order, Position,
 * Connection) is an explicit FSM. Transitions are append-only events that double as
 * audit trail; persisted to MS SQL on every transition.
 *
 * <p>Phase 0 placeholder. Concrete state-machine base classes and persistence helpers
 * are added in Phase 2 alongside the Decision Engine FSMs.
 */
package com.levelsweep.shared.fsm;
