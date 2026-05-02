package com.levelsweep.aiagent.cost;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-(tenant, role, day) AI cost accumulator with a HARD pre-flight cap
 * (architecture-spec §4.8 + §4.9; {@code ai-prompt-management} skill rule #4).
 *
 * <p>Caps are configured under {@code anthropic.cost-cap-usd-per-tenant-per-day.{role}}
 * in {@code application.yml} and read once at construction.
 *
 * <p>State model:
 *
 * <ul>
 *   <li>In-memory {@link ConcurrentHashMap} keyed by {@code (tenantId, role, date)}
 *       — fast pre-flight check on every Anthropic call.</li>
 *   <li>Write-through to {@link DailyCostMongoRepository} on every
 *       {@code recordCost} so the cap survives JVM restarts.</li>
 *   <li>Lazy bootstrap: the first {@code wouldExceedCap}/{@code recordCost}
 *       for a given {@code (tenant, role, date)} key seeds the in-memory bucket
 *       from Mongo via {@link DailyCostMongoRepository#sumByDay}. Subsequent
 *       calls hit only the in-memory map.</li>
 * </ul>
 *
 * <p>Wall-clock discipline (architecture-spec Principle #10): the date roll-over
 * runs against {@code America/New_York} ZoneId, not UTC. A 23:59 ET call and a
 * 00:01 ET call land in different daily buckets even though they're 2 minutes
 * apart in absolute time. The {@link Clock} is injected so tests can pin time.
 */
@ApplicationScoped
public class DailyCostTracker {

    private static final Logger LOG = LoggerFactory.getLogger(DailyCostTracker.class);

    /** Architecture-spec Principle #10: ET wall clock, not fixed offset. */
    public static final ZoneId AMERICA_NEW_YORK = ZoneId.of("America/New_York");

    private final DailyCostMongoRepository repository;
    private final Clock clock;
    private final Map<Role, BigDecimal> caps;

    /** {@code (tenantId, role, date) -> accumulated cost (USD)}. */
    private final ConcurrentHashMap<Key, BigDecimal> spend = new ConcurrentHashMap<>();

    /** Mongo seed status per key — keeps the lazy seed call to once-per-key. */
    private final ConcurrentHashMap<Key, Boolean> seeded = new ConcurrentHashMap<>();

    @Inject
    public DailyCostTracker(
            DailyCostMongoRepository repository,
            Clock clock,
            @ConfigProperty(name = "anthropic.cost-cap-usd-per-tenant-per-day.sentinel", defaultValue = "1.00")
                    BigDecimal sentinelCap,
            @ConfigProperty(name = "anthropic.cost-cap-usd-per-tenant-per-day.narrator", defaultValue = "1.00")
                    BigDecimal narratorCap,
            @ConfigProperty(name = "anthropic.cost-cap-usd-per-tenant-per-day.assistant", defaultValue = "5.00")
                    BigDecimal assistantCap,
            @ConfigProperty(name = "anthropic.cost-cap-usd-per-tenant-per-day.reviewer", defaultValue = "2.00")
                    BigDecimal reviewerCap) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<Role, BigDecimal> capsMap = new EnumMap<>(Role.class);
        capsMap.put(Role.SENTINEL, requireNonNegative(sentinelCap, "sentinelCap"));
        capsMap.put(Role.NARRATOR, requireNonNegative(narratorCap, "narratorCap"));
        capsMap.put(Role.ASSISTANT, requireNonNegative(assistantCap, "assistantCap"));
        capsMap.put(Role.REVIEWER, requireNonNegative(reviewerCap, "reviewerCap"));
        this.caps = Map.copyOf(capsMap);
    }

    /** Today's local date in America/New_York per the injected {@link Clock}. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), AMERICA_NEW_YORK);
    }

    /** Returns the cap (USD) configured for the given role. */
    public BigDecimal capFor(Role role) {
        return caps.get(role);
    }

    /**
     * Pre-flight check: {@code true} if {@code currentSpend + projectedCallCostUsd > cap}.
     * Does NOT mutate the accumulator — the caller invokes
     * {@link #recordCost(String, Role, LocalDate, BigDecimal)} only after a
     * successful Anthropic call (or, for replay-parity reasons, after a synthetic
     * cost-reconciliation step).
     */
    public boolean wouldExceedCap(String tenantId, Role role, LocalDate date, BigDecimal projectedCallCostUsd) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(projectedCallCostUsd, "projectedCallCostUsd");
        BigDecimal cap = caps.get(role);
        if (cap == null) {
            // Unknown role — fail-closed (skip the call) rather than allowing
            // unbudgeted spend.
            LOG.warn("daily cost tracker has no cap configured for role={}", role);
            return true;
        }
        BigDecimal current = currentSpend(tenantId, role, date);
        BigDecimal projected = current.add(projectedCallCostUsd);
        return projected.compareTo(cap) > 0;
    }

    /**
     * Append cost to the in-memory accumulator AND persist via the Mongo repo.
     * Concurrent calls for the same key safely accumulate via
     * {@link ConcurrentHashMap#compute}.
     */
    public BigDecimal recordCost(String tenantId, Role role, LocalDate date, BigDecimal costUsd) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(costUsd, "costUsd");
        if (costUsd.signum() < 0) {
            throw new IllegalArgumentException("costUsd must be non-negative");
        }
        Key key = new Key(tenantId, role, date);
        seedFromMongoIfNeeded(key);
        BigDecimal newTotal = spend.compute(key, (k, prev) -> (prev == null ? BigDecimal.ZERO : prev).add(costUsd));
        // Best-effort persistence — failures don't block (the in-memory cap
        // still holds for the JVM's lifetime; repository logs warn). Write
        // is per-call so partial-day state survives a crash.
        repository.append(tenantId, role, date, costUsd, Instant.now(clock));
        return newTotal;
    }

    /** Read-only accessor, primarily for tests and observability. */
    public BigDecimal currentSpend(String tenantId, Role role, LocalDate date) {
        Key key = new Key(tenantId, role, date);
        seedFromMongoIfNeeded(key);
        return spend.getOrDefault(key, BigDecimal.ZERO);
    }

    /**
     * Lazy seed: on the first observation of a {@code (tenant, role, date)} key
     * within this JVM, sum the existing rows from Mongo and prime the in-memory
     * bucket. Subsequent calls hit only the local map. Stub-mode Mongo returns
     * zero, which is correct for fresh-state testing.
     */
    private void seedFromMongoIfNeeded(Key key) {
        if (seeded.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        BigDecimal seed = repository.sumByDay(key.tenantId(), key.role(), key.date());
        if (seed.signum() > 0) {
            spend.merge(key, seed, BigDecimal::add);
            LOG.info(
                    "daily cost tracker seeded from mongo tenantId={} role={} date={} seedUsd={}",
                    key.tenantId(),
                    key.role(),
                    key.date(),
                    seed.toPlainString());
        }
    }

    private static BigDecimal requireNonNegative(BigDecimal v, String name) {
        Objects.requireNonNull(v, name);
        if (v.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return v;
    }

    /** Composite key for the in-memory map. */
    record Key(String tenantId, Role role, LocalDate date) {
        Key {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(date, "date");
        }
    }
}
