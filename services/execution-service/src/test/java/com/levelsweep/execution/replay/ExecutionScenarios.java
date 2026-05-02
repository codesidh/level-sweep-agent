package com.levelsweep.execution.replay;

import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeProposed;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hand-labeled execution scenarios for the Phase 3 Step 7 replay-parity harness.
 * Three named scenarios cover the dominant execution paths the service must
 * handle deterministically:
 *
 * <ol>
 *   <li>{@link #happyPathLong()} — SPY 0DTE call entry, accepted by Alpaca,
 *       fills, profitable EOD flatten exit.
 *   <li>{@link #stopHitLong()} — entry fills, then a stop-breach event drives
 *       the saga to a STOP_HIT exit.
 *   <li>{@link #orderRejected()} — Alpaca returns 422 / Rejected; no fill, the
 *       trade ends in FAILED.
 * </ol>
 *
 * <p>Each scenario is a {@link ExecutionScenario} record carrying the
 * {@link TradeProposed} input plus an ordered timeline of {@link SimulatedEvent}s
 * the harness dispatches in order. The fixtures match the architecture-spec
 * §21.1 row 3 acceptance: "5+ paper sessions match replay within ±2%". This
 * builder ships the scenario shape; later soak runs populate the JSON fixture
 * corpus from real Alpaca recordings.
 *
 * <h3>Determinism contract</h3>
 *
 * <ul>
 *   <li>All instants are hardcoded ({@link #SESSION_DATE} + offsets) — no
 *       {@link Instant#now()} anywhere.
 *   <li>All numeric values are hand-picked {@link BigDecimal}s — no randomness.
 *   <li>Records use structural equality, so two runs that read this builder
 *       produce byte-identical {@link TradeProposed}s.
 * </ul>
 */
public final class ExecutionScenarios {

    /**
     * Fixed session date for every scenario. Aligns with the decision-engine
     * replay fixtures' date so cross-service replays line up on the same wall
     * clock (Phase 6 paper-session soak).
     */
    public static final LocalDate SESSION_DATE = LocalDate.of(2026, 4, 30);

    /** 09:32 ET = 13:32 UTC — first 2-min bar close after market open. */
    public static final Instant PROPOSED_AT = Instant.parse("2026-04-30T13:32:00Z");

    /** 09:32:30 ET — Alpaca order accept landing 30 seconds after submission. */
    public static final Instant ORDER_ACK_AT = Instant.parse("2026-04-30T13:32:30Z");

    /** 09:32:35 ET — fill event. */
    public static final Instant FILL_AT = Instant.parse("2026-04-30T13:32:35Z");

    /** 14:30 ET — synthetic mid-session stop-breach. */
    public static final Instant STOP_BREACH_AT = Instant.parse("2026-04-30T18:30:00Z");

    /** 15:55 ET — EOD flatten cron fire. */
    public static final Instant EOD_AT = Instant.parse("2026-04-30T19:55:00Z");

    private ExecutionScenarios() {}

    /** A scenario = one {@link TradeProposed} input + an ordered event timeline. */
    public record ExecutionScenario(String name, TradeProposed input, List<SimulatedEvent> events) {
        public ExecutionScenario {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(events, "events");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            // Defensive copy preserves ordering — required for replay determinism.
            events = List.copyOf(events);
        }
    }

    /**
     * HAPPY_PATH_LONG — SPY 0DTE 600C, accepted by Alpaca at 1.225, fills 1
     * contract at 1.23, EOD flatten at 1.85 (profitable exit).
     */
    public static ExecutionScenario happyPathLong() {
        TradeProposed input = new TradeProposed(
                "OWNER",
                "trade-happy-long",
                SESSION_DATE,
                PROPOSED_AT,
                "SPY",
                OptionSide.CALL,
                "SPY260430C00600000",
                new BigDecimal("1.20"),
                new BigDecimal("1.25"),
                new BigDecimal("1.225"),
                Optional.of(new BigDecimal("0.18")),
                Optional.of(new BigDecimal("0.50")),
                "corr-happy-long",
                List.of("pdh_sweep", "ema_stack_bullish"));
        List<SimulatedEvent> events = List.of(
                SimulatedEvent.OrderSubmissionResponse.accepted(new BigDecimal("1.225")),
                new SimulatedEvent.FillFrame(new BigDecimal("1.23"), 1, "fill"),
                new SimulatedEvent.EodTrigger(EOD_AT));
        return new ExecutionScenario("HAPPY_PATH_LONG", input, events);
    }

    /**
     * STOP_HIT_LONG — entry fills; underlying retraces and a synthetic
     * StopBreach drives the saga to a STOP_HIT exit before EOD.
     */
    public static ExecutionScenario stopHitLong() {
        TradeProposed input = new TradeProposed(
                "OWNER",
                "trade-stop-hit-long",
                SESSION_DATE,
                PROPOSED_AT,
                "SPY",
                OptionSide.CALL,
                "SPY260430C00600000",
                new BigDecimal("1.20"),
                new BigDecimal("1.25"),
                new BigDecimal("1.225"),
                Optional.of(new BigDecimal("0.18")),
                Optional.of(new BigDecimal("0.50")),
                "corr-stop-hit-long",
                List.of("pdh_sweep", "ema_stack_bullish"));
        List<SimulatedEvent> events = List.of(
                SimulatedEvent.OrderSubmissionResponse.accepted(new BigDecimal("1.225")),
                new SimulatedEvent.FillFrame(new BigDecimal("1.23"), 1, "fill"),
                new SimulatedEvent.StopBreach(new BigDecimal("598.50"), STOP_BREACH_AT));
        return new ExecutionScenario("STOP_HIT_LONG", input, events);
    }

    /**
     * ORDER_REJECTED — Alpaca returns 422 / Rejected; no FillFrame; the saga
     * ends in FAILED with no captured fill.
     */
    public static ExecutionScenario orderRejected() {
        TradeProposed input = new TradeProposed(
                "OWNER",
                "trade-rejected",
                SESSION_DATE,
                PROPOSED_AT,
                "SPY",
                OptionSide.CALL,
                "SPY260430C00600000",
                new BigDecimal("1.20"),
                new BigDecimal("1.25"),
                new BigDecimal("1.225"),
                Optional.of(new BigDecimal("0.18")),
                Optional.of(new BigDecimal("0.50")),
                "corr-rejected",
                List.of("pdh_sweep", "ema_stack_bullish"));
        List<SimulatedEvent> events = List.of(SimulatedEvent.OrderSubmissionResponse.rejected(422));
        return new ExecutionScenario("ORDER_REJECTED", input, events);
    }

    /** All three scenarios in stable order — the parameterized test source. */
    public static List<ExecutionScenario> all() {
        return List.of(happyPathLong(), stopHitLong(), orderRejected());
    }
}
