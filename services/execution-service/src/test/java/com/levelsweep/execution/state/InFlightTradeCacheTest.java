package com.levelsweep.execution.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.trade.InFlightTrade;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Concurrency-safety tests for {@link InFlightTradeCache}. The cache backs the
 * S3 fill listener (writer) and the S6 EOD scheduler (reader); they run on
 * different threads, so put/remove/snapshot must be safe under contention.
 */
class InFlightTradeCacheTest {

    private static InFlightTrade trade(String tradeId) {
        return new InFlightTrade(
                "OWNER", tradeId, "SPY260430C00595000", 1, Instant.parse("2026-04-30T13:30:00Z"), "corr-" + tradeId);
    }

    @Test
    void putAndRemoveRoundTrip() {
        InFlightTradeCache cache = new InFlightTradeCache();
        InFlightTrade t = trade("t1");

        cache.put(t);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.snapshot()).containsExactly(t);

        cache.remove("t1");

        assertThat(cache.size()).isZero();
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    void putReplacesExistingEntryByTradeId() {
        InFlightTradeCache cache = new InFlightTradeCache();
        InFlightTrade first = trade("t1");
        InFlightTrade second = new InFlightTrade(
                "OWNER", "t1", "SPY260430P00590000", 2, Instant.parse("2026-04-30T13:30:01Z"), "corr-t1-2");

        cache.put(first);
        cache.put(second);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.snapshot()).containsExactly(second);
    }

    @Test
    void removeMissingTradeIsNoOp() {
        InFlightTradeCache cache = new InFlightTradeCache();

        cache.remove("never-added");

        assertThat(cache.size()).isZero();
    }

    @Test
    void snapshotRejectsStructuralMutation() {
        InFlightTradeCache cache = new InFlightTradeCache();
        cache.put(trade("t1"));

        Collection<InFlightTrade> snap = cache.snapshot();

        assertThatThrownBy(() -> snap.add(trade("t-illegal"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(snap::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotReflectsLaterMutationsOfTheLiveBacking() {
        // The contract is that snapshot() returns a *live* read-only view, not
        // a defensive copy — this is what lets the EOD saga iterate while a
        // concurrent fill listener writes new entries without ConcurrentMod.
        InFlightTradeCache cache = new InFlightTradeCache();
        cache.put(trade("t1"));

        Collection<InFlightTrade> snap = cache.snapshot();

        cache.put(trade("t2"));

        assertThat(snap).hasSize(2);
    }

    @Test
    void concurrentPutsAndRemovesAreSafe() throws InterruptedException {
        InFlightTradeCache cache = new InFlightTradeCache();
        int writers = 8;
        int opsPerWriter = 500;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicInteger failures = new AtomicInteger();

        try {
            for (int w = 0; w < writers; w++) {
                final int writerId = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < opsPerWriter; i++) {
                            String tradeId = "t-" + writerId + "-" + i;
                            cache.put(trade(tradeId));
                            // Iterate snapshot — must not throw
                            // ConcurrentModificationException even while other
                            // writers are mutating the underlying map.
                            for (InFlightTrade t : cache.snapshot()) {
                                if (t.tradeId().isBlank()) {
                                    failures.incrementAndGet();
                                }
                            }
                            cache.remove(tradeId);
                        }
                    } catch (RuntimeException | InterruptedException e) {
                        failures.incrementAndGet();
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).isZero();
        assertThat(cache.size()).isZero();
    }

    @Test
    void snapshotDuringConcurrentPutDoesNotThrow() {
        // Drive the same weak-consistency invariant explicitly — the iterator
        // returned by snapshot() must tolerate puts mid-traversal.
        InFlightTradeCache cache = new InFlightTradeCache();
        for (int i = 0; i < 100; i++) {
            cache.put(trade("seed-" + i));
        }

        List<InFlightTrade> seen = new ArrayList<>();
        for (InFlightTrade t : cache.snapshot()) {
            seen.add(t);
            // Mutate while iterating.
            cache.put(trade("during-" + t.tradeId()));
        }

        // We don't assert seen contents — only that no exception escaped.
        assertThat(seen).isNotEmpty();
        assertThat(cache.size()).isGreaterThan(100);
    }
}
