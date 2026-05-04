package com.levelsweep.calendar.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.levelsweep.calendar.domain.EventType;
import com.levelsweep.calendar.domain.MarketDay;
import com.levelsweep.calendar.domain.MarketEvent;
import com.levelsweep.calendar.service.CalendarService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link CalendarController}. Pure {@code @WebMvcTest} — no
 * service-layer wiring, no @PostConstruct YAML load, no Spring Cache. The
 * controller's only collaborator is {@link CalendarService}, which we mock.
 */
@WebMvcTest(CalendarController.class)
class CalendarControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CalendarService service;

    @Test
    void todayReturnsMarketDay() throws Exception {
        MarketDay day = new MarketDay(LocalDate.of(2026, 5, 13), true, false, null, false, false, List.of());
        when(service.today()).thenReturn(day);

        // Jackson serializes record components verbatim — the boolean components
        // declared as `isTradingDay` etc. land in JSON as `isTradingDay` (NOT
        // `tradingDay`). The is-prefix-stripping is JavaBean convention only;
        // records bypass the BeanIntrospector. The Session FSM consumer
        // contract is built around these exact keys.
        mvc.perform(get("/calendar/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-05-13"))
                .andExpect(jsonPath("$.isTradingDay").value(true))
                .andExpect(jsonPath("$.isHoliday").value(false))
                .andExpect(jsonPath("$.isHalfDay").value(false))
                .andExpect(jsonPath("$.isFomcDay").value(false));
    }

    @Test
    void todayRejectsUnsupportedTimezone() throws Exception {
        mvc.perform(get("/calendar/today").param("tz", "Europe/London"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("America/New_York")));
    }

    @Test
    void forDateReturnsHolidayShape() throws Exception {
        MarketDay day = new MarketDay(
                LocalDate.of(2026, 12, 25), false, true, "Christmas Day", false, false, List.of("Christmas Day"));
        when(service.forDate(LocalDate.of(2026, 12, 25))).thenReturn(day);

        mvc.perform(get("/calendar/2026-12-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-12-25"))
                .andExpect(jsonPath("$.isHoliday").value(true))
                .andExpect(jsonPath("$.isTradingDay").value(false))
                .andExpect(jsonPath("$.holidayName").value("Christmas Day"))
                .andExpect(jsonPath("$.eventNames[0]").value("Christmas Day"));
    }

    @Test
    void forDateRejectsBadFormat() throws Exception {
        mvc.perform(get("/calendar/not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ISO-8601")));
    }

    @Test
    void blackoutDatesReturnsEventList() throws Exception {
        MarketEvent christmas = new MarketEvent(LocalDate.of(2026, 12, 25), "Christmas Day", EventType.HOLIDAY);
        MarketEvent fomcJan = new MarketEvent(LocalDate.of(2026, 1, 28), "FOMC Meeting (Jan)", EventType.FOMC_MEETING);
        when(service.blackoutDates(any(), any())).thenReturn(List.of(fomcJan, christmas));

        mvc.perform(get("/calendar/blackout-dates").param("from", "2026-01-01").param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-01-01"))
                .andExpect(jsonPath("$.to").value("2026-12-31"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.events[0].name").value("FOMC Meeting (Jan)"))
                .andExpect(jsonPath("$.events[1].name").value("Christmas Day"));
    }

    @Test
    void blackoutDatesRejectsInvertedRange() throws Exception {
        mvc.perform(get("/calendar/blackout-dates").param("from", "2026-12-31").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("from must be <= to")));
    }

    @Test
    void blackoutDatesRejectsBadDateFormat() throws Exception {
        mvc.perform(get("/calendar/blackout-dates").param("from", "bogus").param("to", "2026-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ISO-8601")));
    }
}
