package com.levelsweep.decision.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit test for {@link BarConsumer}: constructs the consumer with a
 * recording {@link BarRouter} stub and invokes each {@code @Incoming}-annotated
 * method directly. Avoids {@code @QuarkusTest} so the suite stays cheap on CI.
 *
 * <p>The {@code @Incoming} annotation is irrelevant here — Quarkus's reactive-
 * messaging build-time validator covers the wiring; this test only verifies
 * delegation semantics.
 */
class BarConsumerTest {

    private static final Instant OPEN = Instant.parse("2026-04-30T13:30:00Z");

    private RecordingRouter router;
    private BarConsumer consumer;

    @BeforeEach
    void setUp() {
        router = new RecordingRouter();
        consumer = new BarConsumer(router);
    }

    @Test
    void consumeOneMinBarForwardsToRouter() {
        Bar bar = barOf(Timeframe.ONE_MIN);
        consumer.consumeOneMinBar(bar);
        assertThat(router.received).containsExactly(bar);
    }

    @Test
    void consumeTwoMinBarForwardsToRouter() {
        Bar bar = barOf(Timeframe.TWO_MIN);
        consumer.consumeTwoMinBar(bar);
        assertThat(router.received).containsExactly(bar);
    }

    @Test
    void consumeFifteenMinBarForwardsToRouter() {
        Bar bar = barOf(Timeframe.FIFTEEN_MIN);
        consumer.consumeFifteenMinBar(bar);
        assertThat(router.received).containsExactly(bar);
    }

    @Test
    void consumeDailyBarForwardsToRouter() {
        Bar bar = barOf(Timeframe.DAILY);
        consumer.consumeDailyBar(bar);
        assertThat(router.received).containsExactly(bar);
    }

    @Test
    void allFourChannelsShareSingleRouterInstance() {
        // Sanity-check that fan-in from four topics into one BarRouter preserves
        // ordering (the per-method calls flow into the same recording list).
        consumer.consumeOneMinBar(barOf(Timeframe.ONE_MIN));
        consumer.consumeTwoMinBar(barOf(Timeframe.TWO_MIN));
        consumer.consumeFifteenMinBar(barOf(Timeframe.FIFTEEN_MIN));
        consumer.consumeDailyBar(barOf(Timeframe.DAILY));

        assertThat(router.received)
                .extracting(Bar::timeframe)
                .containsExactly(Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.FIFTEEN_MIN, Timeframe.DAILY);
    }

    @Test
    void noOpBarRouterCountsEveryBar() {
        // Cross-check that the production default router actually counts.
        NoOpBarRouter realRouter = new NoOpBarRouter();
        BarConsumer realConsumer = new BarConsumer(realRouter);

        realConsumer.consumeOneMinBar(barOf(Timeframe.ONE_MIN));
        realConsumer.consumeFifteenMinBar(barOf(Timeframe.FIFTEEN_MIN));
        realConsumer.consumeDailyBar(barOf(Timeframe.DAILY));

        assertThat(realRouter.count()).isEqualTo(3L);
    }

    private static Bar barOf(Timeframe tf) {
        return new Bar(
                "SPY",
                tf,
                OPEN,
                OPEN.plus(tf.duration()),
                BigDecimal.valueOf(594.00),
                BigDecimal.valueOf(594.50),
                BigDecimal.valueOf(593.75),
                BigDecimal.valueOf(594.25),
                1_000L,
                10L);
    }

    /** Recording stub — captures every bar handed to it for assertions. */
    private static final class RecordingRouter implements BarRouter {
        final List<Bar> received = new ArrayList<>();

        @Override
        public void onBar(Bar bar) {
            received.add(bar);
        }
    }
}
