package com.levelsweep.execution.eod;

import com.levelsweep.execution.persistence.EodFlattenAuditRepository;
import com.levelsweep.execution.state.InFlightTradeCache;
import com.levelsweep.execution.state.OrderSubmitter;
import com.levelsweep.shared.domain.trade.EodFlattenAttempt;
import com.levelsweep.shared.domain.trade.InFlightTrade;
import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import com.levelsweep.shared.domain.trade.TradeEodFlattened;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3 Step 6 — End-of-Day flatten saga. At {@code 15:55:00 ET} every day,
 * force-close every open trade with a market sell order so 0DTE positions do
 * not auto-exercise at 16:00 ET.
 *
 * <p>Cron expression {@code "0 55 15 * * ?"} fires at HH:55:00 with HH=15
 * (3 PM in the {@code America/New_York} zone — Quarkus / Quartz handles DST
 * transitions transparently). The saga runs every day of the week — the
 * in-flight cache is empty outside trading sessions so weekend invocations
 * are cheap no-ops, and per architecture-spec §14 we explicitly do not
 * special-case half-day NYSE schedules in Phase 3 (operator manually halts
 * via the Risk FSM if needed).
 *
 * <p>Per-fire flow:
 *
 * <ol>
 *   <li>Read {@link Clock#instant()} once and pass that instant through every
 *       audit row + event emitted in the fire — keeps replay deterministic.
 *   <li>Resolve the {@link OrderSubmitter} via {@link Instance} so this PR can
 *       land before Phase 3 Step 2's {@code AlpacaTradingClient}. If the bean
 *       is unresolved, log a WARN and return — the saga is in stub mode and
 *       does not crash the JVM.
 *   <li>Snapshot {@link InFlightTradeCache} and iterate. Each trade gets a
 *       deterministic {@code clientOrderId = "eod:" + tenantId + ":" + tradeId}
 *       so a JVM restart inside the cushion window cannot double-submit
 *       (Alpaca rejects duplicate client_order_id with 422).
 *   <li>Submit a {@code MARKET SELL DAY} {@link OrderRequest}. On success: fire
 *       {@link TradeEodFlattened}, persist a {@code FLATTENED} audit row,
 *       remove from the cache. On failure: catch the exception, persist a
 *       {@code FAILED} audit row, leave the cache untouched, do NOT propagate
 *       — the next trade in the snapshot still gets a chance to flatten.
 * </ol>
 *
 * <p>Determinism: the scheduler reads the clock exactly once per fire and
 * threads that {@link Instant} through every audit row + event. Critical for
 * the Phase 3 Step 7 replay-parity harness.
 */
@ApplicationScoped
public class EodFlattenScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(EodFlattenScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final Clock clock;
    private final InFlightTradeCache cache;
    private final Instance<OrderSubmitter> submitterInstance;
    private final Event<TradeEodFlattened> flattenedEvent;
    private final EodFlattenAuditRepository audit;

    @Inject
    public EodFlattenScheduler(
            Clock clock,
            InFlightTradeCache cache,
            Instance<OrderSubmitter> submitterInstance,
            Event<TradeEodFlattened> flattenedEvent,
            EodFlattenAuditRepository audit) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.submitterInstance = Objects.requireNonNull(submitterInstance, "submitterInstance");
        this.flattenedEvent = Objects.requireNonNull(flattenedEvent, "flattenedEvent");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Cron handler — fires at 15:55:00 ET daily. The handler delegates to
     * {@link #runOnce()} so unit tests can drive the saga directly without the
     * Quarkus scheduler harness.
     */
    @Scheduled(cron = "0 55 15 * * ?", timeZone = "America/New_York", identity = "eod-flatten")
    void onCron() {
        runOnce();
    }

    /**
     * Test-visible body. One read of {@link Clock#instant()} per fire — the
     * resulting {@link Instant} is threaded through every audit row + CDI
     * event so replay parity holds.
     */
    void runOnce() {
        Instant now = clock.instant();
        LocalDate sessionDate = LocalDate.now(clock.withZone(ET));

        if (submitterInstance.isUnsatisfied()) {
            LOG.warn(
                    "eod scheduler running in stub mode — no OrderSubmitter bean resolvable; sessionDate={}",
                    sessionDate);
            return;
        }

        OrderSubmitter submitter = submitterInstance.get();
        Collection<InFlightTrade> trades = cache.snapshot();
        if (trades.isEmpty()) {
            LOG.info("eod flatten: no in-flight trades; skipping sessionDate={}", sessionDate);
            return;
        }

        LOG.info("eod flatten: starting sessionDate={} tradeCount={}", sessionDate, trades.size());
        int flattened = 0;
        int failed = 0;
        for (InFlightTrade t : trades) {
            boolean ok = flattenOne(submitter, t, now, sessionDate);
            if (ok) {
                flattened++;
            } else {
                failed++;
            }
        }
        LOG.info(
                "eod flatten: complete sessionDate={} flattened={} failed={} total={}",
                sessionDate,
                flattened,
                failed,
                trades.size());
    }

    /**
     * Flatten one trade. Returns {@code true} on a successful submission. The
     * submitter returns a {@link OrderSubmission} sealed sum type — broker
     * rejection ({@link OrderSubmission.Rejected}) and transport failure
     * ({@link OrderSubmission.FailedWithError}) are values, not exceptions.
     * The {@code try/catch} is a safety net for an unexpected throw inside the
     * submitter (e.g. NPE, never expected); any thrown exception still ends as
     * a {@code FAILED} audit row so a single broker hiccup does not stop the
     * rest of the snapshot from being processed.
     */
    private boolean flattenOne(OrderSubmitter submitter, InFlightTrade t, Instant now, LocalDate sessionDate) {
        String clientOrderId = "eod:" + t.tenantId() + ":" + t.tradeId();
        OrderRequest exit = new OrderRequest(
                t.tenantId(),
                t.tradeId(),
                t.contractSymbol(),
                t.quantity(),
                "sell",
                "market",
                Optional.empty(),
                "day",
                clientOrderId);

        OrderSubmission outcome;
        try {
            outcome = submitter.submit(exit);
        } catch (RuntimeException e) {
            LOG.warn(
                    "eod flatten: trade FAILED (unexpected throw) tenantId={} tradeId={} contractSymbol={} clientOrderId={}: {}",
                    t.tenantId(),
                    t.tradeId(),
                    t.contractSymbol(),
                    clientOrderId,
                    e.toString());
            recordAudit(new EodFlattenAttempt(
                    t.tenantId(),
                    sessionDate,
                    now,
                    t.tradeId(),
                    t.contractSymbol(),
                    EodFlattenAttempt.Outcome.FAILED,
                    Optional.empty(),
                    Optional.of(truncate(e.toString(), 256))));
            return false;
        }

        return switch (outcome) {
            case OrderSubmission.Submitted s -> {
                recordAudit(new EodFlattenAttempt(
                        t.tenantId(),
                        sessionDate,
                        now,
                        t.tradeId(),
                        t.contractSymbol(),
                        EodFlattenAttempt.Outcome.FLATTENED,
                        Optional.of(s.alpacaOrderId()),
                        Optional.empty()));
                flattenedEvent.fire(
                        new TradeEodFlattened(t.tenantId(), t.tradeId(), s.alpacaOrderId(), now, t.correlationId()));
                cache.remove(t.tradeId());
                LOG.info(
                        "eod flatten: trade flattened tenantId={} tradeId={} contractSymbol={} clientOrderId={} alpacaOrderId={}",
                        t.tenantId(),
                        t.tradeId(),
                        t.contractSymbol(),
                        clientOrderId,
                        s.alpacaOrderId());
                yield true;
            }
            case OrderSubmission.Rejected r -> {
                LOG.warn(
                        "eod flatten: trade FAILED (rejected) tenantId={} tradeId={} contractSymbol={} clientOrderId={} httpStatus={} reason={}",
                        t.tenantId(),
                        t.tradeId(),
                        t.contractSymbol(),
                        clientOrderId,
                        r.httpStatus(),
                        r.reason());
                recordAudit(new EodFlattenAttempt(
                        t.tenantId(),
                        sessionDate,
                        now,
                        t.tradeId(),
                        t.contractSymbol(),
                        EodFlattenAttempt.Outcome.FAILED,
                        Optional.empty(),
                        Optional.of(truncate("rejected " + r.httpStatus() + ": " + r.reason(), 256))));
                yield false;
            }
            case OrderSubmission.FailedWithError f -> {
                LOG.warn(
                        "eod flatten: trade FAILED (transport) tenantId={} tradeId={} contractSymbol={} clientOrderId={}: {}",
                        t.tenantId(),
                        t.tradeId(),
                        t.contractSymbol(),
                        clientOrderId,
                        f.exceptionMessage());
                recordAudit(new EodFlattenAttempt(
                        t.tenantId(),
                        sessionDate,
                        now,
                        t.tradeId(),
                        t.contractSymbol(),
                        EodFlattenAttempt.Outcome.FAILED,
                        Optional.empty(),
                        Optional.of(truncate(f.exceptionMessage(), 256))));
                yield false;
            }
        };
    }

    /**
     * Persist an audit row, swallowing repository exceptions. The audit row is
     * a journal — we never want a DB hiccup to short-circuit the saga's main
     * loop. The repository already logs WARN on SQL failures.
     */
    private void recordAudit(EodFlattenAttempt attempt) {
        try {
            audit.record(attempt);
        } catch (RuntimeException e) {
            LOG.warn(
                    "eod flatten: audit persistence failed tenantId={} tradeId={} outcome={}: {}",
                    attempt.tenantId(),
                    attempt.tradeId(),
                    attempt.outcome(),
                    e.toString());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
