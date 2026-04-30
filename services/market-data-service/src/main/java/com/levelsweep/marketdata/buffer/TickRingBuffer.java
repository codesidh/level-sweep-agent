package com.levelsweep.marketdata.buffer;

import com.levelsweep.shared.domain.marketdata.Tick;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded FIFO buffer for ticks. Drop-oldest on overflow.
 *
 * <p>Phase 1 — used by the Polygon adapter to absorb downstream slowness.
 * Per architecture-spec §9.1, the production deployment backs this with a
 * SQLite-on-disk ring (so a pod restart can resume from disk); Phase 1 ships
 * with the in-memory variant only and will swap implementations behind this
 * interface in Phase 7 (resilience hardening).
 *
 * <p>Thread-safe: external mutations are synchronized; reads return
 * immutable snapshots. Single-writer / multi-reader is the expected pattern
 * (the WS handler offers; consumers drain).
 */
public final class TickRingBuffer {

    private final int capacity;
    private final Deque<Tick> queue;
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong offeredCount = new AtomicLong();

    public TickRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    /**
     * Offer a tick. If the buffer is at capacity, the oldest entry is dropped
     * to make room. Returns true if the new tick was accepted (always, in this
     * drop-oldest strategy).
     */
    public synchronized boolean offer(Tick tick) {
        Objects.requireNonNull(tick, "tick");
        offeredCount.incrementAndGet();
        if (queue.size() >= capacity) {
            queue.pollFirst();
            droppedCount.incrementAndGet();
        }
        queue.offerLast(tick);
        return true;
    }

    /**
     * Drain up to {@code max} ticks in FIFO order. Empty list if the buffer
     * is empty.
     */
    public synchronized List<Tick> drain(int max) {
        if (max <= 0) {
            return Collections.emptyList();
        }
        List<Tick> out = new ArrayList<>(Math.min(max, queue.size()));
        while (!queue.isEmpty() && out.size() < max) {
            out.add(queue.pollFirst());
        }
        return out;
    }

    /** Drain everything currently buffered. */
    public synchronized List<Tick> drainAll() {
        if (queue.isEmpty()) {
            return Collections.emptyList();
        }
        List<Tick> out = new ArrayList<>(queue);
        queue.clear();
        return out;
    }

    /** Bulk offer convenience for tests / replay. */
    public synchronized void offerAll(Collection<Tick> ticks) {
        for (Tick t : ticks) {
            offer(t);
        }
    }

    public synchronized int size() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    public long offeredCount() {
        return offeredCount.get();
    }
}
