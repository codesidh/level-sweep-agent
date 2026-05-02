package com.levelsweep.decision.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.levelsweep.decision.signal.IndicatorSnapshotHolder;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit unit test for {@link IndicatorSnapshotConsumer}: constructs the consumer
 * with a real (in-process, non-CDI) {@link IndicatorSnapshotHolder} and invokes the
 * {@code @Incoming}-annotated method directly. Avoids {@code @QuarkusTest} so the suite
 * stays cheap on CI.
 *
 * <p>The {@code @Incoming} annotation is irrelevant here — Quarkus's reactive-messaging
 * build-time validator covers the wiring; this test only verifies delegation semantics.
 *
 * <p>Mirrors the pattern in {@link BarConsumerTest}.
 */
class IndicatorSnapshotConsumerTest {

    private static final Instant TS = Instant.parse("2026-04-30T13:32:00Z");

    private IndicatorSnapshotHolder holder;
    private IndicatorSnapshotConsumer consumer;

    @BeforeEach
    void setUp() {
        holder = new IndicatorSnapshotHolder();
        consumer = new IndicatorSnapshotConsumer(holder);
    }

    @Test
    void consumeSnapshotForwardsToHolder() {
        IndicatorSnapshot snap = snapOf("SPY", TS);

        consumer.consumeSnapshot(snap);

        assertThat(holder.latest()).contains(snap);
    }

    @Test
    void laterSnapshotReplacesEarlierInHolder() {
        // Last-value-wins semantics — IndicatorSnapshotHolder's AtomicReference.set is
        // unconditional, mirroring how it would behave under live partition-keyed traffic.
        IndicatorSnapshot earlier = snapOf("SPY", TS);
        IndicatorSnapshot later = snapOf("SPY", TS.plusSeconds(120));

        consumer.consumeSnapshot(earlier);
        consumer.consumeSnapshot(later);

        assertThat(holder.latest()).contains(later);
    }

    @Test
    void rejectsNullSnapshot() {
        // Mirrors BarConsumer's null guard; a poison-pill record on the channel must
        // not silently overwrite the holder with null and would point at a serializer
        // bug worth surfacing.
        assertThatNullPointerException().isThrownBy(() -> consumer.consumeSnapshot(null));
    }

    @Test
    void consumerIsApplicationScoped() {
        // CDI scope guarantees one instance per Quarkus app — required because the
        // first-snapshot INFO log latch is local state. A non-singleton scope would
        // re-log INFO on every consumption, which would flood the operator log.
        assertThat(IndicatorSnapshotConsumer.class.isAnnotationPresent(ApplicationScoped.class))
                .isTrue();
    }

    private static IndicatorSnapshot snapOf(String symbol, Instant timestamp) {
        return new IndicatorSnapshot(
                symbol,
                timestamp,
                BigDecimal.valueOf(594.10),
                BigDecimal.valueOf(594.05),
                BigDecimal.valueOf(593.20),
                BigDecimal.valueOf(1.45));
    }
}
