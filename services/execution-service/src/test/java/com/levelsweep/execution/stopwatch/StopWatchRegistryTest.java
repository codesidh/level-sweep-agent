package com.levelsweep.execution.stopwatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeFilled;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StopWatchRegistryTest {

    private static final Instant FILL_AT = Instant.parse("2026-04-30T13:30:00Z");

    private static TradeFilled filled(String tradeId, String contractSymbol) {
        return new TradeFilled(
                "OWNER",
                tradeId,
                "alpaca-" + tradeId,
                contractSymbol,
                new BigDecimal("1.20"),
                1,
                "filled",
                FILL_AT,
                "corr-" + tradeId);
    }

    @Test
    void registerOnTradeFilledThenDeregister() {
        StopWatchRegistry reg = new StopWatchRegistry();
        reg.onTradeFilled(filled("t1", "SPY260430C00595000"));
        assertThat(reg.size()).isEqualTo(1);
        StopWatchRegistry.RegisteredStop r = reg.snapshot().iterator().next();
        assertThat(r.side()).isEqualTo(OptionSide.CALL);
        assertThat(r.underlyingSymbol()).isEqualTo("SPY");
        assertThat(r.alpacaOrderId()).isEqualTo("alpaca-t1");

        reg.deregister("t1");
        assertThat(reg.size()).isZero();
    }

    @Test
    void parseSidePut() {
        StopWatchRegistry reg = new StopWatchRegistry();
        reg.onTradeFilled(filled("t1", "SPY260430P00590000"));
        assertThat(reg.snapshot().iterator().next().side()).isEqualTo(OptionSide.PUT);
    }

    @Test
    void duplicateTradeFilledReplaces() {
        StopWatchRegistry reg = new StopWatchRegistry();
        reg.onTradeFilled(filled("t1", "SPY260430C00595000"));
        reg.onTradeFilled(filled("t1", "SPY260430C00595000"));
        assertThat(reg.size()).isEqualTo(1);
    }

    @Test
    void deregisterUnknownIsNoOp() {
        StopWatchRegistry reg = new StopWatchRegistry();
        reg.deregister("ghost"); // must not throw
        assertThat(reg.size()).isZero();
    }

    @Test
    void unparseableSymbolSkipsRegistration() {
        StopWatchRegistry reg = new StopWatchRegistry();
        // Too short to parse — skip silently.
        TradeFilled bad = new TradeFilled(
                "OWNER", "t1", "alpaca-1", "BAD", new BigDecimal("1.20"), 1, "filled", FILL_AT, "corr-1");
        reg.onTradeFilled(bad);
        assertThat(reg.size()).isZero();
    }

    @Test
    void concurrentRegisterAndDeregisterIsThreadSafe() throws Exception {
        StopWatchRegistry reg = new StopWatchRegistry();
        ExecutorService exec = Executors.newFixedThreadPool(8);
        try {
            CompletableFuture<?>[] futures = new CompletableFuture[16];
            for (int i = 0; i < 16; i++) {
                final String id = "t" + i;
                futures[i] = CompletableFuture.runAsync(
                        () -> {
                            reg.onTradeFilled(filled(id, "SPY260430C00595000"));
                            reg.deregister(id);
                        },
                        exec);
            }
            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
            assertThat(reg.size()).isZero();
        } finally {
            exec.shutdown();
        }
    }
}
