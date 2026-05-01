package com.levelsweep.decision.fsm.trade;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Validates every legal and illegal transition of the {@link TradeFsm}. Pure JUnit
 * (no Quarkus, no Mockito) since the FSM has no dependencies.
 */
class TradeFsmTest {

    private final TradeFsm fsm = new TradeFsm();

    @Test
    void exposesKindAndVersion() {
        assertThat(fsm.fsmKind()).isEqualTo("TRADE");
        assertThat(fsm.fsmVersion()).isEqualTo(1);
    }

    @Test
    void proposedRiskApprovedEntersTheTrade() {
        assertThat(fsm.next(TradeState.PROPOSED, TradeEvent.RISK_APPROVED)).contains(TradeState.ENTERED);
    }

    @Test
    void enteredFillConfirmedActivates() {
        assertThat(fsm.next(TradeState.ENTERED, TradeEvent.FILL_CONFIRMED)).contains(TradeState.ACTIVE);
    }

    @Test
    void activeStopHitTransitionsToExiting() {
        assertThat(fsm.next(TradeState.ACTIVE, TradeEvent.STOP_HIT)).contains(TradeState.EXITING);
    }

    @Test
    void activeProfitTargetTransitionsToExiting() {
        assertThat(fsm.next(TradeState.ACTIVE, TradeEvent.PROFIT_TARGET_HIT)).contains(TradeState.EXITING);
    }

    @Test
    void activeEodFlattenTransitionsToExiting() {
        assertThat(fsm.next(TradeState.ACTIVE, TradeEvent.EOD_FLATTEN)).contains(TradeState.EXITING);
    }

    @Test
    void exitingExitFillConfirmedClosesTrade() {
        assertThat(fsm.next(TradeState.EXITING, TradeEvent.EXIT_FILL_CONFIRMED)).contains(TradeState.CLOSED);
    }

    @Test
    void errorFromAnyNonTerminalLandsInFailed() {
        assertThat(fsm.next(TradeState.PROPOSED, TradeEvent.ERROR)).contains(TradeState.FAILED);
        assertThat(fsm.next(TradeState.ENTERED, TradeEvent.ERROR)).contains(TradeState.FAILED);
        assertThat(fsm.next(TradeState.ACTIVE, TradeEvent.ERROR)).contains(TradeState.FAILED);
        assertThat(fsm.next(TradeState.EXITING, TradeEvent.ERROR)).contains(TradeState.FAILED);
    }

    @Test
    void errorFromTerminalStatesIsRejected() {
        assertThat(fsm.next(TradeState.CLOSED, TradeEvent.ERROR)).isEmpty();
        assertThat(fsm.next(TradeState.FAILED, TradeEvent.ERROR)).isEmpty();
    }

    @Test
    void closedAndFailedAreTerminal() {
        for (TradeEvent e : TradeEvent.values()) {
            assertThat(fsm.next(TradeState.CLOSED, e))
                    .as("closed via %s", e)
                    .isEmpty();
            assertThat(fsm.next(TradeState.FAILED, e))
                    .as("failed via %s", e)
                    .isEmpty();
        }
    }

    @Test
    void unrelatedEventsAreRejected() {
        assertThat(fsm.next(TradeState.PROPOSED, TradeEvent.FILL_CONFIRMED)).isEmpty();
        assertThat(fsm.next(TradeState.PROPOSED, TradeEvent.STOP_HIT)).isEmpty();
        assertThat(fsm.next(TradeState.ENTERED, TradeEvent.RISK_APPROVED)).isEmpty();
        assertThat(fsm.next(TradeState.ENTERED, TradeEvent.STOP_HIT)).isEmpty();
        assertThat(fsm.next(TradeState.ACTIVE, TradeEvent.RISK_APPROVED)).isEmpty();
        assertThat(fsm.next(TradeState.ACTIVE, TradeEvent.FILL_CONFIRMED)).isEmpty();
        assertThat(fsm.next(TradeState.EXITING, TradeEvent.STOP_HIT)).isEmpty();
        assertThat(fsm.next(TradeState.EXITING, TradeEvent.RISK_APPROVED)).isEmpty();
    }

    @Test
    void nullsAreRejected() {
        assertThat(fsm.next(null, TradeEvent.RISK_APPROVED)).isEmpty();
        assertThat(fsm.next(TradeState.PROPOSED, null)).isEmpty();
    }
}
