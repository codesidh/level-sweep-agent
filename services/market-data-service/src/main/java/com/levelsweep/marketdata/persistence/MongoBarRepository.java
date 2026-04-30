package com.levelsweep.marketdata.persistence;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mongo persistence for raw OHLCV bars (architecture-spec §13.2). Append-only writes
 * keyed by {@code (tenantId, symbol, timeframe, openTime)} with a 30-day TTL on
 * {@code insertedAt}.
 *
 * <p>Decimal pricing fields are stored as {@code String} (BigDecimal#toPlainString) to
 * avoid lossy {@code Decimal128} conversions and keep the on-disk shape obvious to ops.
 *
 * <p>Indexes are created lazily on first write (no {@code @PostConstruct}) so the bean
 * boots cleanly when Mongo is unavailable in CI / local dev.
 */
@ApplicationScoped
public class MongoBarRepository {

    private static final Logger LOG = LoggerFactory.getLogger(MongoBarRepository.class);
    private static final String COLLECTION = "bars_raw";
    private static final long TTL_SECONDS = 30L * 24L * 3600L;

    private final MongoClient mongoClient;
    private final String databaseName;
    private final String tenantId;
    private final AtomicBoolean indexesEnsured = new AtomicBoolean(false);

    @Inject
    public MongoBarRepository(
            MongoClient mongoClient,
            @ConfigProperty(name = "levelsweep.mongo.database", defaultValue = "level_sweep") String databaseName,
            @ConfigProperty(name = "tenant.id", defaultValue = "OWNER") String tenantId) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.tenantId = tenantId;
    }

    /** Write a single bar. Best-effort: failures log + return without throwing. */
    public void save(Bar bar) {
        ensureIndexes();
        Document doc = toDocument(bar, tenantId, Instant.now());
        try {
            bars().insertOne(doc);
        } catch (RuntimeException e) {
            // Mongo's unique index will reject restart-time duplicates; that's expected
            // and not an error. Other failures are logged at WARN — the bar fan-out must
            // never block on a persistence blip.
            LOG.warn(
                    "mongo bar insert failed tenantId={} symbol={} timeframe={} openTime={}: {}",
                    tenantId,
                    bar.symbol(),
                    bar.timeframe(),
                    bar.openTime(),
                    e.toString());
        }
    }

    /**
     * Read bars for a given {@code (tenantId, symbol, timeframe)} within the half-open
     * window {@code [start, endExclusive)}, sorted by {@code openTime} ascending. Returns
     * an empty list on any read error so callers (the {@link
     * com.levelsweep.marketdata.live.SessionLevelScheduler} primarily) can degrade
     * gracefully when Mongo is offline.
     */
    public List<Bar> findBarsByWindow(
            String tenantId, String symbol, Timeframe timeframe, Instant start, Instant endExclusive) {
        try {
            Document filter = new Document("tenantId", tenantId)
                    .append("symbol", symbol)
                    .append("timeframe", timeframe.name())
                    .append("openTime", new Document("$gte", Date.from(start)).append("$lt", Date.from(endExclusive)));
            List<Bar> out = new ArrayList<>();
            for (Document d : bars().find(filter).sort(Sorts.ascending("openTime"))) {
                out.add(fromDocument(d));
            }
            return out;
        } catch (RuntimeException e) {
            LOG.warn(
                    "mongo bar read failed tenantId={} symbol={} timeframe={} start={} end={}: {}",
                    tenantId,
                    symbol,
                    timeframe,
                    start,
                    endExclusive,
                    e.toString());
            return Collections.emptyList();
        }
    }

    /** Package-private: extracted for unit tests of document shape. */
    static Document toDocument(Bar bar, String tenantId, Instant insertedAt) {
        Document d = new Document();
        d.put("tenantId", tenantId);
        d.put("symbol", bar.symbol());
        d.put("timeframe", bar.timeframe().name());
        d.put("openTime", Date.from(bar.openTime()));
        d.put("closeTime", Date.from(bar.closeTime()));
        // BigDecimal as plain-string preserves precision losslessly. Reader side
        // (future Decision Engine, future S5 backfill) reconstructs via new BigDecimal(s).
        d.put("o", bar.open().toPlainString());
        d.put("h", bar.high().toPlainString());
        d.put("l", bar.low().toPlainString());
        d.put("c", bar.close().toPlainString());
        d.put("volume", bar.volume());
        d.put("ticks", bar.ticks());
        // TTL anchor — mongo evicts the doc 30d after this instant.
        d.put("insertedAt", Date.from(insertedAt));
        return d;
    }

    /** Reverse mapping for documents read via {@link #findBarsByWindow}. Package-private for tests. */
    static Bar fromDocument(Document d) {
        String symbol = d.getString("symbol");
        Timeframe tf = Timeframe.valueOf(d.getString("timeframe"));
        Instant openTime = ((Date) d.get("openTime")).toInstant();
        Instant closeTime = ((Date) d.get("closeTime")).toInstant();
        BigDecimal o = new BigDecimal(d.getString("o"));
        BigDecimal h = new BigDecimal(d.getString("h"));
        BigDecimal l = new BigDecimal(d.getString("l"));
        BigDecimal c = new BigDecimal(d.getString("c"));
        long volume = d.getLong("volume");
        long ticks = d.getLong("ticks");
        return new Bar(symbol, tf, openTime, closeTime, o, h, l, c, volume, ticks);
    }

    private MongoCollection<Document> bars() {
        return mongoClient.getDatabase(databaseName).getCollection(COLLECTION);
    }

    private void ensureIndexes() {
        if (!indexesEnsured.compareAndSet(false, true)) {
            return;
        }
        try {
            MongoCollection<Document> coll = bars();
            coll.createIndex(
                    Indexes.ascending("tenantId", "symbol", "timeframe", "openTime"),
                    new IndexOptions().unique(true).name("uniq_tenant_symbol_timeframe_openTime"));
            coll.createIndex(
                    Indexes.ascending("insertedAt"),
                    new IndexOptions()
                            .expireAfter(TTL_SECONDS, TimeUnit.SECONDS)
                            .name("ttl_inserted_at"));
        } catch (RuntimeException e) {
            // If Mongo is unreachable at the moment of first write, reset the flag so a
            // later write retries. Persistent failure is logged at warn.
            indexesEnsured.set(false);
            LOG.warn("mongo index creation failed (will retry on next write): {}", e.toString());
        }
    }
}
