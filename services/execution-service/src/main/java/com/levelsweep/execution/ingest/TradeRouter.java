package com.levelsweep.execution.ingest;

import com.levelsweep.shared.domain.trade.TradeProposed;

/**
 * Pluggable sink for {@link TradeProposed} events consumed off the Kafka
 * {@code tenant.commands} topic.
 *
 * <p>The Phase 3 Step 1 default is {@link NoOpTradeRouter} (counts and
 * rate-limits a confirmation log line). Subsequent steps replace it by
 * producing a different {@code @ApplicationScoped} bean of this type:
 *
 * <ul>
 *   <li><b>Step 2 — Alpaca order placement</b>: routes each event through the
 *       Alpaca paper-trading REST client to submit an options entry order with a
 *       deterministic {@code client_order_id = sha256(tenant_id|trade_id|entry)}.
 *   <li><b>Step 3 — Fill listener</b>: consumes the trade-updates websocket and
 *       advances the trade FSM ENTERED → FILLED.
 *   <li><b>Step 4–5 — Stop + trailing manager</b>: places + amends stop-loss
 *       orders and trailing stops based on the underlying's price action.
 *   <li><b>Step 6 — EOD flatten</b>: a {@code @Scheduled} job that closes any
 *       still-open positions before market close.
 * </ul>
 *
 * <p>The interface intentionally takes a fully-deserialized {@link TradeProposed}
 * rather than a Kafka {@code Record}: the consumer layer is the only thing that
 * should care about topic names. Downstream stages route by event content and
 * never need the topic name.
 */
public interface TradeRouter {

    /**
     * Handle a single deserialized {@link TradeProposed}. Implementations must
     * not block the calling thread for longer than the consumer's
     * {@code max.poll.interval.ms} — the Kafka consumer commits offsets after
     * this returns, so a slow router stalls partition consumption. Heavy I/O
     * (Alpaca REST calls in S2) goes to a dedicated executor or async
     * Mutiny-based pathway.
     */
    void onTradeProposed(TradeProposed event);
}
