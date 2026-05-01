package com.levelsweep.execution.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeProposed;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit test for {@link TradeProposedConsumer}: constructs the
 * consumer with a recording {@link TradeRouter} stub and invokes the
 * {@code @Incoming}-annotated method directly. Avoids {@code @QuarkusTest} so
 * the suite stays cheap on CI.
 *
 * <p>The {@code @Incoming} annotation is irrelevant here — Quarkus's
 * reactive-messaging build-time validator covers the wiring; this test only
 * verifies delegation semantics. Mirrors decision-engine's
 * {@code BarConsumerTest}.
 */
class TradeProposedConsumerTest {

    private static final Instant PROPOSED_AT = Instant.parse("2026-04-30T13:32:00Z");

    private RecordingRouter router;
    private TradeProposedConsumer consumer;

    @BeforeEach
    void setUp() {
        router = new RecordingRouter();
        consumer = new TradeProposedConsumer(router);
    }

    @Test
    void consumeForwardsToRouter() {
        TradeProposed event = eventOf("OWNER", "trade-1");
        consumer.consume(event);
        assertThat(router.received).containsExactly(event);
    }

    @Test
    void consumePreservesOrderingAcrossMultipleEvents() {
        TradeProposed first = eventOf("OWNER", "trade-1");
        TradeProposed second = eventOf("OWNER", "trade-2");
        TradeProposed third = eventOf("ACME", "trade-3");

        consumer.consume(first);
        consumer.consume(second);
        consumer.consume(third);

        assertThat(router.received).containsExactly(first, second, third);
    }

    @Test
    void noOpTradeRouterCountsEveryEvent() {
        // Cross-check that the production default router actually counts.
        NoOpTradeRouter realRouter = new NoOpTradeRouter();
        TradeProposedConsumer realConsumer = new TradeProposedConsumer(realRouter);

        realConsumer.consume(eventOf("OWNER", "trade-1"));
        realConsumer.consume(eventOf("OWNER", "trade-2"));
        realConsumer.consume(eventOf("OWNER", "trade-3"));

        assertThat(realRouter.count()).isEqualTo(3L);
    }

    private static TradeProposed eventOf(String tenantId, String tradeId) {
        return new TradeProposed(
                tenantId,
                tradeId,
                LocalDate.parse("2026-04-30"),
                PROPOSED_AT,
                "SPY",
                OptionSide.CALL,
                "SPY260430C00595000",
                BigDecimal.valueOf(1.20),
                BigDecimal.valueOf(1.25),
                BigDecimal.valueOf(1.225),
                Optional.of(BigDecimal.valueOf(0.18)),
                Optional.of(BigDecimal.valueOf(0.50)),
                "corr-" + tradeId,
                List.of("pdh_sweep", "vwap_above"));
    }

    /** Recording stub — captures every event handed to it for assertions. */
    private static final class RecordingRouter implements TradeRouter {
        final List<TradeProposed> received = new ArrayList<>();

        @Override
        public void onTradeProposed(TradeProposed event) {
            received.add(event);
        }
    }
}
