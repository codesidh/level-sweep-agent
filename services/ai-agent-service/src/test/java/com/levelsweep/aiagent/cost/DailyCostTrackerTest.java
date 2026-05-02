package com.levelsweep.aiagent.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link DailyCostTracker}. Verify:
 *
 * <ul>
 *   <li>{@code recordCost} accumulates by (tenantId, role, date)</li>
 *   <li>{@code wouldExceedCap} returns {@code true} when projected sum > cap</li>
 *   <li>Date rollover at midnight America/New_York</li>
 *   <li>Concurrent {@code recordCost} from multiple threads is safe</li>
 *   <li>{@code seedFromMongo} runs once per (tenant, role, date) on first touch</li>
 * </ul>
 */
class DailyCostTrackerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private DailyCostMongoRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(DailyCostMongoRepository.class);
        when(repository.sumByDay(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void recordCostAccumulatesByKey() {
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));

        BigDecimal afterFirst =
                tracker.recordCost("OWNER", Role.SENTINEL, LocalDate.of(2026, 5, 2), new BigDecimal("0.10"));
        BigDecimal afterSecond =
                tracker.recordCost("OWNER", Role.SENTINEL, LocalDate.of(2026, 5, 2), new BigDecimal("0.05"));

        assertThat(afterFirst).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(afterSecond).isEqualByComparingTo(new BigDecimal("0.15"));
        assertThat(tracker.currentSpend("OWNER", Role.SENTINEL, LocalDate.of(2026, 5, 2)))
                .isEqualByComparingTo(new BigDecimal("0.15"));
    }

    @Test
    void recordCostScopedByTenantAndRoleAndDate() {
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));
        LocalDate today = LocalDate.of(2026, 5, 2);
        LocalDate yesterday = today.minusDays(1);

        tracker.recordCost("OWNER", Role.SENTINEL, today, new BigDecimal("0.10"));
        tracker.recordCost("OWNER", Role.NARRATOR, today, new BigDecimal("0.20"));
        tracker.recordCost("OWNER-2", Role.SENTINEL, today, new BigDecimal("0.30"));
        tracker.recordCost("OWNER", Role.SENTINEL, yesterday, new BigDecimal("0.40"));

        assertThat(tracker.currentSpend("OWNER", Role.SENTINEL, today)).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(tracker.currentSpend("OWNER", Role.NARRATOR, today)).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(tracker.currentSpend("OWNER-2", Role.SENTINEL, today)).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(tracker.currentSpend("OWNER", Role.SENTINEL, yesterday))
                .isEqualByComparingTo(new BigDecimal("0.40"));
    }

    @Test
    void wouldExceedCapTrueWhenProjectedSumOverCap() {
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));
        LocalDate today = LocalDate.of(2026, 5, 2);
        // Sentinel cap is $1.00 by default in newTracker.
        tracker.recordCost("OWNER", Role.SENTINEL, today, new BigDecimal("0.95"));

        assertThat(tracker.wouldExceedCap("OWNER", Role.SENTINEL, today, new BigDecimal("0.10")))
                .isTrue();
        // Just below cap is OK.
        assertThat(tracker.wouldExceedCap("OWNER", Role.SENTINEL, today, new BigDecimal("0.04")))
                .isFalse();
    }

    @Test
    void wouldExceedCapFalseOnFreshBucket() {
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));

        assertThat(tracker.wouldExceedCap("OWNER", Role.NARRATOR, LocalDate.of(2026, 5, 2), new BigDecimal("0.99")))
                .isFalse();
    }

    @Test
    void wouldExceedCapTrueAtExactCap() {
        // Strict ">" semantics — projected == cap is allowed; only > cap is denied.
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));
        LocalDate today = LocalDate.of(2026, 5, 2);

        // Projected = $1.00, cap = $1.00 → false (not over)
        assertThat(tracker.wouldExceedCap("OWNER", Role.SENTINEL, today, new BigDecimal("1.00")))
                .isFalse();
        // Projected = $1.01, cap = $1.00 → true
        assertThat(tracker.wouldExceedCap("OWNER", Role.SENTINEL, today, new BigDecimal("1.01")))
                .isTrue();
    }

    @Test
    void todayUsesAmericaNewYorkZone() {
        // 2026-05-02 04:00 UTC = 2026-05-02 00:00 EDT — first minute of the new ET day.
        Clock midnightEt = Clock.fixed(Instant.parse("2026-05-02T04:00:00Z"), ZoneId.of("UTC"));
        DailyCostTracker tracker = newTracker(midnightEt);

        assertThat(tracker.today()).isEqualTo(LocalDate.of(2026, 5, 2));

        // 2026-05-02 03:59:59 UTC = 2026-05-01 23:59:59 EDT — still the prior ET day.
        Clock oneSecondBefore = Clock.fixed(Instant.parse("2026-05-02T03:59:59Z"), ZoneId.of("UTC"));
        DailyCostTracker tracker2 = newTracker(oneSecondBefore);

        assertThat(tracker2.today()).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void recordCostWritesThroughToRepository() {
        Clock c = Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC"));
        DailyCostTracker tracker = newTracker(c);
        LocalDate today = LocalDate.of(2026, 5, 2);

        tracker.recordCost("OWNER", Role.SENTINEL, today, new BigDecimal("0.10"));

        verify(repository, times(1))
                .append(eq("OWNER"), eq(Role.SENTINEL), eq(today), eq(new BigDecimal("0.10")), any(Instant.class));
    }

    @Test
    void seedFromMongoLoadsExistingSpendOnFirstTouch() {
        when(repository.sumByDay("OWNER", Role.SENTINEL, LocalDate.of(2026, 5, 2)))
                .thenReturn(new BigDecimal("0.50"));
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));

        // First touch: should pull from Mongo + reflect in spend.
        BigDecimal current = tracker.currentSpend("OWNER", Role.SENTINEL, LocalDate.of(2026, 5, 2));
        assertThat(current).isEqualByComparingTo(new BigDecimal("0.50"));

        // Subsequent touch: should not call Mongo again (the second invocation
        // is gated by the `seeded` flag).
        tracker.currentSpend("OWNER", Role.SENTINEL, LocalDate.of(2026, 5, 2));
        verify(repository, times(1)).sumByDay("OWNER", Role.SENTINEL, LocalDate.of(2026, 5, 2));
    }

    @Test
    void recordCostIsThreadSafe() throws Exception {
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));
        LocalDate today = LocalDate.of(2026, 5, 2);

        int threads = 16;
        int callsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        latch.await();
                        for (int j = 0; j < callsPerThread; j++) {
                            tracker.recordCost("OWNER", Role.NARRATOR, today, new BigDecimal("0.0001"));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }
            latch.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors.get()).isZero();

        // 16 × 50 × 0.0001 = 0.0800
        assertThat(tracker.currentSpend("OWNER", Role.NARRATOR, today)).isEqualByComparingTo(new BigDecimal("0.0800"));
    }

    @Test
    void recordCostRejectsNegative() {
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));

        assertThatThrownBy(() ->
                        tracker.recordCost("OWNER", Role.SENTINEL, LocalDate.of(2026, 5, 2), new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capForReturnsConfiguredCap() {
        DailyCostTracker tracker = newTracker(Clock.fixed(Instant.parse("2026-05-02T15:00:00Z"), ZoneId.of("UTC")));

        assertThat(tracker.capFor(Role.SENTINEL)).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(tracker.capFor(Role.NARRATOR)).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(tracker.capFor(Role.ASSISTANT)).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(tracker.capFor(Role.REVIEWER)).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    void zoneConstantIsAmericaNewYork() {
        // Doc / regression check on architecture-spec Principle #10.
        assertThat(DailyCostTracker.AMERICA_NEW_YORK).isEqualTo(ET);
    }

    private DailyCostTracker newTracker(Clock clock) {
        return new DailyCostTracker(
                repository,
                clock,
                new BigDecimal("1.00"),
                new BigDecimal("1.00"),
                new BigDecimal("5.00"),
                new BigDecimal("2.00"));
    }
}
