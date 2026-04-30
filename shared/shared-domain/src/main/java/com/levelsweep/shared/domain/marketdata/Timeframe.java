package com.levelsweep.shared.domain.marketdata;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Bar resolution. Each timeframe is anchored to America/New_York wall clock
 * (per Architecture Principle #10) so DST transitions are handled correctly.
 *
 * <p>Boundary alignment:
 *
 * <ul>
 *   <li>{@link #ONE_MIN} — every whole minute (00:00, 00:01, 00:02, ...)
 *   <li>{@link #TWO_MIN} — every even minute (00:00, 00:02, 00:04, ...)
 *   <li>{@link #FIFTEEN_MIN} — :00, :15, :30, :45 of every hour
 *   <li>{@link #DAILY} — start of trading day in ET (00:00 of the local date)
 * </ul>
 */
public enum Timeframe {
    ONE_MIN(Duration.ofMinutes(1)),
    TWO_MIN(Duration.ofMinutes(2)),
    FIFTEEN_MIN(Duration.ofMinutes(15)),
    DAILY(Duration.ofDays(1));

    private final Duration duration;

    Timeframe(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    /** Whether this timeframe is intraday (sub-day). */
    public boolean isIntraday() {
        return this != DAILY;
    }

    /**
     * Floor an instant to the start of the bar that contains it, in the
     * caller-supplied timezone (always America/New_York for this project).
     *
     * <p>For intraday timeframes: floor to the appropriate minute boundary
     * within the local day. For DAILY: the start of the local date at 00:00.
     */
    public ZonedDateTime floor(ZonedDateTime ts) {
        Objects.requireNonNull(ts, "ts");
        if (this == DAILY) {
            return ts.toLocalDate().atStartOfDay(ts.getZone());
        }
        int minutesPerBar = (int) duration.toMinutes();
        LocalTime t = ts.toLocalTime();
        int totalMinutes = t.getHour() * 60 + t.getMinute();
        int floored = totalMinutes - (totalMinutes % minutesPerBar);
        LocalDate date = ts.toLocalDate();
        LocalDateTime ldt = date.atTime(floored / 60, floored % 60);
        return ldt.atZone(ts.getZone());
    }

    /**
     * Floor convenience that takes a {@link java.time.Instant} and zone.
     */
    public ZonedDateTime floor(java.time.Instant instant, ZoneId zone) {
        return floor(instant.atZone(zone));
    }
}
