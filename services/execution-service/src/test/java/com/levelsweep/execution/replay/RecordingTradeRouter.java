package com.levelsweep.execution.replay;

import com.levelsweep.execution.ingest.TradeRouter;
import com.levelsweep.shared.domain.trade.TradeProposed;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test-only {@link TradeRouter} that captures every {@link TradeProposed}
 * handed to it for downstream assertions. No I/O, no clock dependencies, no
 * randomness — drop-in replacement for {@code NoOpTradeRouter} in the replay
 * harness.
 *
 * <p>Production code uses S2's {@code OrderPlacingTradeRouter} (still in flight
 * on a sibling branch); this class is the harness's analogue, structurally
 * equivalent to {@code NoOpTradeRouter} but exposing the full capture list
 * instead of only a count.
 *
 * <p>Determinism: insertion order is preserved (backing {@link ArrayList}); the
 * {@link #captured()} accessor returns an unmodifiable copy, so two runs
 * comparing capture lists are byte-equal across Java versions and JVM seeds.
 */
public final class RecordingTradeRouter implements TradeRouter {

    private final List<TradeProposed> captured = new ArrayList<>();

    @Override
    public void onTradeProposed(TradeProposed event) {
        captured.add(Objects.requireNonNull(event, "event"));
    }

    /** Defensive immutable copy — preserves insertion order. */
    public List<TradeProposed> captured() {
        return List.copyOf(captured);
    }

    /** Test seam — clear between scenario invocations within one pipeline. */
    public void reset() {
        captured.clear();
    }
}
