package com.levelsweep.decision.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.decision.fsm.session.SessionService;
import com.levelsweep.decision.fsm.session.SessionState;
import com.levelsweep.decision.fsm.trade.TradeEvent;
import com.levelsweep.decision.fsm.trade.TradeFsmInstance;
import com.levelsweep.decision.fsm.trade.TradeService;
import com.levelsweep.decision.fsm.trade.TradeState;
import com.levelsweep.decision.risk.RiskService;
import com.levelsweep.decision.signal.SignalEvaluator;
import com.levelsweep.decision.strike.StrikeSelectorService;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.levelsweep.shared.domain.options.OptionContract;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.options.StrikeSelection;
import com.levelsweep.shared.domain.options.StrikeSelectionResult;
import com.levelsweep.shared.domain.risk.DailyRiskState;
import com.levelsweep.shared.domain.risk.RiskState;
import com.levelsweep.shared.domain.signal.SignalAction;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import com.levelsweep.shared.domain.signal.SweptLevel;
import com.levelsweep.shared.domain.trade.TradeProposed;
import com.levelsweep.shared.domain.trade.TradeSkipped;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for {@link TradeSaga}. All collaborators are mocked via Mockito;
 * the saga's own logic is exercised by feeding it scripted responses for the
 * session state, signal evaluation, risk gate, strike selection, and trade
 * FSM advancement.
 *
 * <p>Test layout — one test per terminal branch in the saga's flow:
 *
 * <ul>
 *   <li>session not TRADING → TradeSkipped(SESSION_NOT_TRADING)
 *   <li>signal SKIP → TradeSkipped(SIGNAL_SKIP)
 *   <li>risk halted → TradeSkipped(RISK_BLOCKED)
 *   <li>no strike → TradeSkipped(NO_STRIKE)
 *   <li>happy path → TradeProposed with all fields populated
 * </ul>
 */
class TradeSagaTest {

    private static final String TENANT = "OWNER";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final Instant CLOSE = Instant.parse("2026-04-30T14:00:00Z");
    private static final LocalDate SESSION = CLOSE.atZone(ET).toLocalDate();
    private static final UUID FIXED_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private SessionService sessionService;
    private SignalEvaluator signalEvaluator;
    private RiskService riskService;
    private StrikeSelectorService strikeSelectorService;
    private TradeService tradeService;
    private Clock clock;
    private RecordingEventSink<TradeProposed> proposedSink;
    private RecordingEventSink<TradeSkipped> skippedSink;
    private MeterRegistry registry;
    private TradeSaga saga;

    @BeforeEach
    void setUp() {
        sessionService = Mockito.mock(SessionService.class);
        signalEvaluator = Mockito.mock(SignalEvaluator.class);
        riskService = Mockito.mock(RiskService.class);
        strikeSelectorService = Mockito.mock(StrikeSelectorService.class);
        tradeService = Mockito.mock(TradeService.class);
        clock = Clock.fixed(CLOSE, ZoneOffset.UTC);
        proposedSink = new RecordingEventSink<>();
        skippedSink = new RecordingEventSink<>();
        registry = new SimpleMeterRegistry();
        Supplier<UUID> deterministicUuid = () -> FIXED_UUID;

        saga = new TradeSaga(
                sessionService,
                signalEvaluator,
                riskService,
                strikeSelectorService,
                tradeService,
                clock,
                proposedSink,
                skippedSink,
                registry,
                deterministicUuid,
                /* enabled */ true,
                TENANT);
    }

    // ---- Session gate ------------------------------------------------------

    @Test
    void skipsWhenSessionIsNotTrading() {
        when(sessionService.currentState(TENANT)).thenReturn(SessionState.PRE_MARKET);

        TradeSaga.Result result = saga.run(twoMinBar(), bullishSnapshot(), levels());

        assertThat(result).isInstanceOf(TradeSaga.Result.Skipped.class);
        TradeSkipped skipped = ((TradeSaga.Result.Skipped) result).event();
        assertThat(skipped.stage()).isEqualTo(TradeSkipped.STAGE_SESSION_NOT_TRADING);
        assertThat(skipped.reasons()).containsExactly("session_state:PRE_MARKET");
        assertThat(skipped.correlationId()).isEqualTo(FIXED_UUID.toString());
        assertThat(skippedSink.events).hasSize(1);
        assertThat(proposedSink.events).isEmpty();
        verify(signalEvaluator, never()).evaluate(any(), any(), any());
        verify(riskService, never()).canTakeTrade(any());
        assertThat(registry.counter(
                                "decision.saga.evaluations.total",
                                Tags.of("outcome", "SKIPPED", "stage", TradeSkipped.STAGE_SESSION_NOT_TRADING))
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void skipsForEachNonTradingSessionState() {
        for (SessionState state : SessionState.values()) {
            if (state == SessionState.TRADING) {
                continue;
            }
            // Reset between iterations.
            setUp();
            when(sessionService.currentState(TENANT)).thenReturn(state);
            TradeSaga.Result result = saga.run(twoMinBar(), bullishSnapshot(), levels());
            assertThat(result).isInstanceOf(TradeSaga.Result.Skipped.class);
            assertThat(((TradeSaga.Result.Skipped) result).event().stage())
                    .as("state %s", state)
                    .isEqualTo(TradeSkipped.STAGE_SESSION_NOT_TRADING);
        }
    }

    // ---- Signal SKIP -------------------------------------------------------

    @Test
    void skipsWhenSignalEvaluatesToSkip() {
        when(sessionService.currentState(TENANT)).thenReturn(SessionState.TRADING);
        SignalEvaluation skipEval = SignalEvaluation.skip(TENANT, "SPY", CLOSE, List.of("emas_warming_up"));
        when(signalEvaluator.evaluate(any(), any(), any())).thenReturn(skipEval);

        TradeSaga.Result result = saga.run(twoMinBar(), bullishSnapshot(), levels());

        assertThat(result).isInstanceOf(TradeSaga.Result.Skipped.class);
        TradeSkipped skipped = ((TradeSaga.Result.Skipped) result).event();
        assertThat(skipped.stage()).isEqualTo(TradeSkipped.STAGE_SIGNAL_SKIP);
        assertThat(skipped.reasons()).containsExactly("emas_warming_up");
        assertThat(skipped.correlationId()).isEqualTo(FIXED_UUID.toString());
        verify(riskService, never()).canTakeTrade(any());
        verify(strikeSelectorService, never()).selectFor(any(), any(), any(), any());
    }

    // ---- Risk gate ---------------------------------------------------------

    @Test
    void skipsWhenRiskGateBlocks() {
        when(sessionService.currentState(TENANT)).thenReturn(SessionState.TRADING);
        when(signalEvaluator.evaluate(any(), any(), any())).thenReturn(callEntry());
        when(riskService.canTakeTrade(TENANT)).thenReturn(false);
        when(riskService.snapshot(TENANT)).thenReturn(Optional.of(haltedState()));

        TradeSaga.Result result = saga.run(twoMinBar(), bullishSnapshot(), levels());

        assertThat(result).isInstanceOf(TradeSaga.Result.Skipped.class);
        TradeSkipped skipped = ((TradeSaga.Result.Skipped) result).event();
        assertThat(skipped.stage()).isEqualTo(TradeSkipped.STAGE_RISK_BLOCKED);
        assertThat(skipped.reasons()).containsExactly("risk_state:HALTED");
        verify(strikeSelectorService, never()).selectFor(any(), any(), any(), any());
        verify(tradeService, never()).propose(any(), any());
    }

    @Test
    void skipsWithRiskUninitializedWhenSnapshotIsEmpty() {
        when(sessionService.currentState(TENANT)).thenReturn(SessionState.TRADING);
        when(signalEvaluator.evaluate(any(), any(), any())).thenReturn(callEntry());
        when(riskService.canTakeTrade(TENANT)).thenReturn(false);
        when(riskService.snapshot(TENANT)).thenReturn(Optional.empty());

        TradeSaga.Result result = saga.run(twoMinBar(), bullishSnapshot(), levels());

        TradeSkipped skipped = ((TradeSaga.Result.Skipped) result).event();
        assertThat(skipped.reasons()).containsExactly("risk_state:UNINITIALIZED");
    }

    // ---- Strike selection --------------------------------------------------

    @Test
    void skipsWhenStrikeSelectorReturnsNoCandidates() {
        when(sessionService.currentState(TENANT)).thenReturn(SessionState.TRADING);
        when(signalEvaluator.evaluate(any(), any(), any())).thenReturn(callEntry());
        when(riskService.canTakeTrade(TENANT)).thenReturn(true);
        when(strikeSelectorService.selectFor(eq("SPY"), any(), eq(OptionSide.CALL), eq(SESSION)))
                .thenReturn(new StrikeSelectionResult.NoCandidates("empty_chain"));

        TradeSaga.Result result = saga.run(twoMinBar(), bullishSnapshot(), levels());

        assertThat(result).isInstanceOf(TradeSaga.Result.Skipped.class);
        TradeSkipped skipped = ((TradeSaga.Result.Skipped) result).event();
        assertThat(skipped.stage()).isEqualTo(TradeSkipped.STAGE_NO_STRIKE);
        assertThat(skipped.reasons()).containsExactly("empty_chain");
        verify(tradeService, never()).propose(any(), any());
        verify(riskService, never()).onTradeStarted(any());
    }

    // ---- Happy path --------------------------------------------------------

    @Test
    void happyPathProposesTradeAndAdvancesFsm() {
        when(sessionService.currentState(TENANT)).thenReturn(SessionState.TRADING);
        when(signalEvaluator.evaluate(any(), any(), any())).thenReturn(callEntry());
        when(riskService.canTakeTrade(TENANT)).thenReturn(true);
        OptionContract contract = sampleCall();
        when(strikeSelectorService.selectFor(eq("SPY"), any(), eq(OptionSide.CALL), eq(SESSION)))
                .thenReturn(new StrikeSelectionResult.Selected(new StrikeSelection(contract, "atm_call", List.of())));

        String tradeId = "trade-1";
        TradeFsmInstance proposed = new TradeFsmInstance(
                TENANT,
                SESSION,
                tradeId,
                TradeState.PROPOSED,
                Optional.empty(),
                Optional.of(CLOSE),
                Optional.empty(),
                Optional.empty());
        TradeFsmInstance entered = proposed.withState(TradeState.ENTERED);
        when(tradeService.propose(TENANT, SESSION)).thenReturn(proposed);
        when(tradeService.apply(eq(tradeId), eq(TradeEvent.RISK_APPROVED), any()))
                .thenReturn(Optional.of(entered));

        TradeSaga.Result result = saga.run(twoMinBar(), bullishSnapshot(), levels());

        // Outcome shape
        assertThat(result).isInstanceOf(TradeSaga.Result.Proposed.class);
        TradeProposed event = ((TradeSaga.Result.Proposed) result).event();
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.tradeId()).isEqualTo(tradeId);
        assertThat(event.sessionDate()).isEqualTo(SESSION);
        assertThat(event.proposedAt()).isEqualTo(CLOSE);
        assertThat(event.underlying()).isEqualTo("SPY");
        assertThat(event.side()).isEqualTo(OptionSide.CALL);
        assertThat(event.contractSymbol()).isEqualTo(contract.symbol());
        assertThat(event.entryNbboBid()).isEqualByComparingTo(contract.bidPrice());
        assertThat(event.entryNbboAsk()).isEqualByComparingTo(contract.askPrice());
        assertThat(event.entryMid()).isEqualByComparingTo(contract.mid());
        assertThat(event.impliedVolatility()).isEqualTo(contract.impliedVolatility());
        assertThat(event.delta()).isEqualTo(contract.delta());
        assertThat(event.correlationId()).isEqualTo(FIXED_UUID.toString());
        assertThat(event.signalReasons()).containsExactly("sweep:PDL", "ema_stack:LONG_OK");

        // FSM driven, risk notified, correlationId threaded through.
        verify(tradeService).propose(TENANT, SESSION);
        verify(riskService).onTradeStarted(TENANT);
        ArgumentCaptor<Optional<String>> corrCaptor = ArgumentCaptor.forClass(Optional.class);
        verify(tradeService).apply(eq(tradeId), eq(TradeEvent.RISK_APPROVED), corrCaptor.capture());
        assertThat(corrCaptor.getValue()).contains(FIXED_UUID.toString());

        // CDI events: one TradeProposed, no TradeSkipped.
        assertThat(proposedSink.events).containsExactly(event);
        assertThat(skippedSink.events).isEmpty();

        // Counter
        assertThat(registry.counter("decision.saga.evaluations.total", Tags.of("outcome", TradeSaga.OUTCOME_PROPOSED))
                        .count())
                .isEqualTo(1.0);
    }

    // ---- Disabled gate -----------------------------------------------------

    @Test
    void shortCircuitsWhenSagaIsDisabled() {
        saga = new TradeSaga(
                sessionService,
                signalEvaluator,
                riskService,
                strikeSelectorService,
                tradeService,
                clock,
                proposedSink,
                skippedSink,
                registry,
                () -> FIXED_UUID,
                /* enabled */ false,
                TENANT);

        TradeSaga.Result result = saga.run(twoMinBar(), bullishSnapshot(), levels());

        assertThat(result).isInstanceOf(TradeSaga.Result.Skipped.class);
        verify(sessionService, never()).currentState(any());
        verify(signalEvaluator, never()).evaluate(any(), any(), any());
        verify(riskService, never()).canTakeTrade(any());
    }

    // ---- Determinism -------------------------------------------------------

    @Test
    void sameInputsProduceSameOutputsGivenStubbedUuid() {
        when(sessionService.currentState(TENANT)).thenReturn(SessionState.TRADING);
        SignalEvaluation skipEval = SignalEvaluation.skip(TENANT, "SPY", CLOSE, List.of("emas_warming_up"));
        when(signalEvaluator.evaluate(any(), any(), any())).thenReturn(skipEval);

        TradeSaga.Result a = saga.run(twoMinBar(), bullishSnapshot(), levels());
        TradeSaga.Result b = saga.run(twoMinBar(), bullishSnapshot(), levels());

        assertThat(a).isInstanceOf(TradeSaga.Result.Skipped.class);
        assertThat(b).isInstanceOf(TradeSaga.Result.Skipped.class);
        assertThat(((TradeSaga.Result.Skipped) a).event()).isEqualTo(((TradeSaga.Result.Skipped) b).event());
    }

    // ---- Null arg rejection ------------------------------------------------

    @Test
    void rejectsNullArguments() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> saga.run(null, bullishSnapshot(), levels()))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> saga.run(twoMinBar(), null, levels()))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> saga.run(twoMinBar(), bullishSnapshot(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Counts in metrics for skips per stage -----------------------------

    @Test
    void countersTagBySkipStage() {
        when(sessionService.currentState(TENANT)).thenReturn(SessionState.TRADING);
        when(signalEvaluator.evaluate(any(), any(), any()))
                .thenReturn(SignalEvaluation.skip(TENANT, "SPY", CLOSE, List.of("emas_warming_up")));
        saga.run(twoMinBar(), bullishSnapshot(), levels());
        saga.run(twoMinBar(), bullishSnapshot(), levels());

        assertThat(registry.counter(
                                "decision.saga.evaluations.total",
                                Tags.of("outcome", "SKIPPED", "stage", TradeSkipped.STAGE_SIGNAL_SKIP))
                        .count())
                .isEqualTo(2.0);
        verify(signalEvaluator, times(2)).evaluate(any(), any(), any());
    }

    // ---- Helpers -----------------------------------------------------------

    private static Bar twoMinBar() {
        Instant open = CLOSE.minus(Timeframe.TWO_MIN.duration());
        return new Bar(
                "SPY",
                Timeframe.TWO_MIN,
                open,
                CLOSE,
                new BigDecimal("590.10"),
                new BigDecimal("590.30"),
                new BigDecimal("589.50"),
                new BigDecimal("590.20"),
                1_000L,
                10L);
    }

    private static IndicatorSnapshot bullishSnapshot() {
        return new IndicatorSnapshot(
                "SPY",
                CLOSE,
                new BigDecimal("595.00"),
                new BigDecimal("594.00"),
                new BigDecimal("593.00"),
                new BigDecimal("1.00"));
    }

    private static Levels levels() {
        return new Levels(
                TENANT,
                "SPY",
                SESSION,
                new BigDecimal("600.00"),
                new BigDecimal("590.00"),
                new BigDecimal("598.00"),
                new BigDecimal("592.00"));
    }

    private static SignalEvaluation callEntry() {
        return SignalEvaluation.enter(
                TENANT,
                "SPY",
                CLOSE,
                SignalAction.ENTER_LONG,
                SweptLevel.PDL,
                OptionSide.CALL,
                new BigDecimal("590.00"),
                List.of("sweep:PDL", "ema_stack:LONG_OK"));
    }

    private static OptionContract sampleCall() {
        return new OptionContract(
                "SPY260430C00590000",
                "SPY",
                SESSION,
                new BigDecimal("590"),
                OptionSide.CALL,
                new BigDecimal("1.05"),
                new BigDecimal("1.10"),
                Optional.of(500),
                Optional.of(1000),
                Optional.of(new BigDecimal("0.18")),
                Optional.of(new BigDecimal("0.55")));
    }

    private static DailyRiskState haltedState() {
        return new DailyRiskState(
                TENANT,
                SESSION,
                new BigDecimal("5000.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                3,
                RiskState.HALTED,
                Optional.of(CLOSE),
                Optional.of("daily_loss_budget_consumed"));
    }

    /**
     * Test-only {@link Event} that captures every fired payload into a list so
     * assertions can run after the saga returns. Implements only the methods
     * the saga actually calls (a {@code fire(T)} pass-through); the rest throw
     * to flag accidental wider use.
     */
    private static final class RecordingEventSink<T> implements Event<T> {

        final java.util.List<T> events = new java.util.ArrayList<>();

        @Override
        public void fire(T event) {
            events.add(event);
        }

        @Override
        public <U extends T> java.util.concurrent.CompletionStage<U> fireAsync(U event) {
            throw new UnsupportedOperationException("fireAsync not used by saga");
        }

        @Override
        public <U extends T> java.util.concurrent.CompletionStage<U> fireAsync(
                U event, jakarta.enterprise.event.NotificationOptions options) {
            throw new UnsupportedOperationException("fireAsync not used by saga");
        }

        @Override
        public Event<T> select(java.lang.annotation.Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends T> Event<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Event<U> select(
                jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
    }
}
