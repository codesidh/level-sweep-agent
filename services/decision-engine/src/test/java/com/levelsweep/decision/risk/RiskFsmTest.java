package com.levelsweep.decision.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.risk.DailyRiskState;
import com.levelsweep.shared.domain.risk.RiskEvent;
import com.levelsweep.shared.domain.risk.RiskEventType;
import com.levelsweep.shared.domain.risk.RiskState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pure-POJO tests for {@link RiskFsm}. The FSM has no CDI / clock / IO
 * dependencies, so each test walks one transition with concrete numbers and
 * asserts the {@link RiskFsm.Result} record directly.
 */
class RiskFsmTest {

    private static final String TENANT = "OWNER";
    private static final LocalDate SESSION = LocalDate.of(2026, 4, 30);
    private static final Instant T0 = Instant.parse("2026-04-30T13:29:00Z");

    /** $5,000 equity → $100 budget (2%). Max 5 trades/day. */
    private static final BigDecimal EQUITY = new BigDecimal("5000.00");

    private static final BigDecimal BUDGET = new BigDecimal("100.00");

    private RiskFsm fsm() {
        return new RiskFsm(5, new BigDecimal("0.70"));
    }

    @Test
    void resetProducesHealthyStateAndDailyResetEvent() {
        RiskFsm.Result r = fsm().reset(TENANT, SESSION, EQUITY, BUDGET, T0);

        assertThat(r.newState().tenantId()).isEqualTo(TENANT);
        assertThat(r.newState().sessionDate()).isEqualTo(SESSION);
        assertThat(r.newState().startingEquity()).isEqualByComparingTo(EQUITY);
        assertThat(r.newState().dailyLossBudget()).isEqualByComparingTo(BUDGET);
        assertThat(r.newState().realizedLoss()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.newState().tradesTaken()).isZero();
        assertThat(r.newState().state()).isEqualTo(RiskState.HEALTHY);
        assertThat(r.newState().haltedAt()).isEmpty();
        assertThat(r.newState().haltReason()).isEmpty();
        assertThat(r.events()).hasSize(1);
        assertThat(r.events().get(0).type()).isEqualTo(RiskEventType.DAILY_RESET);
    }

    @Test
    void onFillRealizedWithProfitDoesNotConsumeBudget() {
        DailyRiskState start = healthy();
        RiskFsm.Result r = fsm().onFillRealized(start, new BigDecimal("25.00"), T0);

        assertThat(r.newState().realizedLoss()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.newState().state()).isEqualTo(RiskState.HEALTHY);
        // No budget consumed → no events.
        assertThat(r.events()).isEmpty();
    }

    @Test
    void onFillRealizedWithSmallLossStaysHealthy() {
        DailyRiskState start = healthy();
        // -$30 loss < 70% × $100 = $70 warn threshold
        RiskFsm.Result r = fsm().onFillRealized(start, new BigDecimal("-30.00"), T0);

        assertThat(r.newState().realizedLoss()).isEqualByComparingTo("30.00");
        assertThat(r.newState().state()).isEqualTo(RiskState.HEALTHY);
        // BUDGET_CONSUMED only — no STATE_TRANSITION
        assertThat(r.events()).hasSize(1);
        assertThat(r.events().get(0).type()).isEqualTo(RiskEventType.BUDGET_CONSUMED);
        assertThat(r.events().get(0).deltaAmount()).contains(new BigDecimal("30.00"));
        assertThat(r.events().get(0).cumulativeLoss()).contains(new BigDecimal("30.00"));
    }

    @Test
    void onFillRealizedCrossingWarnThresholdTransitionsToBudgetLow() {
        DailyRiskState start = healthy();
        // -$75 loss crosses 70% × $100 = $70
        RiskFsm.Result r = fsm().onFillRealized(start, new BigDecimal("-75.00"), T0);

        assertThat(r.newState().realizedLoss()).isEqualByComparingTo("75.00");
        assertThat(r.newState().state()).isEqualTo(RiskState.BUDGET_LOW);
        assertThat(r.events()).hasSize(2);
        assertThat(r.events().get(0).type()).isEqualTo(RiskEventType.BUDGET_CONSUMED);
        assertThat(r.events().get(1).type()).isEqualTo(RiskEventType.STATE_TRANSITION);
        assertThat(r.events().get(1).fromState()).contains(RiskState.HEALTHY);
        assertThat(r.events().get(1).toState()).contains(RiskState.BUDGET_LOW);
    }

    @Test
    void onFillRealizedCrossingFullBudgetHaltsAndCarriesReason() {
        DailyRiskState start = healthy();
        // -$100 loss == budget, hard halt
        RiskFsm.Result r = fsm().onFillRealized(start, new BigDecimal("-100.00"), T0);

        assertThat(r.newState().realizedLoss()).isEqualByComparingTo("100.00");
        assertThat(r.newState().state()).isEqualTo(RiskState.HALTED);
        assertThat(r.newState().haltedAt()).contains(T0);
        assertThat(r.newState().haltReason()).contains("BUDGET_EXHAUSTED");

        // BUDGET_CONSUMED + STATE_TRANSITION (HEALTHY -> HALTED) + HALT_TRIGGERED
        assertThat(r.events()).hasSize(3);
        assertThat(r.events().get(0).type()).isEqualTo(RiskEventType.BUDGET_CONSUMED);
        assertThat(r.events().get(1).type()).isEqualTo(RiskEventType.STATE_TRANSITION);
        assertThat(r.events().get(1).fromState()).contains(RiskState.HEALTHY);
        assertThat(r.events().get(1).toState()).contains(RiskState.HALTED);
        assertThat(r.events().get(2).type()).isEqualTo(RiskEventType.HALT_TRIGGERED);
        assertThat(r.events().get(2).reason()).isEqualTo("BUDGET_EXHAUSTED");
    }

    @Test
    void onFillRealizedFromBudgetLowToHaltedFiresHaltEvent() {
        DailyRiskState lowState = stateWithLoss(new BigDecimal("75.00"), RiskState.BUDGET_LOW);
        // Add another $30 → cumulative $105 > $100 budget
        RiskFsm.Result r = fsm().onFillRealized(lowState, new BigDecimal("-30.00"), T0);

        assertThat(r.newState().state()).isEqualTo(RiskState.HALTED);
        assertThat(r.newState().realizedLoss()).isEqualByComparingTo("105.00");
        assertThat(r.events())
                .extracting(RiskEvent::type)
                .containsExactly(
                        RiskEventType.BUDGET_CONSUMED, RiskEventType.STATE_TRANSITION, RiskEventType.HALT_TRIGGERED);
    }

    @Test
    void onFillRealizedWhenAlreadyHaltedAccumulatesLossButDoesNotTransition() {
        DailyRiskState halted = haltedState(new BigDecimal("100.00"));
        RiskFsm.Result r = fsm().onFillRealized(halted, new BigDecimal("-20.00"), T0);

        assertThat(r.newState().realizedLoss()).isEqualByComparingTo("120.00");
        assertThat(r.newState().state()).isEqualTo(RiskState.HALTED);
        // Single BUDGET_CONSUMED — no transition or halt event.
        assertThat(r.events()).hasSize(1);
        assertThat(r.events().get(0).type()).isEqualTo(RiskEventType.BUDGET_CONSUMED);
    }

    @Test
    void onFillRealizedWhenHaltedWithProfitEmitsNoEvents() {
        DailyRiskState halted = haltedState(new BigDecimal("100.00"));
        RiskFsm.Result r = fsm().onFillRealized(halted, new BigDecimal("15.00"), T0);

        assertThat(r.newState()).isEqualTo(halted);
        assertThat(r.events()).isEmpty();
    }

    @Test
    void onTradeStartedBumpsCounter() {
        DailyRiskState start = healthy();
        RiskFsm.Result r = fsm().onTradeStarted(start, T0);

        assertThat(r.newState().tradesTaken()).isEqualTo(1);
        assertThat(r.newState().state()).isEqualTo(RiskState.HEALTHY);
        assertThat(r.events()).isEmpty();
    }

    @Test
    void onTradeStartedAtMaxHaltsWithMaxTradesReason() {
        DailyRiskState fourTrades = new DailyRiskState(
                TENANT,
                SESSION,
                EQUITY,
                BUDGET,
                BigDecimal.ZERO,
                4,
                RiskState.HEALTHY,
                java.util.Optional.empty(),
                java.util.Optional.empty());

        RiskFsm.Result r = fsm().onTradeStarted(fourTrades, T0);

        assertThat(r.newState().tradesTaken()).isEqualTo(5);
        assertThat(r.newState().state()).isEqualTo(RiskState.HALTED);
        assertThat(r.newState().haltReason()).contains("MAX_TRADES");
        assertThat(r.events())
                .extracting(RiskEvent::type)
                .containsExactly(RiskEventType.STATE_TRANSITION, RiskEventType.HALT_TRIGGERED);
    }

    @Test
    void onTradeStartedAtMaxFromBudgetLowAlsoHalts() {
        DailyRiskState fourTradesBudgetLow = new DailyRiskState(
                TENANT,
                SESSION,
                EQUITY,
                BUDGET,
                new BigDecimal("75.00"),
                4,
                RiskState.BUDGET_LOW,
                java.util.Optional.empty(),
                java.util.Optional.empty());

        RiskFsm.Result r = fsm().onTradeStarted(fourTradesBudgetLow, T0);

        assertThat(r.newState().state()).isEqualTo(RiskState.HALTED);
        assertThat(r.newState().haltReason()).contains("MAX_TRADES");
    }

    @Test
    void onTradeStartedWhileHaltedIsNoop() {
        DailyRiskState halted = haltedState(new BigDecimal("100.00"));
        RiskFsm.Result r = fsm().onTradeStarted(halted, T0);

        assertThat(r.newState()).isEqualTo(halted);
        assertThat(r.events()).isEmpty();
    }

    @Test
    void onHaltManualTransitionsToHaltedWithReason() {
        DailyRiskState start = healthy();
        RiskFsm.Result r = fsm().onHaltManual(start, "NEWS_BLACKOUT", T0);

        assertThat(r.newState().state()).isEqualTo(RiskState.HALTED);
        assertThat(r.newState().haltReason()).contains("NEWS_BLACKOUT");
        assertThat(r.events())
                .extracting(RiskEvent::type)
                .containsExactly(RiskEventType.STATE_TRANSITION, RiskEventType.HALT_TRIGGERED);
    }

    @Test
    void onHaltManualWhileHaltedIsIdempotent() {
        DailyRiskState halted = haltedState(new BigDecimal("0.00"));
        RiskFsm.Result r = fsm().onHaltManual(halted, "NEWS_BLACKOUT", T0);

        assertThat(r.newState()).isEqualTo(halted);
        assertThat(r.events()).isEmpty();
    }

    @Test
    void constructorRejectsBadConfig() {
        assertThatThrownBy(() -> new RiskFsm(0, new BigDecimal("0.70")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTradesPerDay");
        assertThatThrownBy(() -> new RiskFsm(5, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetLowFraction");
        assertThatThrownBy(() -> new RiskFsm(5, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetLowFraction");
    }

    @Test
    void resetRejectsNegativeStartingEquity() {
        assertThatThrownBy(() -> fsm().reset(TENANT, SESSION, new BigDecimal("-1"), BUDGET, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startingEquity");
    }

    // --- helpers ---------------------------------------------------------

    private static DailyRiskState healthy() {
        return new DailyRiskState(
                TENANT,
                SESSION,
                EQUITY,
                BUDGET,
                BigDecimal.ZERO,
                0,
                RiskState.HEALTHY,
                java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    private static DailyRiskState stateWithLoss(BigDecimal loss, RiskState state) {
        return new DailyRiskState(
                TENANT,
                SESSION,
                EQUITY,
                BUDGET,
                loss,
                0,
                state,
                java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    private static DailyRiskState haltedState(BigDecimal loss) {
        return new DailyRiskState(
                TENANT,
                SESSION,
                EQUITY,
                BUDGET,
                loss,
                0,
                RiskState.HALTED,
                java.util.Optional.of(T0),
                java.util.Optional.of("BUDGET_EXHAUSTED"));
    }
}
