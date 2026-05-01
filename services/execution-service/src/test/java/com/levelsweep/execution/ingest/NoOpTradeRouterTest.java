package com.levelsweep.execution.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeProposed;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit tests for {@link NoOpTradeRouter}. Verifies the counter
 * increments monotonically on each {@code onTradeProposed} call. The
 * rate-limited INFO log line is observed indirectly via the counter; we don't
 * sample log output here because that's brittle and SLF4J already exercises
 * the format string.
 */
class NoOpTradeRouterTest {

    @Test
    void counterStartsAtZero() {
        NoOpTradeRouter router = new NoOpTradeRouter();
        assertThat(router.count()).isZero();
    }

    @Test
    void counterIncrementsOnEachEvent() {
        NoOpTradeRouter router = new NoOpTradeRouter();
        router.onTradeProposed(eventOf("trade-1"));
        router.onTradeProposed(eventOf("trade-2"));
        router.onTradeProposed(eventOf("trade-3"));
        assertThat(router.count()).isEqualTo(3L);
    }

    private static TradeProposed eventOf(String tradeId) {
        return new TradeProposed(
                "OWNER",
                tradeId,
                LocalDate.parse("2026-04-30"),
                Instant.parse("2026-04-30T13:32:00Z"),
                "SPY",
                OptionSide.CALL,
                "SPY260430C00595000",
                BigDecimal.valueOf(1.20),
                BigDecimal.valueOf(1.25),
                BigDecimal.valueOf(1.225),
                Optional.of(BigDecimal.valueOf(0.18)),
                Optional.of(BigDecimal.valueOf(0.50)),
                "corr-" + tradeId,
                List.of("pdh_sweep"));
    }
}
