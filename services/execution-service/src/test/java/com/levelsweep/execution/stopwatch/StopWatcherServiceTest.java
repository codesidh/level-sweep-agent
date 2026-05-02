package com.levelsweep.execution.stopwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.levelsweep.execution.persistence.StopAuditRepository;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.levelsweep.shared.domain.trade.TradeFilled;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import jakarta.enterprise.event.Event;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * End-to-end orchestration tests for {@link StopWatcherService}: bar +
 * indicator → trigger → audit + CDI fire + registry deregister.
 */
class StopWatcherServiceTest {

    private static final Instant FILL_AT = Instant.parse("2026-04-30T13:30:00Z");
    private static final Instant T2 = Instant.parse("2026-04-30T13:32:00Z");
    private static final Instant TRIGGER_AT = Instant.parse("2026-04-30T13:32:00.250Z");

    private StopWatchRegistry registry;
    private StopAuditRepository audit;
    private Event<TradeStopTriggered> stopEvent;
    private Clock clock;
    private StopWatcherService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new StopWatchRegistry();
        audit = Mockito.mock(StopAuditRepository.class);
        stopEvent = Mockito.mock(Event.class);
        clock = Clock.fixed(TRIGGER_AT, ZoneId.of("UTC"));
        service = new StopWatcherService(registry, audit, stopEvent, clock);
        service.init();
    }

    private static TradeFilled fill(String tradeId, String contractSymbol) {
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

    private static Bar bar(BigDecimal close) {
        return new Bar("SPY", Timeframe.TWO_MIN, T2.minusSeconds(120), T2, close, close, close, close, 0L, 0L);
    }

    private static IndicatorSnapshot ind(BigDecimal ema13, BigDecimal ema48, BigDecimal atr14) {
        return new IndicatorSnapshot("SPY", T2, ema13, ema48, BigDecimal.ZERO, atr14);
    }

    @Test
    void firesEventAndAuditAndDeregistersOnCallStop() {
        registry.onTradeFilled(fill("t1", "SPY260430C00595000"));
        // CALL — close=594 < EMA13=595 → fires.
        service.acceptBar(bar(new BigDecimal("594.00")));
        service.acceptIndicator(ind(new BigDecimal("595.00"), new BigDecimal("590.00"), new BigDecimal("2.00")));

        ArgumentCaptor<TradeStopTriggered> evtCap = ArgumentCaptor.forClass(TradeStopTriggered.class);
        verify(stopEvent, times(1)).fire(evtCap.capture());
        TradeStopTriggered evt = evtCap.getValue();
        assertThat(evt.tradeId()).isEqualTo("t1");
        assertThat(evt.alpacaOrderId()).isEqualTo("alpaca-t1");
        assertThat(evt.contractSymbol()).isEqualTo("SPY260430C00595000");
        assertThat(evt.barClose()).isEqualByComparingTo("594.00");
        assertThat(evt.stopReference()).isEqualTo("EMA13");
        assertThat(evt.barTimestamp()).isEqualTo(T2);
        assertThat(evt.triggeredAt()).isEqualTo(TRIGGER_AT);
        assertThat(evt.correlationId()).isEqualTo("corr-t1");

        verify(audit, times(1)).record(any(TradeStopTriggered.class));
        // Deregistered after fire.
        assertThat(registry.size()).isZero();
    }

    @Test
    void doesNotFireWhenIndicatorNotReady() {
        registry.onTradeFilled(fill("t1", "SPY260430C00595000"));
        service.acceptBar(bar(new BigDecimal("594.00")));
        // Warm-up — atr14 missing.
        service.acceptIndicator(ind(new BigDecimal("595.00"), new BigDecimal("590.00"), null));

        verifyNoInteractions(stopEvent, audit);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void doesNotFireWhenBarOutOfWindowDropsThePair() {
        registry.onTradeFilled(fill("t1", "SPY260430C00595000"));
        // Bar at T2, indicator at different T → joiner drops both, no fire.
        service.acceptBar(bar(new BigDecimal("594.00")));
        Instant otherTs = T2.plusSeconds(120);
        service.acceptIndicator(new IndicatorSnapshot(
                "SPY",
                otherTs,
                new BigDecimal("595.00"),
                new BigDecimal("590.00"),
                BigDecimal.ZERO,
                new BigDecimal("2.00")));

        verifyNoInteractions(stopEvent, audit);
    }

    @Test
    void differentSymbolDoesNotFire() {
        registry.onTradeFilled(fill("t1", "SPY260430C00595000"));
        // Bar for QQQ — different underlying.
        Bar qqq = new Bar(
                "QQQ",
                Timeframe.TWO_MIN,
                T2.minusSeconds(120),
                T2,
                new BigDecimal("594.00"),
                new BigDecimal("594.00"),
                new BigDecimal("594.00"),
                new BigDecimal("594.00"),
                0L,
                0L);
        IndicatorSnapshot qqqInd = new IndicatorSnapshot(
                "QQQ", T2, new BigDecimal("595.00"), new BigDecimal("590.00"), BigDecimal.ZERO, new BigDecimal("2.00"));
        service.acceptBar(qqq);
        service.acceptIndicator(qqqInd);

        verify(stopEvent, never()).fire(any(TradeStopTriggered.class));
    }

    @Test
    void auditFailureDoesNotBlockEventFire() {
        registry.onTradeFilled(fill("t1", "SPY260430C00595000"));
        Mockito.doThrow(new RuntimeException("db down")).when(audit).record(any(TradeStopTriggered.class));

        service.acceptBar(bar(new BigDecimal("594.00")));
        service.acceptIndicator(ind(new BigDecimal("595.00"), new BigDecimal("590.00"), new BigDecimal("2.00")));

        verify(stopEvent, times(1)).fire(any(TradeStopTriggered.class));
        assertThat(registry.size()).isZero();
    }
}
