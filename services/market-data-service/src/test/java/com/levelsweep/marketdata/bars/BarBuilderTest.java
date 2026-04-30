package com.levelsweep.marketdata.bars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BarBuilderTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static Tick t(String time, double price, long size) {
        return new Tick("SPY", BigDecimal.valueOf(price), size, Instant.parse(time));
    }

    @Test
    void firstTickStartsInFlightBar() {
        BarBuilder b = new BarBuilder("SPY", Timeframe.ONE_MIN, NY);
        Optional<Bar> emitted = b.apply(t("2026-04-30T13:30:00Z", 594.0, 100));
        assertThat(emitted).isEmpty();
        assertThat(b.hasInFlight()).isTrue();
    }

    @Test
    void multipleTicksWithinSameBoundaryAggregate() {
        BarBuilder b = new BarBuilder("SPY", Timeframe.ONE_MIN, NY);
        b.apply(t("2026-04-30T13:30:00Z", 594.0, 100));
        b.apply(t("2026-04-30T13:30:15Z", 594.5, 200));
        b.apply(t("2026-04-30T13:30:30Z", 593.8, 150));
        b.apply(t("2026-04-30T13:30:45Z", 594.2, 50));
        Optional<Bar> emitted = b.flush();
        assertThat(emitted).isPresent();
        Bar bar = emitted.get();
        assertThat(bar.open()).isEqualByComparingTo("594.0");
        assertThat(bar.high()).isEqualByComparingTo("594.5");
        assertThat(bar.low()).isEqualByComparingTo("593.8");
        assertThat(bar.close()).isEqualByComparingTo("594.2");
        assertThat(bar.volume()).isEqualTo(500L);
        assertThat(bar.ticks()).isEqualTo(4L);
    }

    @Test
    void crossingBoundaryClosesPreviousAndStartsNew() {
        BarBuilder b = new BarBuilder("SPY", Timeframe.ONE_MIN, NY);
        b.apply(t("2026-04-30T13:30:30Z", 594.0, 100));
        Optional<Bar> emitted = b.apply(t("2026-04-30T13:31:00Z", 594.5, 200));
        assertThat(emitted).isPresent();
        Bar prev = emitted.get();
        assertThat(prev.openTime()).isEqualTo(Instant.parse("2026-04-30T13:30:00Z"));
        assertThat(prev.closeTime()).isEqualTo(Instant.parse("2026-04-30T13:31:00Z"));
        assertThat(prev.close()).isEqualByComparingTo("594.0");
        // New bar in flight from second tick
        assertThat(b.hasInFlight()).isTrue();
    }

    @Test
    void twoMinTimeframeAlignsToEvenMinutes() {
        BarBuilder b = new BarBuilder("SPY", Timeframe.TWO_MIN, NY);
        // 13:30:30 UTC = 09:30:30 ET → bar [09:30, 09:32) ET = [13:30, 13:32) UTC
        b.apply(t("2026-04-30T13:30:30Z", 594.0, 100));
        // Same bar (still 09:31)
        b.apply(t("2026-04-30T13:31:00Z", 594.2, 50));
        // New bar (09:32 ET = 13:32 UTC)
        Optional<Bar> emitted = b.apply(t("2026-04-30T13:32:00Z", 594.5, 200));
        assertThat(emitted).isPresent();
        Bar bar = emitted.get();
        assertThat(bar.openTime()).isEqualTo(Instant.parse("2026-04-30T13:30:00Z"));
        assertThat(bar.closeTime()).isEqualTo(Instant.parse("2026-04-30T13:32:00Z"));
        assertThat(bar.timeframe()).isEqualTo(Timeframe.TWO_MIN);
    }

    @Test
    void fifteenMinTimeframeAlignsToQuarterHours() {
        BarBuilder b = new BarBuilder("SPY", Timeframe.FIFTEEN_MIN, NY);
        // 09:30 ET = 13:30 UTC
        b.apply(t("2026-04-30T13:30:00Z", 594.0, 100));
        // 09:44:59 ET = 13:44:59 UTC — same bar
        b.apply(t("2026-04-30T13:44:59Z", 594.5, 100));
        // 09:45 ET = 13:45 UTC — new bar
        Optional<Bar> emitted = b.apply(t("2026-04-30T13:45:00Z", 594.6, 100));
        assertThat(emitted).isPresent();
        Bar bar = emitted.get();
        assertThat(bar.openTime()).isEqualTo(Instant.parse("2026-04-30T13:30:00Z"));
        assertThat(bar.closeTime()).isEqualTo(Instant.parse("2026-04-30T13:45:00Z"));
    }

    @Test
    void flushIfStaleEmitsWhenWindowElapsed() {
        BarBuilder b = new BarBuilder("SPY", Timeframe.ONE_MIN, NY);
        b.apply(t("2026-04-30T13:30:30Z", 594.0, 100));
        // Before close time — not stale
        assertThat(b.flushIfStale(Instant.parse("2026-04-30T13:30:59Z"))).isEmpty();
        // At/after close time — stale
        Optional<Bar> emitted = b.flushIfStale(Instant.parse("2026-04-30T13:31:00Z"));
        assertThat(emitted).isPresent();
        assertThat(b.hasInFlight()).isFalse();
    }

    @Test
    void flushOnEmptyBuilderReturnsEmpty() {
        BarBuilder b = new BarBuilder("SPY", Timeframe.ONE_MIN, NY);
        assertThat(b.flush()).isEmpty();
        assertThat(b.flushIfStale(Instant.now())).isEmpty();
    }

    @Test
    void rejectsTickWithWrongSymbol() {
        BarBuilder b = new BarBuilder("SPY", Timeframe.ONE_MIN, NY);
        Tick foreign = new Tick("QQQ", BigDecimal.valueOf(500.0), 100L, Instant.parse("2026-04-30T13:30:00Z"));
        assertThatThrownBy(() -> b.apply(foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol mismatch");
    }

    @Test
    void outOfOrderTickWithEarlierBoundaryIsDropped() {
        // We don't reorder; tests document the policy: the late tick is silently dropped.
        BarBuilder b = new BarBuilder("SPY", Timeframe.ONE_MIN, NY);
        b.apply(t("2026-04-30T13:31:30Z", 594.0, 100)); // bar 13:31
        // Tick at 13:30:30 belongs to an earlier bar — drop
        Optional<Bar> emitted = b.apply(t("2026-04-30T13:30:30Z", 593.0, 50));
        assertThat(emitted).isEmpty();
        // In-flight bar's close should still be 594.0 (the late tick was ignored)
        Bar finalBar = b.flush().orElseThrow();
        assertThat(finalBar.close()).isEqualByComparingTo("594.0");
    }
}
