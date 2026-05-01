package com.levelsweep.decision.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.levelsweep.decision.risk.persistence.DailyRiskStateRepository;
import com.levelsweep.shared.domain.risk.DailyRiskState;
import com.levelsweep.shared.domain.risk.RiskEvent;
import com.levelsweep.shared.domain.risk.RiskState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for {@link RiskService}. Mocks {@link DailyRiskStateRepository}
 * to assert that events are persisted before state, that the in-memory cache
 * tracks transitions, and that {@link RiskService#canTakeTrade} gates the
 * Trade Saga correctly.
 */
class RiskServiceTest {

    private static final String TENANT = "OWNER";
    private static final BigDecimal EQUITY = new BigDecimal("5000.00");
    private static final BigDecimal BUDGET = new BigDecimal("100.00");

    /** Fixed clock at 09:29 ET = 13:29 UTC on 2026-04-30. */
    private static final Instant T0 = Instant.parse("2026-04-30T13:29:00Z");

    private DailyRiskStateRepository repository;
    private RiskService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(DailyRiskStateRepository.class);
        Clock fixed = Clock.fixed(T0, ZoneOffset.UTC);
        service = new RiskService(new RiskFsm(5, new BigDecimal("0.70")), repository, fixed);
    }

    @Test
    void canTakeTradeIsFalseUntilDailyReset() {
        assertThat(service.canTakeTrade(TENANT)).isFalse();
        assertThat(service.snapshot(TENANT)).isEmpty();
    }

    @Test
    void onDailyResetCachesStateAndPersistsResetEvent() {
        DailyRiskState s = service.onDailyReset(TENANT, EQUITY, BUDGET);

        assertThat(s.state()).isEqualTo(RiskState.HEALTHY);
        assertThat(s.startingEquity()).isEqualByComparingTo(EQUITY);
        assertThat(s.dailyLossBudget()).isEqualByComparingTo(BUDGET);
        assertThat(service.canTakeTrade(TENANT)).isTrue();
        assertThat(service.snapshot(TENANT)).contains(s);

        verify(repository, times(1)).recordEvent(any(RiskEvent.class));
        verify(repository, times(1)).upsert(any(DailyRiskState.class));
    }

    @Test
    void onFillRealizedLossPersistsBothEventsAndStateThenUpdatesCache() {
        service.onDailyReset(TENANT, EQUITY, BUDGET);
        Mockito.reset(repository);

        DailyRiskState after = service.onFillRealized(TENANT, new BigDecimal("-75.00"));

        assertThat(after.state()).isEqualTo(RiskState.BUDGET_LOW);
        assertThat(after.realizedLoss()).isEqualByComparingTo("75.00");
        assertThat(service.snapshot(TENANT)).contains(after);

        // BUDGET_CONSUMED + STATE_TRANSITION
        ArgumentCaptor<RiskEvent> eventCaptor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(repository, times(2)).recordEvent(eventCaptor.capture());
        verify(repository, times(1)).upsert(after);

        // Trade Saga still allowed to enter from BUDGET_LOW (re-entry rule §11.3)
        assertThat(service.canTakeTrade(TENANT)).isTrue();
    }

    @Test
    void canTakeTradeReturnsFalseAfterHalt() {
        service.onDailyReset(TENANT, EQUITY, BUDGET);
        service.onFillRealized(TENANT, new BigDecimal("-100.00"));

        assertThat(service.snapshot(TENANT).orElseThrow().state()).isEqualTo(RiskState.HALTED);
        assertThat(service.canTakeTrade(TENANT)).isFalse();
    }

    @Test
    void onTradeStartedBumpsCounter() {
        service.onDailyReset(TENANT, EQUITY, BUDGET);
        Mockito.reset(repository);

        DailyRiskState after = service.onTradeStarted(TENANT);

        assertThat(after.tradesTaken()).isEqualTo(1);
        assertThat(after.state()).isEqualTo(RiskState.HEALTHY);
        // No FSM events when below max — only the state upsert
        verify(repository, never()).recordEvent(any());
        verify(repository, times(1)).upsert(after);
    }

    @Test
    void onHaltManualTransitionsToHaltedAndPersistsBothEvents() {
        service.onDailyReset(TENANT, EQUITY, BUDGET);
        Mockito.reset(repository);

        DailyRiskState after = service.onHaltManual(TENANT, "NEWS_BLACKOUT");

        assertThat(after.state()).isEqualTo(RiskState.HALTED);
        assertThat(after.haltReason()).contains("NEWS_BLACKOUT");
        verify(repository, times(2)).recordEvent(any(RiskEvent.class));
        verify(repository, times(1)).upsert(after);
        assertThat(service.canTakeTrade(TENANT)).isFalse();
    }

    @Test
    void operationsBeforeResetThrow() {
        assertThatThrownBy(() -> service.onFillRealized(TENANT, new BigDecimal("-10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
        assertThatThrownBy(() -> service.onTradeStarted(TENANT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.onHaltManual(TENANT, "MANUAL"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void differentTenantsHaveIsolatedState() {
        service.onDailyReset(TENANT, EQUITY, BUDGET);
        service.onDailyReset("TENANT_B", new BigDecimal("10000.00"), new BigDecimal("200.00"));
        Mockito.reset(repository);

        service.onFillRealized(TENANT, new BigDecimal("-100.00"));

        assertThat(service.canTakeTrade(TENANT)).isFalse();
        assertThat(service.canTakeTrade("TENANT_B")).isTrue();
        verify(repository, atLeastOnce()).recordEvent(any());
    }
}
