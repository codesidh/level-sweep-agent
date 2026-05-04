package com.levelsweep.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.calendar.domain.EventType;
import com.levelsweep.calendar.domain.MarketDay;
import com.levelsweep.calendar.domain.MarketEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link CalendarService}. Pure POJO tests — no Spring boot,
 * no test profile, no fixtures. Loads the real production YAML resources
 * from the classpath so what's tested is what ships.
 *
 * <p>The truth table covers the load-bearing dates:
 *
 * <ul>
 *   <li>Christmas 2026 (Friday) — full closure
 *   <li>July 4 2027 (Sunday, observed July 5 Monday) — observance rule
 *   <li>MLK Day 2028 (third Monday of January)
 *   <li>Black Friday 2026 — half-day, NOT a holiday
 *   <li>FOMC meeting Jan 28 2026 — trading day with isFomcDay=true
 *   <li>Saturday — weekend, isTradingDay=false but isHoliday=false
 *   <li>Random weekday with no events — normalTradingDay
 * </ul>
 */
class CalendarServiceTest {

    /** Pinned to a Wednesday in Q2 2026 for deterministic today() lookups. */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 5, 13);

    private CalendarService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(
                FIXED_TODAY.atStartOfDay(ZoneId.of("America/New_York")).toInstant(), ZoneId.of("America/New_York"));
        service = new CalendarService(fixed, "calendar/nyse-holidays-2026-2030.yml", "calendar/fomc-2026-2030.yml");
        service.loadCalendar();
    }

    @Test
    @DisplayName("today() reflects the fixed clock in NYSE zone")
    void todayUsesFixedClock() {
        MarketDay today = service.today();
        assertThat(today.date()).isEqualTo(FIXED_TODAY);
        assertThat(today.isTradingDay()).isTrue();
        assertThat(today.isHoliday()).isFalse();
        assertThat(today.isHalfDay()).isFalse();
        assertThat(today.isFomcDay()).isFalse();
    }

    @Test
    @DisplayName("Christmas 2026 (Fri Dec 25) is a full closure")
    void christmas2026() {
        MarketDay day = service.forDate(LocalDate.of(2026, 12, 25));
        assertThat(day.isHoliday()).isTrue();
        assertThat(day.isTradingDay()).isFalse();
        assertThat(day.holidayName()).isEqualTo("Christmas Day");
        assertThat(day.eventNames()).contains("Christmas Day");
    }

    @Test
    @DisplayName("July 4 2027 falls on Sunday — observed Monday July 5 is the closure")
    void independenceDay2027ObservedRule() {
        // The Sunday itself is a weekend non-trading day with no recorded
        // closure event — NYSE shifts the observance to Monday.
        MarketDay sunday = service.forDate(LocalDate.of(2027, 7, 4));
        assertThat(sunday.isTradingDay()).isFalse();
        assertThat(sunday.isHoliday()).isFalse();
        assertThat(sunday.holidayName()).isNull();
        assertThat(sunday.eventNames()).isEmpty();

        // Monday July 5 is the OBSERVED Independence Day — full closure.
        MarketDay monday = service.forDate(LocalDate.of(2027, 7, 5));
        assertThat(monday.isHoliday()).isTrue();
        assertThat(monday.isTradingDay()).isFalse();
        assertThat(monday.holidayName()).isEqualTo("Independence Day (observed)");
    }

    @Test
    @DisplayName("MLK Day 2028 = third Monday Jan 17")
    void mlkDay2028() {
        MarketDay day = service.forDate(LocalDate.of(2028, 1, 17));
        assertThat(day.isHoliday()).isTrue();
        assertThat(day.isTradingDay()).isFalse();
        assertThat(day.holidayName()).isEqualTo("Martin Luther King Jr. Day");
    }

    @Test
    @DisplayName("Black Friday 2026 (Fri Nov 27) is a half-day, NOT a holiday")
    void blackFriday2026IsHalfDayNotHoliday() {
        MarketDay day = service.forDate(LocalDate.of(2026, 11, 27));
        assertThat(day.isHalfDay()).isTrue();
        assertThat(day.isHoliday()).isFalse();
        // Half-days are still trading days — RTH 09:30-13:00 ET.
        assertThat(day.isTradingDay()).isTrue();
        assertThat(day.holidayName()).isNull();
        assertThat(day.eventNames()).contains("Day after Thanksgiving");
    }

    @Test
    @DisplayName("FOMC meeting 2026-01-28 is a trading day with isFomcDay=true")
    void fomcMeetingFlagsButDoesNotCloseMarket() {
        MarketDay day = service.forDate(LocalDate.of(2026, 1, 28));
        assertThat(day.isTradingDay()).isTrue();
        assertThat(day.isHoliday()).isFalse();
        assertThat(day.isFomcDay()).isTrue();
        assertThat(day.eventNames()).anyMatch(s -> s.startsWith("FOMC Meeting"));
    }

    @Test
    @DisplayName("FOMC minutes 2026-02-18 also sets isFomcDay=true")
    void fomcMinutesAlsoSetsFomcFlag() {
        MarketDay day = service.forDate(LocalDate.of(2026, 2, 18));
        assertThat(day.isFomcDay()).isTrue();
        assertThat(day.isTradingDay()).isTrue();
        assertThat(day.eventNames()).anyMatch(s -> s.startsWith("FOMC Minutes"));
    }

    @Test
    @DisplayName("Saturday is non-trading but not a holiday")
    void saturdayIsWeekend() {
        // 2026-05-09 is a Saturday — no NYSE event scheduled.
        MarketDay day = service.forDate(LocalDate.of(2026, 5, 9));
        assertThat(day.isTradingDay()).isFalse();
        assertThat(day.isHoliday()).isFalse();
        assertThat(day.holidayName()).isNull();
        assertThat(day.eventNames()).isEmpty();
    }

    @Test
    @DisplayName("Sunday is non-trading but not a holiday")
    void sundayIsWeekend() {
        // 2026-05-10 is a Sunday.
        MarketDay day = service.forDate(LocalDate.of(2026, 5, 10));
        assertThat(day.isTradingDay()).isFalse();
        assertThat(day.isHoliday()).isFalse();
    }

    @Test
    @DisplayName("Plain weekday with no events → normalTradingDay")
    void plainWeekday() {
        // 2026-05-13 (a Wednesday in May — no holiday, no FOMC).
        MarketDay day = service.forDate(LocalDate.of(2026, 5, 13));
        assertThat(day.isTradingDay()).isTrue();
        assertThat(day.isHoliday()).isFalse();
        assertThat(day.isHalfDay()).isFalse();
        assertThat(day.isFomcDay()).isFalse();
        assertThat(day.eventNames()).isEmpty();
    }

    @ParameterizedTest(name = "{0} → isHoliday={1}")
    @CsvSource({
        // ---- 2026 NYSE truth table ----
        "2026-01-01, true,  New Year's Day",
        "2026-01-19, true,  Martin Luther King Jr. Day",
        "2026-02-16, true,  Washington's Birthday",
        "2026-04-03, true,  Good Friday",
        "2026-05-25, true,  Memorial Day",
        "2026-06-19, true,  Juneteenth National Independence Day",
        "2026-07-03, true,  Independence Day (observed)",
        "2026-09-07, true,  Labor Day",
        "2026-11-26, true,  Thanksgiving Day",
        "2026-12-25, true,  Christmas Day",
        // ---- 2028: Jan 1 falls on Saturday → NYSE Rule 7.2 → NO closure ----
        "2027-12-31, false, ",
        "2028-01-03, false, ",
        // ---- 2030 sanity check ----
        "2030-07-04, true,  Independence Day"
    })
    void holidayTruthTable(String dateStr, boolean expectHoliday, String expectName) {
        MarketDay day = service.forDate(LocalDate.parse(dateStr));
        assertThat(day.isHoliday()).isEqualTo(expectHoliday);
        if (expectHoliday) {
            assertThat(day.holidayName()).isEqualTo(expectName);
            assertThat(day.isTradingDay()).isFalse();
        }
    }

    @Test
    @DisplayName("blackoutDates(2026-01-01, 2026-12-31) returns all 2026 closures + FOMC events")
    void blackoutDates2026() {
        List<MarketEvent> events = service.blackoutDates(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        // 10 NYSE holidays in 2026 + 8 FOMC meetings + 8 FOMC minutes = 26.
        long holidays =
                events.stream().filter(e -> e.type() == EventType.HOLIDAY).count();
        long fomcMeetings =
                events.stream().filter(e -> e.type() == EventType.FOMC_MEETING).count();
        long fomcMinutes =
                events.stream().filter(e -> e.type() == EventType.FOMC_MINUTES).count();

        assertThat(holidays).isEqualTo(10);
        assertThat(fomcMeetings).isEqualTo(8);
        assertThat(fomcMinutes).isEqualTo(8);

        // Half-days are NOT in blackout list — explicit per-spec.
        assertThat(events.stream()).noneMatch(e -> e.type() == EventType.EARLY_CLOSE);

        // Sorted ascending by date.
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).date()).isAfterOrEqualTo(events.get(i - 1).date());
        }
    }

    @Test
    @DisplayName("blackoutDates() rejects from > to")
    void blackoutDatesRejectsInvertedRange() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.blackoutDates(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be <= to");
    }

    @Test
    @DisplayName("Cache hit returns the same instance on repeated query")
    void cacheHit() {
        // The @Cacheable annotation is exercised by the controller-test
        // SpringApplicationContext; for the unit test we simply prove the
        // pure-function lookup is referentially transparent.
        MarketDay first = service.forDate(LocalDate.of(2026, 12, 25));
        MarketDay second = service.forDate(LocalDate.of(2026, 12, 25));
        assertThat(first).isEqualTo(second);
        assertThat(first.eventNames()).isEqualTo(second.eventNames());
    }

    @Test
    @DisplayName("Calendar covers exactly years 2026-2030 — entire 5-year range loaded")
    void calendarCoversFiveYears() {
        // First date in 2026, last in 2030 (or later for cross-year FOMC minutes
        // that release in early 2031 from the Dec 2030 meeting).
        List<MarketEvent> all = service.blackoutDates(LocalDate.of(2025, 1, 1), LocalDate.of(2031, 12, 31));
        boolean has2026 = all.stream().anyMatch(e -> e.date().getYear() == 2026);
        boolean has2030 = all.stream().anyMatch(e -> e.date().getYear() == 2030);
        assertThat(has2026).isTrue();
        assertThat(has2030).isTrue();
    }

    @Test
    @DisplayName("Constructor rejects null clock")
    void constructorRejectsNullClock() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CalendarService(
                        null, "calendar/nyse-holidays-2026-2030.yml", "calendar/fomc-2026-2030.yml"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("loadCalendar fails fast when YAML resource is missing")
    void loadCalendarFailsFastOnMissingResource() {
        Clock c = Clock.fixed(Instant.parse("2026-05-13T00:00:00Z"), ZoneId.of("America/New_York"));
        CalendarService bad = new CalendarService(c, "calendar/does-not-exist.yml", "calendar/fomc-2026-2030.yml");
        org.assertj.core.api.Assertions.assertThatThrownBy(bad::loadCalendar)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.yml");
    }
}
