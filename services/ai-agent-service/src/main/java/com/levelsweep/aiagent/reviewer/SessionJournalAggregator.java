package com.levelsweep.aiagent.reviewer;

import com.levelsweep.aiagent.narrator.TradeNarrative;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates the inputs the {@link DailyReviewer} needs into one
 * {@link ReviewRequest}. Reads from Mongo only — no HTTP, no Kafka.
 *
 * <p>Inputs and their Phase-4 status:
 *
 * <ul>
 *   <li><b>Session journal</b> — wired. Reads from {@code trade_narratives}
 *       (P4-S2's collection) for the session date in {@code America/New_York}.</li>
 *   <li><b>Prior 5 days reports</b> — wired. Reads via
 *       {@link DailyReportRepository#findRecent} with limit 5. Phase 4 is a
 *       "last 5 entries" query — Phase 7 follow-up replaces this with
 *       NYSE-calendar-aware lookback so weekends + holidays are handled
 *       correctly.</li>
 *   <li><b>Signal history</b> — gap. Phase 2's signal engine produces
 *       evaluations in-process but no Mongo producer exists yet
 *       ({@code signal_history} collection is reserved for a Phase 5/6 PR
 *       on the decision-engine side). The aggregator returns an empty list
 *       and logs a TODO once per JVM.</li>
 *   <li><b>Regime context</b> — gap. {@link Optional#empty()} until Phase 5/6
 *       wires a market-context feed (architecture-spec §4.5
 *       {@code get_market_context} tool).</li>
 * </ul>
 */
@ApplicationScoped
public class SessionJournalAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(SessionJournalAggregator.class);

    /** Architecture-spec Principle #10: ET wall clock, not fixed offset. */
    public static final ZoneId AMERICA_NEW_YORK = ZoneId.of("America/New_York");

    /** Architecture-spec §4.3.4 — "prior 5-day comparison". */
    public static final int PRIOR_DAYS_LIMIT = 5;

    private final Instance<MongoClient> mongoClientInstance;
    private final DailyReportRepository dailyReportRepository;
    private final String databaseName;
    private final String narrativesCollection;
    private final String signalHistoryCollection;
    private final AtomicBoolean signalHistoryGapLogged = new AtomicBoolean(false);

    @Inject
    public SessionJournalAggregator(
            Instance<MongoClient> mongoClientInstance,
            DailyReportRepository dailyReportRepository,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "level_sweep") String databaseName,
            @ConfigProperty(name = "narrator.trade-narratives-collection", defaultValue = "trade_narratives")
                    String narrativesCollection,
            @ConfigProperty(name = "reviewer.signal-history-collection", defaultValue = "signal_history")
                    String signalHistoryCollection) {
        this.mongoClientInstance = mongoClientInstance;
        this.dailyReportRepository = dailyReportRepository;
        this.databaseName = databaseName;
        this.narrativesCollection = narrativesCollection;
        this.signalHistoryCollection = signalHistoryCollection;
    }

    /**
     * Build a fully-populated {@link ReviewRequest} for the given
     * {@code (tenantId, sessionDate)}. Never throws; on any read failure the
     * relevant section is empty and a WARN is logged. The reviewer is
     * advisory and an aggregator failure must never block the next day's run.
     */
    public ReviewRequest aggregate(String tenantId, LocalDate sessionDate) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (sessionDate == null) {
            throw new IllegalArgumentException("sessionDate must not be null");
        }

        List<TradeNarrative> sessionJournal = readSessionJournal(tenantId, sessionDate);
        List<SignalEvaluationRecord> signalHistory = readSignalHistory(tenantId, sessionDate);
        Optional<MarketRegimeSummary> regimeContext = readRegimeContext(tenantId, sessionDate);
        List<DailyReport> priorFiveDays = dailyReportRepository.findRecent(tenantId, sessionDate, PRIOR_DAYS_LIMIT);

        return new ReviewRequest(tenantId, sessionDate, sessionJournal, signalHistory, regimeContext, priorFiveDays);
    }

    /**
     * Read all trade narratives for {@code (tenantId, sessionDate)} ordered by
     * {@code generated_at} ascending. Session date is treated as the ET
     * 00:00:00 → 23:59:59.999 window.
     */
    List<TradeNarrative> readSessionJournal(String tenantId, LocalDate sessionDate) {
        if (mongoClientInstance.isUnsatisfied()) {
            return List.of();
        }
        try {
            Instant start = sessionDate.atStartOfDay(AMERICA_NEW_YORK).toInstant();
            Instant endExclusive =
                    sessionDate.atTime(LocalTime.MAX).atZone(AMERICA_NEW_YORK).toInstant();
            Document filter = new Document("tenant_id", tenantId)
                    .append(
                            "generated_at",
                            new Document("$gte", Date.from(start)).append("$lte", Date.from(endExclusive)));
            MongoCollection<Document> coll = collection(narrativesCollection);
            List<TradeNarrative> out = new ArrayList<>();
            for (Document d : coll.find(filter).sort(new Document("generated_at", 1))) {
                out.add(toNarrative(d));
            }
            return out;
        } catch (RuntimeException e) {
            LOG.warn(
                    "session journal aggregator: trade_narratives read failed tenantId={} sessionDate={}: {}",
                    tenantId,
                    sessionDate,
                    e.toString());
            return List.of();
        }
    }

    /**
     * Read signal evaluations for the session. Phase 4 status: gap. Returns
     * an empty list until a producer wires {@code signal_history}; logs a
     * one-shot TODO at INFO so the Phase 5/6 follow-up is visible without
     * spamming logs.
     */
    List<SignalEvaluationRecord> readSignalHistory(String tenantId, LocalDate sessionDate) {
        if (mongoClientInstance.isUnsatisfied()) {
            return List.of();
        }
        try {
            // The collection itself may not exist yet in Phase 4; querying a
            // missing collection returns empty rather than throwing on
            // MongoDB. We keep the read shape so that as soon as a Phase 5/6
            // producer lands, this method picks the rows up without code
            // changes here.
            Document filter = new Document("tenant_id", tenantId).append("session_date", sessionDate.toString());
            MongoCollection<Document> coll = collection(signalHistoryCollection);
            List<SignalEvaluationRecord> out = new ArrayList<>();
            for (Document d : coll.find(filter).sort(new Document("evaluated_at", 1))) {
                SignalEvaluationRecord rec = toSignalEvaluation(d);
                if (rec != null) {
                    out.add(rec);
                }
            }
            if (out.isEmpty() && signalHistoryGapLogged.compareAndSet(false, true)) {
                LOG.info(
                        "session journal aggregator: signal_history is empty — Phase 4 gap; Phase 5/6 wires"
                                + " decision-engine producer. tenantId={} sessionDate={} collection={}",
                        tenantId,
                        sessionDate,
                        signalHistoryCollection);
            }
            return out;
        } catch (RuntimeException e) {
            LOG.warn(
                    "session journal aggregator: signal_history read failed tenantId={} sessionDate={}: {}",
                    tenantId,
                    sessionDate,
                    e.toString());
            return List.of();
        }
    }

    /**
     * Phase 4 always returns {@link Optional#empty()}. Phase 5/6 wires a real
     * regime feed (architecture-spec §4.5 {@code get_market_context}). The
     * method is kept as a hook so the wiring change is local to this class.
     */
    Optional<MarketRegimeSummary> readRegimeContext(String tenantId, LocalDate sessionDate) {
        return Optional.empty();
    }

    private static TradeNarrative toNarrative(Document d) {
        Date genAt = d.getDate("generated_at");
        return new TradeNarrative(
                d.getString("tenant_id"),
                d.getString("trade_id"),
                d.getString("narrative"),
                genAt == null ? Instant.EPOCH : genAt.toInstant(),
                d.getString("model_used"),
                d.getString("prompt_hash"));
    }

    private static SignalEvaluationRecord toSignalEvaluation(Document d) {
        // Defensive: if any field is missing or invalid, skip the row rather
        // than crash the whole aggregation.
        try {
            Date evaluatedAt = d.getDate("evaluated_at");
            return new SignalEvaluationRecord(
                    d.getString("tenant_id"),
                    LocalDate.parse(d.getString("session_date")),
                    d.getString("signal_id"),
                    evaluatedAt == null ? Instant.EPOCH : evaluatedAt.toInstant(),
                    SignalEvaluationRecord.Side.valueOf(d.getString("side")),
                    SignalEvaluationRecord.LevelType.valueOf(d.getString("level_type")),
                    SignalEvaluationRecord.Outcome.valueOf(d.getString("outcome")),
                    d.getString("reason_code") == null ? "" : d.getString("reason_code"),
                    d.getString("correlation_id") == null ? "" : d.getString("correlation_id"));
        } catch (RuntimeException e) {
            LOG.warn("session journal aggregator: skipping malformed signal_history row: {}", e.toString());
            return null;
        }
    }

    private MongoCollection<Document> collection(String name) {
        return mongoClientInstance.get().getDatabase(databaseName).getCollection(name);
    }
}
