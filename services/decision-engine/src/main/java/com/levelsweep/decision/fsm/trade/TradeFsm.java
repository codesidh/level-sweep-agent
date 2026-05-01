package com.levelsweep.decision.fsm.trade;

import com.levelsweep.shared.fsm.Fsm;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * Pure {@link Fsm} for a single trade lifecycle. Stateless / replay-deterministic —
 * no clock, no IO. The surrounding {@code TradeService} (and Phase 3 Trade Saga)
 * carries the per-trade current state and persists transitions through the
 * {@code FsmTransitionRepository}.
 *
 * <p>{@link TradeEvent#ERROR} is the universal failure edge — any non-terminal state
 * can land in {@link TradeState#FAILED}. Stop-loss and profit-target both move
 * {@link TradeState#ACTIVE} into {@link TradeState#EXITING}; the divergence on
 * exit-reason is captured in the persisted {@code event} column, not in the state
 * graph.
 */
@ApplicationScoped
public class TradeFsm implements Fsm<TradeState, TradeEvent> {

    public static final int VERSION = 1;

    public static final String KIND = "TRADE";

    @Override
    public int fsmVersion() {
        return VERSION;
    }

    @Override
    public String fsmKind() {
        return KIND;
    }

    @Override
    public Optional<TradeState> next(TradeState currentState, TradeEvent event) {
        if (currentState == null || event == null) {
            return Optional.empty();
        }
        // Universal error edge — any non-terminal state -> FAILED.
        if (event == TradeEvent.ERROR) {
            if (currentState == TradeState.CLOSED || currentState == TradeState.FAILED) {
                return Optional.empty();
            }
            return Optional.of(TradeState.FAILED);
        }
        return switch (currentState) {
            case PROPOSED -> event == TradeEvent.RISK_APPROVED
                    ? Optional.of(TradeState.ENTERED)
                    : Optional.empty();
            case ENTERED -> event == TradeEvent.FILL_CONFIRMED
                    ? Optional.of(TradeState.ACTIVE)
                    : Optional.empty();
            case ACTIVE -> switch (event) {
                case STOP_HIT, PROFIT_TARGET_HIT, EOD_FLATTEN -> Optional.of(TradeState.EXITING);
                default -> Optional.empty();
            };
            case EXITING -> event == TradeEvent.EXIT_FILL_CONFIRMED
                    ? Optional.of(TradeState.CLOSED)
                    : Optional.empty();
            case CLOSED, FAILED -> Optional.empty();
        };
    }
}
