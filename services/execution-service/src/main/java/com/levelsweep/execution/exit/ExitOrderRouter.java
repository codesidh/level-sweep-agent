package com.levelsweep.execution.exit;

import com.levelsweep.execution.alpaca.AlpacaTradingClient;
import com.levelsweep.execution.state.InFlightTradeCache;
import com.levelsweep.shared.domain.trade.InFlightTrade;
import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import com.levelsweep.shared.domain.trade.TradeExitOrderSubmitted;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single exit-path writer per ADR-0005 §4. Observes both
 * {@link TradeStopTriggered} and {@link TradeTrailBreached} CDI events;
 * builds a {@code MARKET SELL DAY} {@link OrderRequest} with deterministic
 * {@code clientOrderId = "<tenantId>:<tradeId>:exit"}; submits via
 * {@link AlpacaTradingClient#submit}; fires {@link TradeExitOrderSubmitted}
 * on success.
 *
 * <p>Single-attempt exit (no retry) per ADR-0005 §4 — a re-submitted in-flight
 * market exit risks double-exit, which on a 0DTE position is unrecoverable
 * before settlement. Broker rejection or transport failure produces a WARN
 * log only; the operator's runbook for an "exit emitted but never accepted"
 * incident is to manually flatten via Alpaca's UI.
 *
 * <p>Idempotency: the deterministic {@code clientOrderId} ensures Alpaca
 * rejects duplicates with 422, so even a hypothetical simultaneous stop +
 * trail trigger results in exactly one accepted exit order.
 *
 * <p>Quantity lookup: pulled from {@link InFlightTradeCache}. The
 * {@link com.levelsweep.execution.state.InFlightTradeRegistrar} bridges
 * {@code TradeFilled} → cache.put so the cache is always populated by the
 * time a trigger fires. Missing cache entry → WARN + no exit (operator
 * manual review).
 *
 * <p>Determinism: identical trigger event + cache state produces a
 * bit-identical {@link OrderRequest}. Replay-parity harness asserts on the
 * captured {@link TradeExitOrderSubmitted} payload.
 */
@ApplicationScoped
public class ExitOrderRouter {

    private static final Logger LOG = LoggerFactory.getLogger(ExitOrderRouter.class);

    private final AlpacaTradingClient client;
    private final InFlightTradeCache cache;
    private final Event<TradeExitOrderSubmitted> exitSubmittedEvent;

    @Inject
    public ExitOrderRouter(
            AlpacaTradingClient client, InFlightTradeCache cache, Event<TradeExitOrderSubmitted> exitSubmittedEvent) {
        this.client = Objects.requireNonNull(client, "client");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.exitSubmittedEvent = Objects.requireNonNull(exitSubmittedEvent, "exitSubmittedEvent");
    }

    void onStop(@Observes TradeStopTriggered event) {
        Objects.requireNonNull(event, "event");
        submitExit(
                event.tenantId(),
                event.tradeId(),
                event.contractSymbol(),
                event.correlationId(),
                TradeExitOrderSubmitted.EXIT_REASON_STOP);
    }

    void onTrailBreach(@Observes TradeTrailBreached event) {
        Objects.requireNonNull(event, "event");
        submitExit(
                event.tenantId(),
                event.tradeId(),
                event.contractSymbol(),
                event.correlationId(),
                TradeExitOrderSubmitted.EXIT_REASON_TRAIL);
    }

    /**
     * Build + submit one exit order. Quantity is read from the in-flight
     * cache; if the cache entry is missing (saga lifecycle bug or operator
     * race), log a WARN and bail — better to leave the position open and
     * alert than to submit a guess that would ruin reconciliation.
     */
    void submitExit(String tenantId, String tradeId, String contractSymbol, String correlationId, String exitReason) {
        Optional<InFlightTrade> cached = findInCache(tradeId);
        if (cached.isEmpty()) {
            LOG.warn(
                    "exit router: in-flight cache miss tenantId={} tradeId={} contractSymbol={} reason={} — skipping exit submit",
                    tenantId,
                    tradeId,
                    contractSymbol,
                    exitReason);
            return;
        }
        InFlightTrade t = cached.get();

        String clientOrderId = tenantId + ":" + tradeId + ":exit";
        OrderRequest req = new OrderRequest(
                tenantId,
                tradeId,
                contractSymbol,
                t.quantity(),
                OrderRequest.SIDE_SELL,
                OrderRequest.TYPE_MARKET,
                Optional.empty(),
                OrderRequest.TIF_DAY,
                clientOrderId);

        OrderSubmission outcome;
        try {
            outcome = client.submit(req);
        } catch (RuntimeException e) {
            LOG.warn(
                    "exit router: unexpected exception from client tenantId={} tradeId={} contractSymbol={} reason={}: {}",
                    tenantId,
                    tradeId,
                    contractSymbol,
                    exitReason,
                    e.toString());
            return;
        }

        switch (outcome) {
            case OrderSubmission.Submitted s -> {
                LOG.info(
                        "exit submitted tenantId={} tradeId={} contractSymbol={} reason={} alpacaOrderId={} clientOrderId={} status={}",
                        tenantId,
                        tradeId,
                        contractSymbol,
                        exitReason,
                        s.alpacaOrderId(),
                        s.clientOrderId(),
                        s.status());
                exitSubmittedEvent.fire(new TradeExitOrderSubmitted(
                        tenantId,
                        tradeId,
                        correlationId,
                        contractSymbol,
                        t.quantity(),
                        s.alpacaOrderId(),
                        s.clientOrderId(),
                        s.status(),
                        s.submittedAt(),
                        exitReason));
            }
            case OrderSubmission.Rejected r -> LOG.warn(
                    "exit router: broker rejected tenantId={} tradeId={} contractSymbol={} reason={} clientOrderId={} status={} brokerReason={}",
                    tenantId,
                    tradeId,
                    contractSymbol,
                    exitReason,
                    r.clientOrderId(),
                    r.httpStatus(),
                    r.reason());
            case OrderSubmission.FailedWithError f -> LOG.warn(
                    "exit router: transport failure tenantId={} tradeId={} contractSymbol={} reason={} clientOrderId={} cause={}",
                    tenantId,
                    tradeId,
                    contractSymbol,
                    exitReason,
                    f.clientOrderId(),
                    f.exceptionMessage());
        }
    }

    private Optional<InFlightTrade> findInCache(String tradeId) {
        for (InFlightTrade t : cache.snapshot()) {
            if (t.tradeId().equals(tradeId)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
