package com.levelsweep.shared.domain.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradeExitOrderSubmittedTest {

    private static final Instant TS = Instant.parse("2026-04-30T15:00:00Z");

    @Test
    void canConstructWithStopExitReason() {
        TradeExitOrderSubmitted evt = new TradeExitOrderSubmitted(
                "OWNER",
                "t1",
                "corr-1",
                "SPY260430C00595000",
                1,
                "alpaca-2",
                "OWNER:t1:exit",
                "accepted",
                TS,
                TradeExitOrderSubmitted.EXIT_REASON_STOP);
        assertThat(evt.exitReason()).isEqualTo("STOP");
    }

    @Test
    void canConstructWithTrailExitReason() {
        TradeExitOrderSubmitted evt = new TradeExitOrderSubmitted(
                "OWNER",
                "t1",
                "corr-1",
                "SPY260430C00595000",
                1,
                "alpaca-2",
                "OWNER:t1:exit",
                "accepted",
                TS,
                TradeExitOrderSubmitted.EXIT_REASON_TRAIL);
        assertThat(evt.exitReason()).isEqualTo("TRAIL");
    }

    @Test
    void rejectsUnknownExitReason() {
        assertThatThrownBy(() -> new TradeExitOrderSubmitted(
                        "OWNER",
                        "t1",
                        "corr-1",
                        "SPY260430C00595000",
                        1,
                        "alpaca-2",
                        "OWNER:t1:exit",
                        "accepted",
                        TS,
                        "MANUAL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroQuantity() {
        assertThatThrownBy(() -> new TradeExitOrderSubmitted(
                        "OWNER",
                        "t1",
                        "corr-1",
                        "SPY260430C00595000",
                        0,
                        "alpaca-2",
                        "OWNER:t1:exit",
                        "accepted",
                        TS,
                        TradeExitOrderSubmitted.EXIT_REASON_STOP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
