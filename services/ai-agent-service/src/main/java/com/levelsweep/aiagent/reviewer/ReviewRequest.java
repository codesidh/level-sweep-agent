package com.levelsweep.aiagent.reviewer;

import com.levelsweep.aiagent.narrator.TradeNarrative;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregated inputs to {@link DailyReviewer#review(ReviewRequest)} for one
 * session. The {@link SessionJournalAggregator} builds this from Mongo;
 * the reviewer treats the inputs as read-only.
 *
 * <p><b>Determinism contract</b>: the canonical input ordering inside
 * {@link ReviewerPromptBuilder} depends on the order of these collections.
 * The aggregator returns rows sorted by {@code generatedAt} / {@code sessionDate}
 * ascending so the prompt body — and therefore the prompt hash — is byte-stable
 * across replays of the same Mongo state (architecture-spec Principle #2).
 *
 * <p><b>Phase 4 status of each input</b>:
 *
 * <ul>
 *   <li>{@code sessionJournal} — wired (P4-S2 narrator persists to
 *       {@code trade_narratives}).</li>
 *   <li>{@code signalHistory} — gap. Phase 2 produces signal evaluations
 *       in-process but no Mongo producer exists yet. The aggregator returns
 *       an empty list and logs a TODO; Phase 5/6 wires the producer side.</li>
 *   <li>{@code regimeContext} — gap. {@link Optional#empty()} until Phase 5/6
 *       wires a market-context feed.</li>
 *   <li>{@code priorFiveDays} — wired (this PR's
 *       {@link DailyReportRepository#findRecent} returns the last N reports;
 *       Phase 7 follow-up adds NYSE-calendar-aware lookback for "5 business
 *       days" rather than "last 5 entries").</li>
 * </ul>
 *
 * @param tenantId       multi-tenant scope (architecture-spec §22 #4)
 * @param sessionDate    ET local date of the session being reviewed
 * @param sessionJournal trade narratives produced during the session, sorted
 *                       by {@code generatedAt} ascending
 * @param signalHistory  signal evaluations during the session (may be empty
 *                       in Phase 4 — see status note above), sorted by
 *                       {@code evaluatedAt} ascending
 * @param regimeContext  optional regime snapshot (Phase 5/6)
 * @param priorFiveDays  prior session reports for trend comparison, sorted by
 *                       {@code sessionDate} ascending
 */
public record ReviewRequest(
        String tenantId,
        LocalDate sessionDate,
        List<TradeNarrative> sessionJournal,
        List<SignalEvaluationRecord> signalHistory,
        Optional<MarketRegimeSummary> regimeContext,
        List<DailyReport> priorFiveDays) {

    public ReviewRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(sessionJournal, "sessionJournal");
        Objects.requireNonNull(signalHistory, "signalHistory");
        Objects.requireNonNull(regimeContext, "regimeContext");
        Objects.requireNonNull(priorFiveDays, "priorFiveDays");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        // Defensive copies — the lists are iterated by the prompt builder and
        // any downstream mutation would corrupt the prompt hash determinism.
        sessionJournal = List.copyOf(sessionJournal);
        signalHistory = List.copyOf(signalHistory);
        priorFiveDays = List.copyOf(priorFiveDays);
    }
}
