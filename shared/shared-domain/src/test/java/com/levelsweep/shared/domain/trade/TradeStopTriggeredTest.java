package com.levelsweep.shared.domain.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradeStopTriggeredTest {

    private static final Instant BAR_TS = Instant.parse("2026-04-30T14:30:00Z");
    private static final Instant TRIGGERED = Instant.parse("2026-04-30T14:30:00.250Z");

    @Test
    void canConstructWithEma13Reference() {
        TradeStopTriggered evt = new TradeStopTriggered(
                "OWNER",
                "t1",
                "alpaca-1",
                "SPY260430C00595000",
                BAR_TS,
                new BigDecimal("1.20"),
                "EMA13",
                TRIGGERED,
                "corr-1");
        assertThat(evt.stopReference()).isEqualTo(TradeStopTriggered.STOP_REF_EMA13);
    }

    @Test
    void canConstructWithEma48Reference() {
        TradeStopTriggered evt = new TradeStopTriggered(
                "OWNER",
                "t1",
                "alpaca-1",
                "SPY260430C00595000",
                BAR_TS,
                new BigDecimal("1.20"),
                "EMA48",
                TRIGGERED,
                "corr-1");
        assertThat(evt.stopReference()).isEqualTo(TradeStopTriggered.STOP_REF_EMA48);
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertThatThrownBy(() -> new TradeStopTriggered(
                        "",
                        "t1",
                        "alpaca-1",
                        "SPY260430C00595000",
                        BAR_TS,
                        BigDecimal.ONE,
                        "EMA13",
                        TRIGGERED,
                        "corr-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownStopReference() {
        assertThatThrownBy(() -> new TradeStopTriggered(
                        "OWNER",
                        "t1",
                        "alpaca-1",
                        "SPY260430C00595000",
                        BAR_TS,
                        BigDecimal.ONE,
                        "RSI",
                        TRIGGERED,
                        "corr-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EMA13");
    }
}
