package com.levelsweep.execution.trail;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.trade.TradeEodFlattened;
import com.levelsweep.shared.domain.trade.TradeFilled;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrailManagerServiceTest {

    private static final Instant FILL_AT = Instant.parse("2026-04-30T13:30:00Z");

    private static TradeFilled fill(String tradeId, BigDecimal premium) {
        return new TradeFilled(
                "OWNER",
                tradeId,
                "alpaca-" + tradeId,
                "SPY260430C00595000",
                premium,
                1,
                "filled",
                FILL_AT,
                "corr-" + tradeId);
    }

    @Test
    void onTradeFilledRegistersTrailState() {
        TrailRegistry reg = new TrailRegistry();
        TrailManagerService svc = new TrailManagerService(reg);

        svc.onTradeFilled(fill("t1", new BigDecimal("1.20")));

        assertThat(reg.size()).isEqualTo(1);
        TrailState s = reg.snapshot().iterator().next();
        assertThat(s.tradeId()).isEqualTo("t1");
        assertThat(s.entryPremium()).isEqualByComparingTo("1.20");
    }

    @Test
    void onTradeStopTriggeredDeregisters() {
        TrailRegistry reg = new TrailRegistry();
        TrailManagerService svc = new TrailManagerService(reg);
        svc.onTradeFilled(fill("t1", new BigDecimal("1.20")));

        svc.onTradeStopTriggered(new TradeStopTriggered(
                "OWNER",
                "t1",
                "alpaca-t1",
                "SPY260430C00595000",
                Instant.parse("2026-04-30T14:00:00Z"),
                BigDecimal.ONE,
                "EMA13",
                Instant.parse("2026-04-30T14:00:00.250Z"),
                "corr-t1"));

        assertThat(reg.size()).isZero();
    }

    @Test
    void onTradeTrailBreachedDeregisters() {
        TrailRegistry reg = new TrailRegistry();
        TrailManagerService svc = new TrailManagerService(reg);
        svc.onTradeFilled(fill("t1", new BigDecimal("1.20")));

        svc.onTradeTrailBreached(new TradeTrailBreached(
                "OWNER",
                "t1",
                "SPY260430C00595000",
                Instant.parse("2026-04-30T15:00:00Z"),
                BigDecimal.ONE,
                new BigDecimal("0.35"),
                "corr-t1"));

        assertThat(reg.size()).isZero();
    }

    @Test
    void onTradeEodFlattenedDeregisters() {
        TrailRegistry reg = new TrailRegistry();
        TrailManagerService svc = new TrailManagerService(reg);
        svc.onTradeFilled(fill("t1", new BigDecimal("1.20")));

        svc.onTradeEodFlattened(new TradeEodFlattened(
                "OWNER", "t1", "alpaca-flat-1", Instant.parse("2026-04-30T19:55:00Z"), "corr-t1"));

        assertThat(reg.size()).isZero();
    }

    @Test
    void zeroEntryPremiumIsSkipped() {
        TrailRegistry reg = new TrailRegistry();
        TrailManagerService svc = new TrailManagerService(reg);

        svc.onTradeFilled(fill("t1", BigDecimal.ZERO));

        assertThat(reg.size()).isZero();
    }
}
