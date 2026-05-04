package com.levelsweep.aiagent.sentinel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Pre-Trade Sentinel input contract — the deterministic tuple the saga
 * correlates from Decision Engine state and hands to the Sentinel before
 * StrikeSelector resolves the OCC symbol (ADR-0007 §1).
 *
 * <p><b>Determinism contract</b>: this record IS the replay-parity seed. The
 * replay harness records the full {@code (tenantId, signalId,
 * indicatorSnapshot, recentTradesWindow, vixClosePrev, nowUtc)} tuple plus the
 * recorded Sentinel response; the runner injects the recorded response by tuple
 * hash without making an HTTP call (ADR-0007 §5). Any change to the field
 * shape, default value, or list-iteration order is a replay-breaking change.
 *
 * <p>Validation in the compact constructor is loud-and-early: a malformed
 * request from the saga must not silently degrade into a malformed prompt that
 * the LLM "interprets" — the audit trail would lose the determinism contract.
 *
 * @param tenantId            per-tenant cost-cap + audit scope (never blank)
 * @param tradeId             saga correlation key
 * @param signalId            Decision Engine signal evaluator key
 * @param direction           LONG_CALL or LONG_PUT
 * @param levelSwept          PDH / PDL / PMH / PML
 * @param indicatorSnapshot   EMAs + ATR + RSI + regime + last 12 2-min bars
 * @param recentTradesWindow  last 5 trades for the tenant (for losing-streak context)
 * @param vixClosePrev        yesterday's VIX close (regime context)
 * @param nowUtc              trading-clock anchor (replay-stable; NOT wall clock)
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

    /** Maximum bars carried in the indicator snapshot — fixed by ADR-0007 §1. */
    public static final int MAX_RECENT_BARS = 12;

    /** Maximum recent trades — fixed by ADR-0007 §1. */
    public static final int MAX_RECENT_TRADES = 5;

    /** Trade direction — long-call or long-put 0DTE option. */
    public enum Direction {
        LONG_CALL,
        LONG_PUT
    }

    /** Level the price action swept to trigger the signal. */
    public enum LevelSwept {
        PDH,
        PDL,
        PMH,
        PML
    }

    /** Outcome bucket for a recent trade summary. */
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
        // Defensive copy — same justification as AnthropicRequest: the prompt
        // builder iterates this list and downstream mutation would corrupt
        // both the audit prompt-hash determinism and the on-the-wire prompt.
        recentTradesWindow = List.copyOf(recentTradesWindow);
    }

    /**
     * Indicator state at signal time. The {@link #recentBars} list is the
     * trailing 12 bars on the 2-min chart — same window the IndicatorEngine
     * uses for regime classification.
     */
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

    /** Single 2-min bar — close + volume only (HLC isn't fed to the Sentinel). */
    public record Bar(Instant ts, BigDecimal close, long volume) {
        public Bar {
            Objects.requireNonNull(ts, "ts");
            Objects.requireNonNull(close, "close");
            if (volume < 0) {
                throw new IllegalArgumentException("volume must be non-negative");
            }
        }
    }

    /** One row in the trailing-5 trade window. R-multiple is signed (loss = negative). */
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
