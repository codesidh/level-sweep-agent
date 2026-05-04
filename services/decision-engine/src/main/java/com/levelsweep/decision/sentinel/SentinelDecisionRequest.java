package com.levelsweep.decision.sentinel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Wire-side mirror of {@code com.levelsweep.aiagent.sentinel.SentinelDecisionRequest}.
 * Decoupled by design (ADR-0007 §5) — the decision-engine module does not
 * take a Maven dependency on ai-agent-service so the wire types are
 * duplicated here and serialized via JSON. Field names + JSON property
 * names match the ai-agent-service record byte-for-byte.
 *
 * <p>This record carries the exact tuple the saga assembles between
 * RiskGate and StrikeSelector:
 *
 * <ul>
 *   <li>{@code tenantId} — per-tenant audit + cost-cap scope.</li>
 *   <li>{@code tradeId} — saga correlation key.</li>
 *   <li>{@code signalId} — Decision Engine signal evaluator key.</li>
 *   <li>{@code direction} — {@code LONG_CALL} / {@code LONG_PUT}.</li>
 *   <li>{@code levelSwept} — PDH / PDL / PMH / PML.</li>
 *   <li>{@code indicatorSnapshot} — EMAs, ATR, RSI, regime, last 12 2-min bars.</li>
 *   <li>{@code recentTradesWindow} — last 5 trades for losing-streak context.</li>
 *   <li>{@code vixClosePrev} — yesterday's VIX close.</li>
 *   <li>{@code nowUtc} — trading-clock anchor (replay-stable).</li>
 * </ul>
 *
 * <p>Validation lives in the compact constructor — the saga must build a
 * well-formed request or the call short-circuits with an
 * {@link IllegalArgumentException}. Loud-and-early — a malformed payload
 * must not silently degrade into a malformed prompt the LLM "interprets".
 */
public record SentinelDecisionRequest(
        String tenantId,
        String tradeId,
        String signalId,
        Direction direction,
        LevelSwept levelSwept,
        IndicatorSnapshot indicatorSnapshot,
        List<RecentTrade> recentTradesWindow,
        BigDecimal vixClosePrev,
        Instant nowUtc) {

    public static final int MAX_RECENT_BARS = 12;
    public static final int MAX_RECENT_TRADES = 5;

    public enum Direction {
        LONG_CALL,
        LONG_PUT
    }

    public enum LevelSwept {
        PDH,
        PDL,
        PMH,
        PML
    }

    public enum Outcome {
        WIN,
        LOSS,
        BE
    }

    public SentinelDecisionRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(signalId, "signalId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(levelSwept, "levelSwept");
        Objects.requireNonNull(indicatorSnapshot, "indicatorSnapshot");
        Objects.requireNonNull(recentTradesWindow, "recentTradesWindow");
        Objects.requireNonNull(vixClosePrev, "vixClosePrev");
        Objects.requireNonNull(nowUtc, "nowUtc");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (signalId.isBlank()) {
            throw new IllegalArgumentException("signalId must not be blank");
        }
        if (recentTradesWindow.size() > MAX_RECENT_TRADES) {
            throw new IllegalArgumentException(
                    "recentTradesWindow exceeds " + MAX_RECENT_TRADES + " entries: " + recentTradesWindow.size());
        }
        recentTradesWindow = List.copyOf(recentTradesWindow);
    }

    /** Indicator state at signal time. */
    public record IndicatorSnapshot(
            BigDecimal ema13,
            BigDecimal ema48,
            BigDecimal ema200,
            BigDecimal atr14,
            BigDecimal rsi2,
            String regime,
            List<Bar> recentBars) {

        public IndicatorSnapshot {
            Objects.requireNonNull(ema13, "ema13");
            Objects.requireNonNull(ema48, "ema48");
            Objects.requireNonNull(ema200, "ema200");
            Objects.requireNonNull(atr14, "atr14");
            Objects.requireNonNull(rsi2, "rsi2");
            Objects.requireNonNull(regime, "regime");
            Objects.requireNonNull(recentBars, "recentBars");
            if (regime.isBlank()) {
                throw new IllegalArgumentException("regime must not be blank");
            }
            if (recentBars.size() > MAX_RECENT_BARS) {
                throw new IllegalArgumentException(
                        "recentBars exceeds " + MAX_RECENT_BARS + " entries: " + recentBars.size());
            }
            recentBars = List.copyOf(recentBars);
        }
    }

    /** Single 2-min bar — close + volume only. */
    public record Bar(Instant ts, BigDecimal close, long volume) {
        public Bar {
            Objects.requireNonNull(ts, "ts");
            Objects.requireNonNull(close, "close");
            if (volume < 0) {
                throw new IllegalArgumentException("volume must be non-negative");
            }
        }
    }

    /** One row in the trailing-5 trade window. */
    public record RecentTrade(String tradeId, Outcome outcome, BigDecimal rMultiple, Instant ts) {
        public RecentTrade {
            Objects.requireNonNull(tradeId, "tradeId");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(rMultiple, "rMultiple");
            Objects.requireNonNull(ts, "ts");
            if (tradeId.isBlank()) {
                throw new IllegalArgumentException("tradeId must not be blank");
            }
        }
    }
}
