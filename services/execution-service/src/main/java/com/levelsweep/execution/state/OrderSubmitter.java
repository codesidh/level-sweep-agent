package com.levelsweep.execution.state;

import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;

/**
 * Broker-agnostic gateway for submitting an {@link OrderRequest}. Lives here in
 * {@code execution/state/} (not in shared-domain) because it is purely an
 * execution-service-internal SPI — Alpaca-specific clients implement it, but
 * decision-engine and journal-service have no business calling a broker
 * directly.
 *
 * <p>This interface exists to break the merge-order coupling between Phase 3
 * Step 6 (EOD flatten saga, this PR) and Step 2 (Alpaca trading client). Step 6
 * injects {@code Instance<OrderSubmitter>} so the bean is optional at runtime;
 * Step 2's {@code AlpacaTradingClient} will declare {@code implements
 * OrderSubmitter} when it lands. Until then, the EOD scheduler degrades
 * gracefully (logs a WARN and exits without firing any orders).
 *
 * <p>Implementations must be thread-safe — the EOD saga calls
 * {@link #submit(OrderRequest)} sequentially within a single fire, but the
 * routing layer in Step 2 will share the bean with the entry-order path.
 *
 * <p>Failure contract: broker rejection ({@link OrderSubmission.Rejected}) and
 * transport failure ({@link OrderSubmission.FailedWithError}) are returned as
 * sealed-interface variants of {@link OrderSubmission} — they are values, not
 * exceptions. Callers pattern-match on the outcome. Implementations may still
 * throw a {@link RuntimeException} on truly unexpected internal failures (NPE,
 * misconfigured client) — the EOD saga catches as a safety net. The saga does
 * NOT retry on any failure path: the 15:55 ET cushion before 16:00 ET 0DTE
 * auto-exercise is too short for retry-with-backoff.
 */
public interface OrderSubmitter {

    /**
     * Submit an order. Returns the broker outcome as a sealed
     * {@link OrderSubmission} — never throws on an expected reject. Internal
     * unexpected failures may still propagate as a {@link RuntimeException}.
     */
    OrderSubmission submit(OrderRequest request);
}
