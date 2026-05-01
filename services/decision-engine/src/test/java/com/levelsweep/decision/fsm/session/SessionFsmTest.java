package com.levelsweep.decision.fsm.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Validates every legal and illegal transition of the {@link SessionFsm}. The
 * FSM is pure, so this is a plain JUnit test (no Quarkus, no Mockito).
 */
class SessionFsmTest {

    private final SessionFsm fsm = new SessionFsm();

    @Test
    void exposesKindAndVersion() {
        assertThat(fsm.fsmKind()).isEqualTo("SESSION");
        assertThat(fsm.fsmVersion()).isEqualTo(1);
    }

    @Test
    void preMarketLevelsReadyArmsTheSession() {
        assertThat(fsm.next(SessionState.PRE_MARKET, SessionEvent.LEVELS_READY)).contains(SessionState.ARMED);
    }

    @Test
    void armedMarketOpenStartsTrading() {
        assertThat(fsm.next(SessionState.ARMED, SessionEvent.MARKET_OPEN)).contains(SessionState.TRADING);
    }

    @Test
    void tradingEodTriggerEntersFlattening() {
        assertThat(fsm.next(SessionState.TRADING, SessionEvent.EOD_TRIGGER)).contains(SessionState.FLATTENING);
    }

    @Test
    void flatteningMarketCloseClosesTheSession() {
        assertThat(fsm.next(SessionState.FLATTENING, SessionEvent.MARKET_CLOSE)).contains(SessionState.CLOSED);
    }

    @Test
    void blackoutStartFromAnyNonTerminalState() {
        assertThat(fsm.next(SessionState.PRE_MARKET, SessionEvent.NEWS_BLACKOUT_START))
                .contains(SessionState.BLACKOUT);
        assertThat(fsm.next(SessionState.ARMED, SessionEvent.NEWS_BLACKOUT_START))
                .contains(SessionState.BLACKOUT);
        assertThat(fsm.next(SessionState.TRADING, SessionEvent.NEWS_BLACKOUT_START))
                .contains(SessionState.BLACKOUT);
        assertThat(fsm.next(SessionState.FLATTENING, SessionEvent.NEWS_BLACKOUT_START))
                .contains(SessionState.BLACKOUT);
    }

    @Test
    void blackoutStartFromClosedIsRejected() {
        assertThat(fsm.next(SessionState.CLOSED, SessionEvent.NEWS_BLACKOUT_START))
                .isEmpty();
    }

    @Test
    void blackoutStartWhileBlackoutIsRejected() {
        assertThat(fsm.next(SessionState.BLACKOUT, SessionEvent.NEWS_BLACKOUT_START))
                .isEmpty();
    }

    @Test
    void blackoutEndIsAlwaysEmptyFromPureFsm() {
        // The resume target is service-owned; the pure FSM cannot decide.
        for (SessionState s : SessionState.values()) {
            assertThat(fsm.next(s, SessionEvent.NEWS_BLACKOUT_END))
                    .as("blackout-end from %s", s)
                    .isEqualTo(Optional.empty());
        }
    }

    @Test
    void closedIsTerminal() {
        for (SessionEvent e : SessionEvent.values()) {
            assertThat(fsm.next(SessionState.CLOSED, e)).as("closed via %s", e).isEmpty();
        }
    }

    @Test
    void unrelatedEventsAreRejected() {
        assertThat(fsm.next(SessionState.PRE_MARKET, SessionEvent.MARKET_OPEN)).isEmpty();
        assertThat(fsm.next(SessionState.PRE_MARKET, SessionEvent.EOD_TRIGGER)).isEmpty();
        assertThat(fsm.next(SessionState.ARMED, SessionEvent.LEVELS_READY)).isEmpty();
        assertThat(fsm.next(SessionState.ARMED, SessionEvent.EOD_TRIGGER)).isEmpty();
        assertThat(fsm.next(SessionState.TRADING, SessionEvent.MARKET_OPEN)).isEmpty();
        assertThat(fsm.next(SessionState.TRADING, SessionEvent.LEVELS_READY)).isEmpty();
        assertThat(fsm.next(SessionState.FLATTENING, SessionEvent.EOD_TRIGGER)).isEmpty();
    }

    @Test
    void nullsAreRejected() {
        assertThat(fsm.next(null, SessionEvent.LEVELS_READY)).isEmpty();
        assertThat(fsm.next(SessionState.PRE_MARKET, null)).isEmpty();
    }
}
