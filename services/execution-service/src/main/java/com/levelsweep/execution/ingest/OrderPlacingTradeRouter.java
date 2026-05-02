package com.levelsweep.execution.ingest;

import com.levelsweep.execution.alpaca.AlpacaTradingClient;
import com.levelsweep.execution.alpaca.OrderPricing;
import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import com.levelsweep.shared.domain.trade.TradeOrderRejected;
import com.levelsweep.shared.domain.trade.TradeOrderSubmitted;
import com.levelsweep.shared.domain.trade.TradeProposed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3 Step 2 {@link TradeRouter}: turns each {@code TradeProposed} into a
 * single Alpaca paper-trading entry order. Wins over {@link NoOpTradeRouter}
 * via Quarkus ARC's standard non-{@code @DefaultBean} precedence — no extra
 * annotation needed.
 *
 * <p>Pipeline: {@code TradeProposed} →
 * {@link OrderPricing#limitPriceFromNbbo} → {@link OrderRequest} →
 * {@link AlpacaTradingClient#submit} → either
 * {@link TradeOrderSubmitted} or {@link TradeOrderRejected} fired as a CDI
 * event for the Phase 3 Step 3 fill listener and the Phase 4 narrator.
 *
 * <p><b>No-retry policy:</b> per architecture-spec §17.4 the entry path makes
 * a single attempt. The signal is time-sensitive and the spread widens as
 * latency accumulates; a second attempt is strictly worse than the first
 * attempt's slippage. Both rejection and transport failure produce
 * {@link TradeOrderRejected} and end the trade's entry path.
 *
 * <p><b>Idempotency:</b> {@code clientOrderId = "<tenantId>:<tradeId>"}.
 * Alpaca rejects duplicates with 422, so a stuck-pipeline replay never double
 * fires.
 *
 * <p><b>Determinism:</b> identical {@link TradeProposed} produces an
 * identical {@link OrderRequest} (same clientOrderId, same limit price). The
 * replay-parity harness asserts on the captured CDI event payload.
 */
@ApplicationScoped
public class OrderPlacingTradeRouter implements TradeRouter {

    private static final Logger LOG = LoggerFactory.getLogger(OrderPlacingTradeRouter.class);

    private final AlpacaTradingClient client;
    private final Clock clock;
    private final Event<TradeOrderSubmitted> submittedEvent;
    private final Event<TradeOrderRejected> rejectedEvent;
    private final int quantity;

    @Inject
    public OrderPlacingTradeRouter(
            AlpacaTradingClient client,
            Clock clock,
            Event<TradeOrderSubmitted> submittedEvent,
            Event<TradeOrderRejected> rejectedEvent,
            @ConfigProperty(name = "execution.fixed-quantity", defaultValue = "1") int quantity) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.submittedEvent = Objects.requireNonNull(submittedEvent, "submittedEvent");
        this.rejectedEvent = Objects.requireNonNull(rejectedEvent, "rejectedEvent");
        if (quantity <= 0) {
            throw new IllegalArgumentException("execution.fixed-quantity must be > 0: " + quantity);
        }
        this.quantity = quantity;
    }

    @Override
    public void onTradeProposed(TradeProposed event) {
        Objects.requireNonNull(event, "event");
        OrderRequest req = buildRequest(event);
        OrderSubmission outcome = client.submit(req);

        switch (outcome) {
            case OrderSubmission.Submitted s -> {
                LOG.info(
                        "trade-order submitted tenant={} tradeId={} alpacaOrderId={} clientOrderId={} status={} contractSymbol={}",
                        event.tenantId(),
                        event.tradeId(),
                        s.alpacaOrderId(),
                        s.clientOrderId(),
                        s.status(),
                        event.contractSymbol());
                submittedEvent.fire(new TradeOrderSubmitted(
                        event.tenantId(),
                        event.tradeId(),
                        event.correlationId(),
                        event.contractSymbol(),
                        req.quantity(),
                        s.alpacaOrderId(),
                        s.clientOrderId(),
                        s.status(),
                        s.submittedAt()));
            }
            case OrderSubmission.Rejected r -> {
                LOG.warn(
                        "trade-order rejected tenant={} tradeId={} clientOrderId={} status={} contractSymbol={} reason={}",
                        event.tenantId(),
                        event.tradeId(),
                        r.clientOrderId(),
                        r.httpStatus(),
                        event.contractSymbol(),
                        r.reason());
                rejectedEvent.fire(new TradeOrderRejected(
                        event.tenantId(),
                        event.tradeId(),
                        event.correlationId(),
                        event.contractSymbol(),
                        r.clientOrderId(),
                        r.httpStatus(),
                        r.reason(),
                        clock.instant()));
            }
            case OrderSubmission.FailedWithError f -> {
                LOG.warn(
                        "trade-order transport failure tenant={} tradeId={} clientOrderId={} contractSymbol={} reason={}",
                        event.tenantId(),
                        event.tradeId(),
                        f.clientOrderId(),
                        event.contractSymbol(),
                        f.exceptionMessage());
                rejectedEvent.fire(new TradeOrderRejected(
                        event.tenantId(),
                        event.tradeId(),
                        event.correlationId(),
                        event.contractSymbol(),
                        f.clientOrderId(),
                        TradeOrderRejected.HTTP_STATUS_TRANSPORT_FAILURE,
                        f.exceptionMessage(),
                        clock.instant()));
            }
        }
    }

    /**
     * Build a deterministic {@link OrderRequest} from the proposed trade.
     * Package-private so the test can assert on the constructed payload
     * without going through the Alpaca client stub.
     */
    OrderRequest buildRequest(TradeProposed event) {
        BigDecimal limit =
                OrderPricing.limitPriceFromNbbo(event.entryNbboBid(), event.entryNbboAsk(), OrderRequest.SIDE_BUY);
        return new OrderRequest(
                event.tenantId(),
                event.tradeId(),
                event.contractSymbol(),
                quantity,
                OrderRequest.SIDE_BUY,
                OrderRequest.TYPE_LIMIT,
                Optional.of(limit),
                OrderRequest.TIF_DAY,
                OrderRequest.idempotencyKey(event.tenantId(), event.tradeId()));
    }

    /** Test seam — exposes the configured fixed quantity for assertions. */
    int quantity() {
        return quantity;
    }
}
