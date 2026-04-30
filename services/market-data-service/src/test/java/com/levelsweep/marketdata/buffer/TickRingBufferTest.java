package com.levelsweep.marketdata.buffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.marketdata.Tick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TickRingBufferTest {

    private static Tick tick(int i) {
        return new Tick(
                "SPY", BigDecimal.valueOf(594.0 + i * 0.01), 100L, Instant.ofEpochSecond(i));
    }

    @Test
    void capacityMustBePositive() {
        assertThatThrownBy(() -> new TickRingBuffer(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TickRingBuffer(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void offerAcceptsNonNullTicks() {
        TickRingBuffer buf = new TickRingBuffer(10);
        Tick t = tick(1);
        assertThat(buf.offer(t)).isTrue();
        assertThat(buf.size()).isEqualTo(1);
        assertThat(buf.offeredCount()).isEqualTo(1);
        assertThat(buf.droppedCount()).isZero();
    }

    @Test
    void drainReturnsFifoOrder() {
        TickRingBuffer buf = new TickRingBuffer(10);
        IntStream.range(0, 5).forEach(i -> buf.offer(tick(i)));
        List<Tick> drained = buf.drainAll();
        assertThat(drained).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(drained.get(i).timestamp()).isEqualTo(Instant.ofEpochSecond(i));
        }
        assertThat(buf.size()).isZero();
    }

    @Test
    void overflowDropsOldest() {
        TickRingBuffer buf = new TickRingBuffer(3);
        IntStream.range(0, 5).forEach(i -> buf.offer(tick(i)));
        // Buffer holds last 3 (ticks 2, 3, 4)
        assertThat(buf.size()).isEqualTo(3);
        assertThat(buf.droppedCount()).isEqualTo(2);
        assertThat(buf.offeredCount()).isEqualTo(5);
        List<Tick> remaining = buf.drainAll();
        assertThat(remaining).extracting(t -> t.timestamp().getEpochSecond()).containsExactly(2L, 3L, 4L);
    }

    @Test
    void drainBoundedByMax() {
        TickRingBuffer buf = new TickRingBuffer(10);
        IntStream.range(0, 7).forEach(i -> buf.offer(tick(i)));
        List<Tick> first = buf.drain(3);
        assertThat(first).hasSize(3);
        assertThat(buf.size()).isEqualTo(4);
        List<Tick> rest = buf.drainAll();
        assertThat(rest).hasSize(4);
    }

    @Test
    void drainEmptyReturnsEmptyList() {
        TickRingBuffer buf = new TickRingBuffer(10);
        assertThat(buf.drainAll()).isEmpty();
        assertThat(buf.drain(5)).isEmpty();
    }

    @Test
    void drainNonPositiveReturnsEmpty() {
        TickRingBuffer buf = new TickRingBuffer(10);
        buf.offer(tick(1));
        assertThat(buf.drain(0)).isEmpty();
        assertThat(buf.drain(-1)).isEmpty();
        assertThat(buf.size()).isEqualTo(1);
    }

    @Test
    void offerAllAcceptsCollection() {
        TickRingBuffer buf = new TickRingBuffer(10);
        buf.offerAll(List.of(tick(1), tick(2), tick(3)));
        assertThat(buf.size()).isEqualTo(3);
    }
}
