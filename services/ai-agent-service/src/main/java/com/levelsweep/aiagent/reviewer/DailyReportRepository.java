package com.levelsweep.aiagent.reviewer;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mongo persistence for {@link DailyReport} records — architecture-spec §13.2
 * row {@code journal.daily_reports}. Mirrors the
 * {@link com.levelsweep.aiagent.narrator.TradeNarrativeRepository} pattern:
 * sync MongoClient, {@link Instance} wrapper for stub-mode dev/test, lazy
 * index creation on first write.
 *
 * <p>Schema (BSON):
 *
 * <pre>
 *   { tenant_id         : "OWNER",
 *     session_date      : "2026-05-02",        // ET local date as ISO string
 *     summary           : "Today's session ...",
 *     anomalies         : ["..."],              // empty in Phase A
 *     proposals         : [...],                // empty in Phase A (advisory only)
 *     outcome           : "COMPLETED",          // or SKIPPED_COST_CAP / FAILED
 *     model_used        : "claude-opus-4-7",
 *     prompt_hash       : "9f86d081...",        // SHA-256 hex
 *     total_tokens_used : 11250,
 *     cost_usd          : "0.3018",             // BigDecimal#toPlainString
 *     generated_at      : ISODate(...) }        // UTC instant
 * </pre>
 *
 * <p>Index: {@code (tenant_id, session_date desc)} so the dashboard query
 * "show the last N reports for tenant X" hits a covered index.
 *
 * <p>Best-effort writes — failures log + return so a transient Mongo blip
 * never propagates out of the daily reviewer scheduler. The reviewer is
 * advisory; an audit-row write failure must never crash the JVM.
 */
@ApplicationScoped
public class DailyReportRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DailyReportRepository.class);

    private final Instance<MongoClient> mongoClientInstance;
    private final String databaseName;
    private final String collectionName;
    private final AtomicBoolean indexEnsured = new AtomicBoolean(false);

    @Inject
    public DailyReportRepository(
            Instance<MongoClient> mongoClientInstance,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "level_sweep") String databaseName,
            @ConfigProperty(name = "reviewer.daily-reports-collection", defaultValue = "daily_reports")
                    String collectionName) {
        this.mongoClientInstance = mongoClientInstance;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    /**
     * Persist one report. Best-effort; logs + returns on Mongo failure
     * (callers — i.e. the scheduler — also swallow, by design).
     */
    public void save(DailyReport report) {
        if (report == null) {
            return;
        }
        if (mongoClientInstance.isUnsatisfied()) {
            LOG.warn(
                    "daily_reports repository running in stub mode — no MongoClient;"
                            + " tenantId={} sessionDate={} outcome={}",
                    report.tenantId(),
                    report.sessionDate(),
                    report.outcome());
            return;
        }
        ensureIndex();
        Document doc = toDocument(report);
        try {
            collection().insertOne(doc);
        } catch (RuntimeException e) {
            LOG.warn(
                    "daily_reports insert failed tenantId={} sessionDate={}: {}",
                    report.tenantId(),
                    report.sessionDate(),
                    e.toString());
        }
    }

    /**
     * Find the most recent reports for {@code tenantId} strictly before
     * {@code beforeDate}, sorted by {@code session_date} descending then
     * truncated to {@code limit}. Returned list is in ascending order of
     * {@code session_date} so callers (i.e. {@link SessionJournalAggregator})
     * get a chronologically-ordered "last N sessions" slice for the prompt.
     *
     * <p>Phase 4 status: this is a "last N entries" query. Phase 7 follow-up
     * adds NYSE-calendar-aware lookback so a 5-business-day window honors
     * weekends + holidays correctly. Until then, on a fresh deploy the list
     * may be shorter than 5; on a long-running deploy holidays produce
     * "5 most recent sessions" rather than "5 calendar days back".
     */
    public List<DailyReport> findRecent(String tenantId, LocalDate beforeDate, int limit) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (beforeDate == null) {
            throw new IllegalArgumentException("beforeDate must not be null");
        }
        if (limit <= 0) {
            return List.of();
        }
        if (mongoClientInstance.isUnsatisfied()) {
            return List.of();
        }
        try {
            Document filter = new Document("tenant_id", tenantId)
                    .append("session_date", new Document("$lt", beforeDate.toString()));
            FindIterable<Document> docs = collection()
                    .find(filter)
                    .sort(new Document("session_date", -1))
                    .limit(limit);
            List<DailyReport> reports = new ArrayList<>(limit);
            for (Document d : docs) {
                reports.add(fromDocument(d));
            }
            // Reverse to chronological (ascending) for the prompt.
            java.util.Collections.reverse(reports);
            return reports;
        } catch (RuntimeException e) {
            LOG.warn(
                    "daily_reports findRecent failed tenantId={} beforeDate={} limit={}: {}",
                    tenantId,
                    beforeDate,
                    limit,
                    e.toString());
            return List.of();
        }
    }

    /** Package-private: extracted for unit tests of document shape. */
    static Document toDocument(DailyReport report) {
        Document d = new Document();
        d.put("tenant_id", report.tenantId());
        d.put("session_date", report.sessionDate().toString());
        d.put("summary", report.summary());
        d.put("anomalies", List.copyOf(report.anomalies()));
        d.put(
                "proposals",
                report.proposals().stream()
                        .map(p -> new Document("change_spec", p.changeSpec())
                                .append("rationale", p.rationale())
                                .append("urgency", p.urgency().name()))
                        .toList());
        d.put("outcome", report.outcome().name());
        d.put("model_used", report.modelUsed());
        d.put("prompt_hash", report.promptHash());
        d.put("total_tokens_used", report.totalTokensUsed());
        d.put("cost_usd", report.costUsd().toPlainString());
        d.put("generated_at", Date.from(report.generatedAt()));
        return d;
    }

    /** Package-private: round-trip for the {@link #findRecent} read path. */
    @SuppressWarnings("unchecked")
    static DailyReport fromDocument(Document d) {
        List<String> anomalies = (List<String>) (List<?>) d.get("anomalies", List.class);
        List<Document> proposalDocs = (List<Document>) (List<?>) d.get("proposals", List.class);
        if (anomalies == null) {
            anomalies = List.of();
        }
        if (proposalDocs == null) {
            proposalDocs = List.of();
        }
        List<ConfigProposal> proposals = proposalDocs.stream()
                .map(p -> new ConfigProposal(
                        p.getString("change_spec"),
                        p.getString("rationale"),
                        ConfigProposal.Urgency.valueOf(p.getString("urgency"))))
                .toList();
        String outcomeStr = d.getString("outcome");
        DailyReport.Outcome outcome =
                outcomeStr == null ? DailyReport.Outcome.COMPLETED : DailyReport.Outcome.valueOf(outcomeStr);
        Date generatedAt = d.getDate("generated_at");
        Number totalTokens = d.get("total_tokens_used", Number.class);
        return new DailyReport(
                d.getString("tenant_id"),
                LocalDate.parse(d.getString("session_date")),
                d.getString("summary"),
                anomalies,
                proposals,
                outcome,
                generatedAt == null ? Instant.EPOCH : generatedAt.toInstant(),
                d.getString("model_used"),
                d.getString("prompt_hash"),
                totalTokens == null ? 0L : totalTokens.longValue(),
                new BigDecimal(d.getString("cost_usd")));
    }

    private MongoCollection<Document> collection() {
        return mongoClientInstance.get().getDatabase(databaseName).getCollection(collectionName);
    }

    private void ensureIndex() {
        if (!indexEnsured.compareAndSet(false, true)) {
            return;
        }
        try {
            collection()
                    .createIndex(
                            Indexes.compoundIndex(Indexes.ascending("tenant_id"), Indexes.descending("session_date")),
                            new IndexOptions().name("idx_tenant_session_date_desc"));
        } catch (RuntimeException e) {
            // Reset so the next write retries — Mongo might just be transiently down.
            indexEnsured.set(false);
            LOG.warn("daily_reports index creation failed (will retry on next write): {}", e.toString());
        }
    }
}
