package com.levelsweep.marketdata.levels;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure-function session boundary calculator. Given a session date in
 * America/New_York, returns the RTH and overnight (pre-market) windows
 * relative to that date.
 *
 * <p>Definitions match {@code requirements.md} §4:
 *
 * <ul>
 *   <li>RTH window: 09:30:00 ET → 16:00:00 ET (inclusive of bars opening at 09:30
 *       and 15:59; the bar opening at 16:00 is the after-hours boundary and is
 *       NOT part of RTH)
 *   <li>Overnight window for "today": 16:01:00 ET prior day → 09:29:00 ET today
 *       (inclusive of both endpoints)
 * </ul>
 *
 * <p>DST-aware via {@link ZonedDateTime}; both windows are computed in
 * America/New_York wall-clock and converted to UTC instants.
 */
public final class SessionWindows {

    public static final ZoneId ET = ZoneId.of("America/New_York");
    public static final LocalTime RTH_OPEN = LocalTime.of(9, 30);
    public static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);
    public static final LocalTime OVERNIGHT_START = LocalTime.of(16, 1); // prior day
    public static final LocalTime OVERNIGHT_END = LocalTime.of(9, 29); // session day

    private SessionWindows() {}

    /** RTH window for the given session date in ET. */
    public static Window rth(LocalDate sessionDate) {
        ZonedDateTime open = LocalDateTime.of(sessionDate, RTH_OPEN).atZone(ET);
        ZonedDateTime close = LocalDateTime.of(sessionDate, RTH_CLOSE).atZone(ET);
        return new Window(open.toInstant(), close.toInstant());
    }

    /**
     * Overnight (pre-market) window for the given session date in ET. Starts at
     * 16:01 ET on the prior calendar day and ends at 09:29 ET on {@code sessionDate}.
     *
     * <p>This is wall-clock (not "trading day") math — if {@code sessionDate} is
     * a Monday, the overnight starts on Sunday at 16:01 ET. The level calculator
     * relies on the data feed populating Friday-evening, Saturday, and Sunday
     * activity (typical for futures-influenced products); for SPY specifically
     * there is no Saturday/Sunday activity but the window still spans the
     * calendar gap.
     */
    public static Window overnight(LocalDate sessionDate) {
        LocalDate priorDay = sessionDate.minusDays(1);
        ZonedDateTime start = LocalDateTime.of(priorDay, OVERNIGHT_START).atZone(ET);
        ZonedDateTime end = LocalDateTime.of(sessionDate, OVERNIGHT_END).atZone(ET);
        return new Window(start.toInstant(), end.toInstant());
    }

    /** Closed-open interval [start, endExclusive). */
    public record Window(Instant start, Instant endExclusive) {
        public Duration duration() {
            return Duration.between(start, endExclusive);
        }

        public boolean contains(Instant ts) {
            return !ts.isBefore(start) && ts.isBefore(endExclusive);
        }
    }
}
