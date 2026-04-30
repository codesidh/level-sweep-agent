package com.levelsweep.marketdata.levels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SessionWindowsTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void rthIsSixAndAHalfHoursOnRegularDay() {
        SessionWindows.Window rth = SessionWindows.rth(LocalDate.of(2026, 4, 30));
        assertThat(rth.duration()).isEqualTo(Duration.ofHours(6).plusMinutes(30));
    }

    @Test
    void rthOpenIs0930ETInUtc() {
        // 2026-04-30 09:30 ET = 13:30 UTC (EDT, UTC-4)
        SessionWindows.Window rth = SessionWindows.rth(LocalDate.of(2026, 4, 30));
        assertThat(rth.start()).isEqualTo(Instant.parse("2026-04-30T13:30:00Z"));
    }

    @Test
    void rthCloseIs1600ETInUtc() {
        SessionWindows.Window rth = SessionWindows.rth(LocalDate.of(2026, 4, 30));
        assertThat(rth.endExclusive()).isEqualTo(Instant.parse("2026-04-30T20:00:00Z"));
    }

    @Test
    void overnightStartsAt1601EtPriorDay() {
        SessionWindows.Window over = SessionWindows.overnight(LocalDate.of(2026, 4, 30));
        // 2026-04-29 16:01 ET = 20:01 UTC
        assertThat(over.start()).isEqualTo(Instant.parse("2026-04-29T20:01:00Z"));
    }

    @Test
    void overnightEndsAt0929EtSessionDay() {
        SessionWindows.Window over = SessionWindows.overnight(LocalDate.of(2026, 4, 30));
        // 2026-04-30 09:29 ET = 13:29 UTC
        assertThat(over.endExclusive()).isEqualTo(Instant.parse("2026-04-30T13:29:00Z"));
    }

    @Test
    void containsHonorsHalfOpenInterval() {
        SessionWindows.Window rth = SessionWindows.rth(LocalDate.of(2026, 4, 30));
        // Boundary: 13:30 included
        assertThat(rth.contains(Instant.parse("2026-04-30T13:30:00Z"))).isTrue();
        // 15:59:59 included
        assertThat(rth.contains(Instant.parse("2026-04-30T19:59:59Z"))).isTrue();
        // 16:00:00 EXCLUDED (window is half-open)
        assertThat(rth.contains(Instant.parse("2026-04-30T20:00:00Z"))).isFalse();
        // Way before: excluded
        assertThat(rth.contains(Instant.parse("2026-04-30T08:00:00Z"))).isFalse();
    }

    @Test
    void rthInWinterIsAtUtcMinus5() {
        // EST in January: 09:30 ET = 14:30 UTC
        SessionWindows.Window rth = SessionWindows.rth(LocalDate.of(2026, 1, 15));
        assertThat(rth.start()).isEqualTo(Instant.parse("2026-01-15T14:30:00Z"));
        assertThat(rth.endExclusive()).isEqualTo(Instant.parse("2026-01-15T21:00:00Z"));
    }

    @Test
    void springForwardSessionStillOpensAt0930ET() {
        // 2026 spring-forward: March 8 02:00 ET → 03:00 ET. RTH opens at 09:30 EDT (UTC-4 now).
        SessionWindows.Window rth = SessionWindows.rth(LocalDate.of(2026, 3, 9)); // Monday after spring-forward
        // 09:30 EDT = 13:30 UTC
        assertThat(rth.start()).isEqualTo(Instant.parse("2026-03-09T13:30:00Z"));
    }

    @Test
    void overnightSpansAcrossDaysCorrectly() {
        SessionWindows.Window over = SessionWindows.overnight(LocalDate.of(2026, 4, 30));
        // From 2026-04-29 16:01 ET (20:01 UTC) to 2026-04-30 09:29 ET (13:29 UTC)
        // = ~17h 28min
        Duration d = over.duration();
        assertThat(d.toMinutes()).isEqualTo(17L * 60 + 28);
    }

    @Test
    void timeMathUsesAmericaNewYorkConstant() {
        // Sanity: the project uses ZoneId.of("America/New_York") consistently
        assertThat(SessionWindows.ET).isEqualTo(NY);
    }

    @Test
    void rthOpenAlignsWithLocalTime() {
        LocalDateTime expectedLocal = LocalDateTime.of(2026, 4, 30, 9, 30);
        SessionWindows.Window rth = SessionWindows.rth(LocalDate.of(2026, 4, 30));
        assertThat(rth.start().atZone(NY).toLocalDateTime()).isEqualTo(expectedLocal);
    }
}
