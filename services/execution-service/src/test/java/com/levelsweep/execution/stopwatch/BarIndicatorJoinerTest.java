package com.levelsweep.execution.stopwatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO tests for {@link BarIndicatorJoiner}. Drives the join logic
 * with a fixed Clock so the 5-second tolerance window is exercised
 * deterministically.
 */
class BarIndicatorJoinerTest {

    private static final String SPY = "SPY";
    private static final Instant T0 = Instant.parse("2026-04-30T13:30:00Z");
    private static final Instant T2 = Instant.parse("2026-04-30T13:32:00Z");
    private static final Instant T4 = Instant.parse("2026-04-30T13:34:00Z");

    private final List<Joined> emitted = new ArrayList<>();

    private record Joined(Bar bar, IndicatorSnapshot indicator) {}

    private static Bar bar(Instant closeTime, BigDecimal close) {
        return new Bar(
                SPY, Timeframe.TWO_MIN, closeTime.minusSeconds(120), closeTime, close, close, close, close, 0L, 0L);
    }

    private static IndicatorSnapshot ind(Instant ts) {
        return new IndicatorSnapshot(SPY, ts, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }

    @Test
    void emitsWhenBarFirstThenMatchingIndicator() {
        Clock clock = Clock.fixed(T2.plusSeconds(1), ZoneId.of("UTC"));
        BarIndicatorJoiner joiner = new BarIndicatorJoiner(clock, (b, i) -> emitted.add(new Joined(b, i)));

        joiner.onBar(bar(T2, BigDecimal.TEN));
        joiner.onIndicator(ind(T2));

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).bar.closeTime()).isEqualTo(T2);
        assertThat(emitted.get(0).indicator.timestamp()).isEqualTo(T2);
    }

    @Test
    void emitsWhenIndicatorFirstThenMatchingBar() {
        Clock clock = Clock.fixed(T2.plusSeconds(1), ZoneId.of("UTC"));
        BarIndicatorJoiner joiner = new BarIndicatorJoiner(clock, (b, i) -> emitted.add(new Joined(b, i)));

        joiner.onIndicator(ind(T2));
        joiner.onBar(bar(T2, BigDecimal.TEN));

        assertThat(emitted).hasSize(1);
    }

    @Test
    void dropsMismatchedBarTimestampWhenIndicatorPending() {
        Clock clock = Clock.fixed(T2.plusSeconds(1), ZoneId.of("UTC"));
        BarIndicatorJoiner joiner = new BarIndicatorJoiner(clock, (b, i) -> emitted.add(new Joined(b, i)));

        // Indicator at T2 first.
        joiner.onIndicator(ind(T2));
        // Bar at T4 — different timestamp; pending T2 indicator is dropped.
        joiner.onBar(bar(T4, BigDecimal.TEN));

        // No emission.
        assertThat(emitted).isEmpty();
        // Now arrive matching indicator for T4 — should emit.
        joiner.onIndicator(ind(T4));
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).bar.closeTime()).isEqualTo(T4);
        assertThat(emitted.get(0).indicator.timestamp()).isEqualTo(T4);
    }

    @Test
    void dropsMismatchedIndicatorTimestampWhenBarPending() {
        Clock clock = Clock.fixed(T2.plusSeconds(1), ZoneId.of("UTC"));
        BarIndicatorJoiner joiner = new BarIndicatorJoiner(clock, (b, i) -> emitted.add(new Joined(b, i)));

        joiner.onBar(bar(T2, BigDecimal.TEN));
        joiner.onIndicator(ind(T4));

        assertThat(emitted).isEmpty();
    }

    @Test
    void dropsStalePendingBarAfterTimeoutWindow() {
        // Test a controllable Clock — start at T2, advance to T2+6s.
        java.util.concurrent.atomic.AtomicReference<Instant> nowRef =
                new java.util.concurrent.atomic.AtomicReference<>(T2.plusSeconds(1));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId z) {
                return this;
            }

            @Override
            public Instant instant() {
                return nowRef.get();
            }
        };
        BarIndicatorJoiner joiner =
                new BarIndicatorJoiner(clock, Duration.ofSeconds(5), (b, i) -> emitted.add(new Joined(b, i)));

        joiner.onBar(bar(T2, BigDecimal.TEN));
        // Advance 6s past the 5s tolerance.
        nowRef.set(T2.plusSeconds(7));
        // Matching indicator arrives — but the pending bar is stale and was dropped.
        joiner.onIndicator(ind(T2));

        assertThat(emitted).isEmpty();
    }

    @Test
    void dropsStalePendingIndicatorAfterTimeoutWindow() {
        java.util.concurrent.atomic.AtomicReference<Instant> nowRef =
                new java.util.concurrent.atomic.AtomicReference<>(T2.plusSeconds(1));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId z) {
                return this;
            }

            @Override
            public Instant instant() {
                return nowRef.get();
            }
        };
        BarIndicatorJoiner joiner =
                new BarIndicatorJoiner(clock, Duration.ofSeconds(5), (b, i) -> emitted.add(new Joined(b, i)));

        joiner.onIndicator(ind(T2));
        nowRef.set(T2.plusSeconds(7));
        joiner.onBar(bar(T2, BigDecimal.TEN));

        assertThat(emitted).isEmpty();
    }
}
