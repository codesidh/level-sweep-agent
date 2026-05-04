package com.levelsweep.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link MarketDay} record. Pure POJO — record component
 * defensive copies, factory helpers, weekend predicate.
 */
class MarketDayTest {

    @Test
    void rejectsNullDate() {
        assertThatThrownBy(() -> new MarketDay(null, true, false, null, false, false, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("date");
    }

    @Test
    void rejectsNullEventNames() {
        assertThatThrownBy(() -> new MarketDay(LocalDate.of(2026, 1, 5), true, false, null, false, false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventNames");
    }

    @Test
    void eventNamesAreDefensivelyCopied() {
        java.util.ArrayList<String> mutable = new java.util.ArrayList<>();
        mutable.add("FOMC Meeting (Jan)");
        MarketDay day = new MarketDay(LocalDate.of(2026, 1, 28), true, false, null, false, true, mutable);
        // Mutate the source — the record's view must NOT change.
        mutable.add("INJECTED");
        assertThat(day.eventNames()).hasSize(1).containsExactly("FOMC Meeting (Jan)");
    }

    @Test
    void normalTradingDayHelper() {
        MarketDay day = MarketDay.normalTradingDay(LocalDate.of(2026, 5, 13));
        assertThat(day.isTradingDay()).isTrue();
        assertThat(day.isHoliday()).isFalse();
        assertThat(day.isHalfDay()).isFalse();
        assertThat(day.isFomcDay()).isFalse();
        assertThat(day.holidayName()).isNull();
        assertThat(day.eventNames()).isEmpty();
    }

    @Test
    void weekendHelper() {
        MarketDay day = MarketDay.weekend(LocalDate.of(2026, 5, 9));
        assertThat(day.isTradingDay()).isFalse();
        assertThat(day.isHoliday()).isFalse();
        assertThat(day.eventNames()).isEmpty();
    }

    @Test
    void isWeekendCorrectlyIdentifiesSatSun() {
        assertThat(MarketDay.isWeekend(LocalDate.of(2026, 5, 9))).isTrue(); // Saturday
        assertThat(MarketDay.isWeekend(LocalDate.of(2026, 5, 10))).isTrue(); // Sunday
        assertThat(MarketDay.isWeekend(LocalDate.of(2026, 5, 11))).isFalse(); // Monday
        assertThat(MarketDay.isWeekend(LocalDate.of(2026, 5, 13))).isFalse(); // Wednesday
        assertThat(MarketDay.isWeekend(LocalDate.of(2026, 5, 15))).isFalse(); // Friday
    }
}
