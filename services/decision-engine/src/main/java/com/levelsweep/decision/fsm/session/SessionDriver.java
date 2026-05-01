package com.levelsweep.decision.fsm.session;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wall-clock driver for the {@link SessionFsm} per requirements.md §3 / §14 / §15.
 * Each {@link #tick(String)} call inspects the ET wall clock and fires whichever
 * {@link SessionEvent} boundaries have been crossed since the previous tick for
 * that tenant. Boundaries fire at most once per session.
 *
 * <p>Phase 2 does not yet schedule {@code tick()} from a Quarkus
 * {@code @Scheduled} — the Trade Saga (S6) is the first caller. The boundary
 * timestamps are kept here as constants so a later scheduler thread can call
 * {@code tick} on a fixed cadence (e.g. once per second during RTH) without
 * needing knowledge of the strategy timeline.
 *
 * <p>Boundary semantics: a boundary fires the first time the ET wall clock is
 * &gt;= the boundary time on a given session date. State is reset on date change.
 */
@ApplicationScoped
public class SessionDriver {

    private static final Logger LOG = LoggerFactory.getLogger(SessionDriver.class);

    /** 09:29:30 ET — reference levels finalized (requirements.md §4). */
    public static final LocalTime LEVELS_READY_AT = LocalTime.of(9, 29, 30);

    /** 09:30:00 ET — RTH market open. */
    public static final LocalTime MARKET_OPEN_AT = LocalTime.of(9, 30, 0);

    /** 15:55:00 ET — EOD hard close (requirements.md §14). */
    public static final LocalTime EOD_TRIGGER_AT = LocalTime.of(15, 55, 0);

    /** 16:00:00 ET — RTH market close. */
    public static final LocalTime MARKET_CLOSE_AT = LocalTime.of(16, 0, 0);

    private final SessionService sessionService;
    private final Clock clock;

    /** Per-tenant ledger of which boundaries have fired on which session date. */
    private final Map<String, TenantBoundaryState> tenantBoundaries = new HashMap<>();

    @Inject
    public SessionDriver(SessionService sessionService, Clock clock) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Inspect the ET wall clock and fire any timeline boundaries crossed since the
     * previous {@code tick} for this tenant. Returns the current session state after
     * all fired events have been applied.
     */
    public SessionState tick(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        ZonedDateTime nowEt = clock.instant().atZone(SessionService.ET_ZONE);
        LocalDate today = nowEt.toLocalDate();
        LocalTime nowTime = nowEt.toLocalTime();

        TenantBoundaryState prev = tenantBoundaries.get(tenantId);
        if (prev == null || !prev.sessionDate.equals(today)) {
            // New session — reset boundaries.
            prev = new TenantBoundaryState(today, false, false, false, false);
            tenantBoundaries.put(tenantId, prev);
        }

        TenantBoundaryState s = prev;
        if (!s.levelsReady && !nowTime.isBefore(LEVELS_READY_AT)) {
            fire(tenantId, SessionEvent.LEVELS_READY);
            s = s.withLevelsReady();
        }
        if (!s.marketOpen && !nowTime.isBefore(MARKET_OPEN_AT)) {
            fire(tenantId, SessionEvent.MARKET_OPEN);
            s = s.withMarketOpen();
        }
        if (!s.eodTrigger && !nowTime.isBefore(EOD_TRIGGER_AT)) {
            fire(tenantId, SessionEvent.EOD_TRIGGER);
            s = s.withEodTrigger();
        }
        if (!s.marketClose && !nowTime.isBefore(MARKET_CLOSE_AT)) {
            fire(tenantId, SessionEvent.MARKET_CLOSE);
            s = s.withMarketClose();
        }
        tenantBoundaries.put(tenantId, s);
        return sessionService.currentState(tenantId);
    }

    private void fire(String tenantId, SessionEvent event) {
        Optional<SessionState> result = sessionService.apply(tenantId, event, Optional.empty());
        if (result.isEmpty()) {
            LOG.debug("driver-fire rejected tenantId={} event={}", tenantId, event);
        }
    }

    /** Per-tenant memo of which timeline boundaries have already fired. */
    private record TenantBoundaryState(
            LocalDate sessionDate, boolean levelsReady, boolean marketOpen, boolean eodTrigger, boolean marketClose) {
        TenantBoundaryState withLevelsReady() {
            return new TenantBoundaryState(sessionDate, true, marketOpen, eodTrigger, marketClose);
        }

        TenantBoundaryState withMarketOpen() {
            return new TenantBoundaryState(sessionDate, levelsReady, true, eodTrigger, marketClose);
        }

        TenantBoundaryState withEodTrigger() {
            return new TenantBoundaryState(sessionDate, levelsReady, marketOpen, true, marketClose);
        }

        TenantBoundaryState withMarketClose() {
            return new TenantBoundaryState(sessionDate, levelsReady, marketOpen, eodTrigger, true);
        }
    }
}
