package com.levelsweep.aiagent.reviewer;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact regime snapshot for one trading session — a Phase 5/6 follow-up will
 * wire this from a real market-context feed (architecture-spec §4.5
 * {@code get_market_context} tool). Phase 4 ships the type so the
 * {@link DailyReviewer} contract is stable; the {@link SessionJournalAggregator}
 * passes {@link Optional#empty()} for now.
 *
 * @param vixOpen      VIX level at 09:30 ET (closing of pre-market)
 * @param vixClose     VIX level at 16:00 ET (RTH close)
 * @param vixDelta     {@code vixClose - vixOpen} (positive = rising fear)
 * @param spxTrend     bucketed direction over the session (UP/DOWN/FLAT)
 * @param breadthRatio NYSE advancers / decliners ratio at close
 * @param notes        optional human-readable annotations (FOMC day, half-day, etc.)
 */
public record MarketRegimeSummary(
        BigDecimal vixOpen,
        BigDecimal vixClose,
        BigDecimal vixDelta,
        SpxTrend spxTrend,
        BigDecimal breadthRatio,
        Optional<String> notes) {

    /** Bucketed direction over the session. */
    public enum SpxTrend {
        UP,
        DOWN,
        FLAT
    }

    public MarketRegimeSummary {
        Objects.requireNonNull(vixOpen, "vixOpen");
        Objects.requireNonNull(vixClose, "vixClose");
        Objects.requireNonNull(vixDelta, "vixDelta");
        Objects.requireNonNull(spxTrend, "spxTrend");
        Objects.requireNonNull(breadthRatio, "breadthRatio");
        Objects.requireNonNull(notes, "notes");
    }
}
