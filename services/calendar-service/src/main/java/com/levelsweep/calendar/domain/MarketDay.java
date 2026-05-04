package com.levelsweep.calendar.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Immutable per-date answer to "what does this day look like for trading?".
 *
 * <p>The shape mirrors the JSON response of {@code GET /calendar/today} and
 * {@code GET /calendar/{date}}; Jackson serializes the record components
 * directly (no custom {@code @JsonProperty} annotations needed because
 * Spring Boot's default ObjectMapper picks up record component names).
 *
 * <p>Truth table:
 *
 * <ul>
 *   <li>Mon-Fri, no holiday, no early close → {@code isTradingDay=true,
 *       isHoliday=false, isHalfDay=false, isFomcDay=false} (FOMC days are
 *       still trading days — the indicator is flag-only).
 *   <li>Sat / Sun → {@code isTradingDay=false, isHoliday=false} (weekends
 *       are NOT classified as holidays; only NYSE-published closures are).
 *   <li>July 4 on a weekday → {@code isTradingDay=false, isHoliday=true,
 *       holidayName="Independence Day"}.
 *   <li>July 4 on a Sunday → {@code isTradingDay=false, isHoliday=true} for
 *       July 5 (observed) and {@code isTradingDay=false, isHoliday=false}
 *       for July 4 itself (weekend).
 *   <li>Black Friday → {@code isTradingDay=true, isHalfDay=true,
 *       holidayName=null} (NYSE half-days are NOT holidays — they trade
 *       09:30-13:00 ET, just on a shortened schedule).
 *   <li>FOMC meeting day → {@code isTradingDay=true, isFomcDay=true,
 *       eventNames=["FOMC Meeting (Jan)"]}.
 * </ul>
 *
 * <p>{@link #eventNames} is the union of holiday name (if any) + every
 * {@link MarketEvent} on this date — the Session FSM uses it for the
 * BLACKOUT-reason audit string. The list is unmodifiable.
 *
 * @param date          calendar date in America/New_York
 * @param isTradingDay  false on weekends and NYSE-closed holidays; true on
 *                      half-days and FOMC days
 * @param isHoliday     true only on NYSE-published full closures (not weekends)
 * @param holidayName   the NYSE holiday name when {@link #isHoliday}; {@code null} otherwise
 * @param isHalfDay     true on NYSE-published early closes (Black Friday, etc.)
 * @param isFomcDay     true on FOMC meeting days OR FOMC minutes-release days
 * @param eventNames    every event name on this date (for BLACKOUT-reason logs)
 */
public record MarketDay(
        LocalDate date,
        boolean isTradingDay,
        boolean isHoliday,
        String holidayName,
        boolean isHalfDay,
        boolean isFomcDay,
        List<String> eventNames) {

    public MarketDay {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(eventNames, "eventNames");
        eventNames = List.copyOf(eventNames);
    }

    /**
     * Convenience constructor for the "no events, normal weekday" case — used
     * by the service layer when the requested date isn't in the YAML calendar.
     */
    public static MarketDay normalTradingDay(LocalDate date) {
        return new MarketDay(date, true, false, null, false, false, List.of());
    }

    /**
     * Convenience constructor for the "weekend, no events" case — used by the
     * service layer when the requested date is a Saturday or Sunday and isn't
     * also in the YAML calendar (which would be unusual but possible if NYSE
     * ever published a weekend session).
     */
    public static MarketDay weekend(LocalDate date) {
        return new MarketDay(date, false, false, null, false, false, List.of());
    }

    /**
     * Pure helper — Saturday / Sunday → true. Mirrors the service layer's
     * weekend short-circuit so callers can sanity-check a {@link LocalDate}
     * without booting Spring.
     */
    public static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
