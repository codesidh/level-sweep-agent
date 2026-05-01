package com.levelsweep.decision.ingest;

import com.levelsweep.shared.domain.marketdata.Bar;

/**
 * Pluggable sink for bars consumed off the Kafka {@code market.bars.*} topics.
 *
 * <p>The Phase 2 Step 1 default is {@link NoOpBarRouter} (counts and rate-limits
 * a confirmation log line). Subsequent steps replace it by producing a different
 * {@code @ApplicationScoped} bean of this type:
 *
 * <ul>
 *   <li><b>Step 2 — Signal Engine</b>: routes each bar through the indicator
 *       refresher and PDH/PDL sweep detector, emitting candidate signals.
 *   <li><b>Step 3 — Risk Manager / Trade Saga</b>: gates signals on session +
 *       account state, persists trades.
 *   <li><b>Step 4 — Strike Selector + Order/Position FSMs</b>: completes the
 *       Phase 2 stack.
 * </ul>
 *
 * <p>The interface intentionally takes a fully-deserialized {@link Bar} rather
 * than a Kafka {@code Record}: the consumer layer is the only thing that should
 * care about the topic-to-timeframe mapping. Downstream stages route by
 * {@code bar.timeframe()} and never need the topic name.
 */
public interface BarRouter {

    /**
     * Handle a single deserialized bar. Implementations must not block the calling
     * thread — the Kafka consumer commits offsets after this returns, so a slow
     * router stalls all four bar topics. Heavy work goes to a dedicated executor.
     */
    void onBar(Bar bar);
}
