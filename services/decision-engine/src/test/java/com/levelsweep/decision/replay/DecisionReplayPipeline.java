package com.levelsweep.decision.replay;

import com.levelsweep.decision.fsm.session.SessionEvent;
import com.levelsweep.decision.fsm.session.SessionFsm;
import com.levelsweep.decision.fsm.session.SessionState;
import com.levelsweep.decision.fsm.trade.TradeEvent;
import com.levelsweep.decision.fsm.trade.TradeFsm;
import com.levelsweep.decision.fsm.trade.TradeState;
import com.levelsweep.decision.risk.RiskFsm;
import com.levelsweep.decision.signal.SignalEvaluator;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.risk.DailyRiskState;
import com.levelsweep.shared.domain.risk.RiskEvent;
import com.levelsweep.shared.domain.signal.SignalAction;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Test-only composition that drives a hand-labeled session through the Phase 2
 * Decision Engine modules — {@link SignalEvaluator}, {@link RiskFsm},
 * {@link SessionFsm}, {@link TradeFsm} — and captures the produced
 * {@link SignalEvaluation}s, FSM transitions, and risk events for downstream
 * assertions.
 *
 * <p>Mirrors the
 * {@link com.levelsweep.marketdata.replay.DataLayerPipeline} pattern from
 * market-data-service: hand-instantiated beans (no CDI), pure synchronous
 * call sequence per bar, all output captured into immutable lists.
 *
 * <h3>Determinism contract</h3>
 *
 * <ul>
 *   <li>No {@link Instant#now()} — every event timestamps to the bar's
 *       {@code closeTime} (already deterministic per fixture).
 *   <li>No randomness in the pipeline — randomness lives in
 *       {@link SyntheticSessionFixtures} and is fully consumed at fixture
 *       construction.
 *   <li>No external IO — Mongo, MS SQL, Kafka, Anthropic, Alpaca are absent
 *       from the test classpath of this harness by design (per the
 *       {@code replay-parity} skill).
 * </ul>
 *
 * <h3>What's wired</h3>
 *
 * <ol>
 *   <li>Each bar → {@link SignalEvaluator#evaluate} → captured into
 *       {@link #evaluations()}.
 *   <li>{@link SessionFsm} transitions on the synthetic open / close events the
 *       harness fires explicitly (no clock dependency).
 *   <li>{@link TradeFsm} drives a single-trade lifecycle whenever a non-SKIP
 *       signal fires and the Risk FSM is not HALTED. The trade is held in-flight
 *       until the next bar and force-exited on EOD.
 *   <li>{@link RiskFsm} accumulates the synthetic per-trade fill P&L (caller
 *       supplies via {@link #onFillRealized}) and drives transitions / halts.
 * </ol>
 *
 * <p>Trade Saga (Phase 2 S6) is intentionally absent — this harness predates it.
 */
public final class DecisionReplayPipeline {

    /** Production defaults — match {@code @ConfigProperty} values in S2. */
    private static final BigDecimal SWEEP_BUFFER = new BigDecimal("0.20");

    private static final BigDecimal EMA_GAP = new BigDecimal("0.30");
    private static final BigDecimal NEAR_LEVEL = new BigDecimal("0.50");

    /** Production defaults — match {@code RiskFsm} static defaults in S3. */
    private static final int MAX_TRADES_PER_DAY = RiskFsm.DEFAULT_MAX_TRADES_PER_DAY;

    private static final BigDecimal BUDGET_LOW_FRACTION = RiskFsm.DEFAULT_BUDGET_LOW_FRACTION;

    private final SignalEvaluator signalEvaluator;
    private final RiskFsm riskFsm;
    private final SessionFsm sessionFsm;
    private final TradeFsm tradeFsm;

    private final List<SignalEvaluation> evaluations = new ArrayList<>();
    private final List<RiskEvent> riskEvents = new ArrayList<>();
    private final List<SessionTransition> sessionTransitions = new ArrayList<>();
    private final List<TradeTransition> tradeTransitions = new ArrayList<>();

    private DailyRiskState riskState;
    private SessionState sessionState = SessionState.PRE_MARKET;
    /** Single in-flight trade; harness models a serialized one-at-a-time flow. */
    private InFlightTrade activeTrade;

    /**
     * Captured session-level transition. The pure {@link SessionFsm#next} returns
     * an Optional, so we record only successful transitions here.
     */
    public record SessionTransition(
            SessionState fromState, SessionState toState, SessionEvent event, Instant occurredAt) {}

    /** Captured per-trade transition for the EOD audit trail. */
    public record TradeTransition(
            String tradeId, TradeState fromState, TradeState toState, TradeEvent event, Instant occurredAt) {}

    private record InFlightTrade(String tradeId, TradeState state) {}

    public DecisionReplayPipeline() {
        this.signalEvaluator = new SignalEvaluator(SWEEP_BUFFER, EMA_GAP, NEAR_LEVEL);
        this.riskFsm = new RiskFsm(MAX_TRADES_PER_DAY, BUDGET_LOW_FRACTION);
        this.sessionFsm = new SessionFsm();
        this.tradeFsm = new TradeFsm();
    }

    /**
     * Initialize the daily-risk state for a session. Call once before the first
     * {@link #onBarClose} of the session. The {@code now} arg is the synthetic
     * 09:29 ET reset instant; the harness uses the session-date midnight UTC
     * for stability.
     */
    public void resetForSession(
            String tenantId, LocalDate sessionDate, BigDecimal startingEquity, BigDecimal dailyLossBudget, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(startingEquity, "startingEquity");
        Objects.requireNonNull(dailyLossBudget, "dailyLossBudget");
        Objects.requireNonNull(now, "now");
        RiskFsm.Result reset = riskFsm.reset(tenantId, sessionDate, startingEquity, dailyLossBudget, now);
        this.riskState = reset.newState();
        this.riskEvents.addAll(reset.events());
    }

    /** Drive the Session FSM with a discrete event. No-op if the transition isn't legal. */
    public void onSessionEvent(SessionEvent event, Instant occurredAt) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Optional<SessionState> next = sessionFsm.next(sessionState, event);
        if (next.isPresent()) {
            sessionTransitions.add(new SessionTransition(sessionState, next.get(), event, occurredAt));
            sessionState = next.get();
        }
    }

    /**
     * Process a single 2-min bar close. Runs Signal evaluation, captures the
     * result, and fires Trade-FSM transitions when a non-SKIP signal lands and
     * Risk is not HALTED. Idempotent in that calling twice with the same args
     * appends a duplicate evaluation — the harness's two-run determinism check
     * relies on that exactness.
     */
    public void onBarClose(Bar bar, IndicatorSnapshot snap, Levels levels) {
        Objects.requireNonNull(bar, "bar");
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(levels, "levels");
        SignalEvaluation eval = signalEvaluator.evaluate(bar, snap, levels);
        evaluations.add(eval);

        // Trade FSM gating — only act on a real entry signal AND only if Risk allows.
        if (eval.action() == SignalAction.SKIP) {
            return;
        }
        if (riskState != null && riskState.state() == com.levelsweep.shared.domain.risk.RiskState.HALTED) {
            return;
        }
        // Bookkeeping for new entry: only start one if no in-flight trade.
        if (activeTrade != null) {
            return;
        }
        String tradeId = "trade-" + bar.openTime().toEpochMilli();
        // PROPOSED → ENTERED on RISK_APPROVED — synthesised here.
        Optional<TradeState> entered = tradeFsm.next(TradeState.PROPOSED, TradeEvent.RISK_APPROVED);
        if (entered.isEmpty()) {
            return;
        }
        tradeTransitions.add(
                new TradeTransition(tradeId, TradeState.PROPOSED, entered.get(), TradeEvent.RISK_APPROVED, bar.closeTime()));
        // ENTERED → ACTIVE on FILL_CONFIRMED — synthesised here for parity audit.
        Optional<TradeState> active = tradeFsm.next(entered.get(), TradeEvent.FILL_CONFIRMED);
        if (active.isPresent()) {
            tradeTransitions.add(new TradeTransition(
                    tradeId, entered.get(), active.get(), TradeEvent.FILL_CONFIRMED, bar.closeTime()));
            activeTrade = new InFlightTrade(tradeId, active.get());
        }
        // Bump risk's tradesTaken — and if cap hit it'll halt.
        if (riskState != null) {
            RiskFsm.Result trade = riskFsm.onTradeStarted(riskState, bar.closeTime());
            riskState = trade.newState();
            riskEvents.addAll(trade.events());
        }
    }

    /**
     * Apply a synthetic fill realized P&L. Negative is a loss (eats into the
     * Risk FSM's budget). Used by the optional budget-halt fixture.
     */
    public void onFillRealized(BigDecimal realizedDelta, Instant now) {
        if (riskState == null) {
            throw new IllegalStateException("resetForSession not called");
        }
        RiskFsm.Result r = riskFsm.onFillRealized(riskState, realizedDelta, now);
        riskState = r.newState();
        riskEvents.addAll(r.events());
        // Close in-flight trade synthetically — exit fill confirmed.
        if (activeTrade != null && activeTrade.state() == TradeState.ACTIVE) {
            Optional<TradeState> exiting = tradeFsm.next(activeTrade.state(), TradeEvent.STOP_HIT);
            if (exiting.isPresent()) {
                tradeTransitions.add(new TradeTransition(
                        activeTrade.tradeId(), activeTrade.state(), exiting.get(), TradeEvent.STOP_HIT, now));
                Optional<TradeState> closed = tradeFsm.next(exiting.get(), TradeEvent.EXIT_FILL_CONFIRMED);
                if (closed.isPresent()) {
                    tradeTransitions.add(new TradeTransition(
                            activeTrade.tradeId(),
                            exiting.get(),
                            closed.get(),
                            TradeEvent.EXIT_FILL_CONFIRMED,
                            now));
                }
            }
            activeTrade = null;
        }
    }

    /** Force-flatten any in-flight trade at EOD. */
    public void onEndOfDay(Instant now) {
        if (activeTrade != null && activeTrade.state() == TradeState.ACTIVE) {
            Optional<TradeState> exiting = tradeFsm.next(activeTrade.state(), TradeEvent.EOD_FLATTEN);
            if (exiting.isPresent()) {
                tradeTransitions.add(new TradeTransition(
                        activeTrade.tradeId(), activeTrade.state(), exiting.get(), TradeEvent.EOD_FLATTEN, now));
                Optional<TradeState> closed = tradeFsm.next(exiting.get(), TradeEvent.EXIT_FILL_CONFIRMED);
                if (closed.isPresent()) {
                    tradeTransitions.add(new TradeTransition(
                            activeTrade.tradeId(),
                            exiting.get(),
                            closed.get(),
                            TradeEvent.EXIT_FILL_CONFIRMED,
                            now));
                }
            }
            activeTrade = null;
        }
    }

    public List<SignalEvaluation> evaluations() {
        return List.copyOf(evaluations);
    }

    public List<RiskEvent> riskEvents() {
        return List.copyOf(riskEvents);
    }

    public List<SessionTransition> sessionTransitions() {
        return List.copyOf(sessionTransitions);
    }

    public List<TradeTransition> tradeTransitions() {
        return List.copyOf(tradeTransitions);
    }

    public DailyRiskState riskState() {
        return riskState;
    }

    public SessionState sessionState() {
        return sessionState;
    }
}
