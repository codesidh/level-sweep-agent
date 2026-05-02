package com.levelsweep.execution.trail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.levelsweep.execution.persistence.TrailAuditRepository;
import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import com.levelsweep.shared.domain.trade.TradeTrailRatcheted;
import jakarta.enterprise.event.Event;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * End-to-end orchestration test for {@link TrailPollScheduler}: drives a
 * synthetic NBBO sequence (the §10.4 worked example) through the FSM and
 * asserts the audit + CDI events fire in order.
 */
class TrailPollSchedulerTest {

    private TrailRegistry registry;
    private TrailConfig config;
    private AlpacaQuotesClient quotes;
    private TrailAuditRepository audit;
    private Event<TradeTrailRatcheted> ratchetEvent;
    private Event<TradeTrailBreached> breachEvent;
    private TrailPollScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new TrailRegistry();
        config = TrailConfig.of(3, new BigDecimal("0.30"), new BigDecimal("0.05"));
        quotes = Mockito.mock(AlpacaQuotesClient.class);
        audit = Mockito.mock(TrailAuditRepository.class);
        ratchetEvent = Mockito.mock(Event.class);
        breachEvent = Mockito.mock(Event.class);
        scheduler = new TrailPollScheduler(registry, config, quotes, audit, ratchetEvent, breachEvent);
    }

    private static AlpacaQuotesClient.NbboSnapshot snap(
            String contractSymbol, double bid, double ask, int secondsOffset) {
        return new AlpacaQuotesClient.NbboSnapshot(
                contractSymbol,
                BigDecimal.valueOf(bid),
                BigDecimal.valueOf(ask),
                Instant.parse("2026-04-30T15:00:00Z").plusSeconds(secondsOffset));
    }

    @Test
    void emptyRegistryDoesNotCallQuotes() {
        scheduler.pollOnce();
        verifyNoInteractions(quotes, audit, ratchetEvent, breachEvent);
    }

    @Test
    void noOpOnAbsentSnapshot() {
        registry.register(new TrailState("OWNER", "t1", "C1", new BigDecimal("10.00"), 1, "corr-1"));
        when(quotes.snapshot("C1")).thenReturn(Optional.empty());

        scheduler.pollOnce();

        verifyNoInteractions(audit, ratchetEvent, breachEvent);
    }

    @Test
    void replaysSection10_4WorkedExampleAuditAndEvents() {
        // Entry premium = $10.00. Worked-example NBBO sequence (mid):
        //   3x at mid=13.00 (+30%) → arm at floor=+25%
        //   3x at mid=13.50 (+35%) → ratchet to floor=+30%
        //   3x at mid=14.00 (+40%) → ratchet to floor=+35%
        //   3x at mid=13.50 (retrace to floor +35%) → exit
        registry.register(new TrailState("OWNER", "t1", "SPY260430C00595000", new BigDecimal("10.00"), 1, "corr-1"));

        // Stage 1: arm.
        for (int i = 0; i < 3; i++) {
            when(quotes.snapshot("SPY260430C00595000"))
                    .thenReturn(Optional.of(snap("SPY260430C00595000", 12.99, 13.01, i)));
            scheduler.pollOnce();
        }
        // Stage 2: ratchet to +30%.
        for (int i = 3; i < 6; i++) {
            when(quotes.snapshot("SPY260430C00595000"))
                    .thenReturn(Optional.of(snap("SPY260430C00595000", 13.49, 13.51, i)));
            scheduler.pollOnce();
        }
        // Stage 3: ratchet to +35%.
        for (int i = 6; i < 9; i++) {
            when(quotes.snapshot("SPY260430C00595000"))
                    .thenReturn(Optional.of(snap("SPY260430C00595000", 13.99, 14.01, i)));
            scheduler.pollOnce();
        }
        // Stage 4: retrace, exit.
        for (int i = 9; i < 12; i++) {
            when(quotes.snapshot("SPY260430C00595000"))
                    .thenReturn(Optional.of(snap("SPY260430C00595000", 13.49, 13.51, i)));
            scheduler.pollOnce();
        }

        // 3 ratchet audits (arm, +30%, +35%) and 3 ratchet events.
        ArgumentCaptor<TradeTrailRatcheted> ratchetCap = ArgumentCaptor.forClass(TradeTrailRatcheted.class);
        verify(audit, times(3)).recordRatchet(ratchetCap.capture(), eq("SPY260430C00595000"));
        verify(ratchetEvent, times(3)).fire(any(TradeTrailRatcheted.class));
        // Floors in order: 0.25, 0.30, 0.35.
        assertThat(ratchetCap.getAllValues())
                .extracting(TradeTrailRatcheted::newFloorPct)
                .containsExactly(new BigDecimal("0.25"), new BigDecimal("0.30"), new BigDecimal("0.35"));

        // 1 exit audit + event at floor=+0.35.
        ArgumentCaptor<TradeTrailBreached> exitCap = ArgumentCaptor.forClass(TradeTrailBreached.class);
        verify(audit, times(1)).recordExit(exitCap.capture());
        verify(breachEvent, times(1)).fire(exitCap.getValue());
        assertThat(exitCap.getValue().exitFloorPct()).isEqualByComparingTo(new BigDecimal("0.35"));

        // Trade deregistered after exit.
        assertThat(registry.size()).isZero();
    }

    @Test
    void exceptionInOneStateDoesNotStopOthers() {
        // Two registered trades — first one's quote call throws, second succeeds.
        registry.register(new TrailState("OWNER", "t1", "C1", new BigDecimal("10.00"), 1, "corr-1"));
        registry.register(new TrailState("OWNER", "t2", "C2", new BigDecimal("10.00"), 1, "corr-2"));

        when(quotes.snapshot("C1")).thenThrow(new RuntimeException("disk full"));
        when(quotes.snapshot("C2")).thenReturn(Optional.empty());

        // Must not propagate — both calls happen.
        scheduler.pollOnce();

        verify(quotes, times(1)).snapshot("C1");
        verify(quotes, times(1)).snapshot("C2");
    }
}
