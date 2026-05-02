package com.levelsweep.execution.exit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.levelsweep.execution.alpaca.AlpacaTradingClient;
import com.levelsweep.execution.state.InFlightTradeCache;
import com.levelsweep.shared.domain.trade.InFlightTrade;
import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import com.levelsweep.shared.domain.trade.TradeExitOrderSubmitted;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import jakarta.enterprise.event.Event;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ExitOrderRouterTest {

    private static final Instant FILL_AT = Instant.parse("2026-04-30T13:30:00Z");
    private static final Instant TRIGGERED = Instant.parse("2026-04-30T14:00:00.250Z");
    private static final Instant SUBMITTED = Instant.parse("2026-04-30T14:00:00.300Z");

    private InFlightTradeCache cache;
    private AlpacaTradingClient client;
    private Event<TradeExitOrderSubmitted> exitEvent;
    private ExitOrderRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cache = new InFlightTradeCache();
        client = Mockito.mock(AlpacaTradingClient.class);
        exitEvent = Mockito.mock(Event.class);
        router = new ExitOrderRouter(client, cache, exitEvent);
    }

    private static InFlightTrade trade(String tradeId, String symbol, int qty) {
        return new InFlightTrade("OWNER", tradeId, symbol, qty, FILL_AT, "corr-" + tradeId);
    }

    private static TradeStopTriggered stopEvt(String tradeId, String contractSymbol) {
        return new TradeStopTriggered(
                "OWNER",
                tradeId,
                "alpaca-entry",
                contractSymbol,
                Instant.parse("2026-04-30T14:00:00Z"),
                new BigDecimal("594.00"),
                "EMA13",
                TRIGGERED,
                "corr-" + tradeId);
    }

    private static TradeTrailBreached trailEvt(String tradeId, String contractSymbol) {
        return new TradeTrailBreached(
                "OWNER",
                tradeId,
                contractSymbol,
                TRIGGERED,
                new BigDecimal("13.50"),
                new BigDecimal("0.35"),
                "corr-" + tradeId);
    }

    @Test
    void onStopBuildsCorrectExitRequestAndFiresEventOnSubmitted() {
        cache.put(trade("t1", "SPY260430C00595000", 1));
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Submitted("alpaca-exit-1", "OWNER:t1:exit", "accepted", SUBMITTED));

        router.onStop(stopEvt("t1", "SPY260430C00595000"));

        ArgumentCaptor<OrderRequest> reqCap = ArgumentCaptor.forClass(OrderRequest.class);
        verify(client, times(1)).submit(reqCap.capture());
        OrderRequest req = reqCap.getValue();
        assertThat(req.tenantId()).isEqualTo("OWNER");
        assertThat(req.tradeId()).isEqualTo("t1");
        assertThat(req.contractSymbol()).isEqualTo("SPY260430C00595000");
        assertThat(req.quantity()).isEqualTo(1);
        assertThat(req.side()).isEqualTo("sell");
        assertThat(req.type()).isEqualTo("market");
        assertThat(req.timeInForce()).isEqualTo("day");
        assertThat(req.limitPrice()).isEmpty();
        assertThat(req.clientOrderId()).isEqualTo("OWNER:t1:exit");

        ArgumentCaptor<TradeExitOrderSubmitted> evtCap = ArgumentCaptor.forClass(TradeExitOrderSubmitted.class);
        verify(exitEvent, times(1)).fire(evtCap.capture());
        TradeExitOrderSubmitted evt = evtCap.getValue();
        assertThat(evt.alpacaOrderId()).isEqualTo("alpaca-exit-1");
        assertThat(evt.exitReason()).isEqualTo("STOP");
        assertThat(evt.correlationId()).isEqualTo("corr-t1");
        assertThat(evt.submittedAt()).isEqualTo(SUBMITTED);
        assertThat(evt.quantity()).isEqualTo(1);
    }

    @Test
    void onTrailBreachFiresEventWithTrailReason() {
        cache.put(trade("t1", "SPY260430C00595000", 2));
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Submitted("alpaca-exit-2", "OWNER:t1:exit", "accepted", SUBMITTED));

        router.onTrailBreach(trailEvt("t1", "SPY260430C00595000"));

        ArgumentCaptor<TradeExitOrderSubmitted> evtCap = ArgumentCaptor.forClass(TradeExitOrderSubmitted.class);
        verify(exitEvent, times(1)).fire(evtCap.capture());
        assertThat(evtCap.getValue().exitReason()).isEqualTo("TRAIL");
        assertThat(evtCap.getValue().quantity()).isEqualTo(2);
    }

    @Test
    void rejectionDoesNotFireEvent() {
        cache.put(trade("t1", "SPY260430C00595000", 1));
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Rejected("OWNER:t1:exit", 422, "duplicate client_order_id"));

        router.onStop(stopEvt("t1", "SPY260430C00595000"));

        verify(client, times(1)).submit(any(OrderRequest.class));
        verify(exitEvent, never()).fire(any(TradeExitOrderSubmitted.class));
    }

    @Test
    void transportFailureDoesNotFireEvent() {
        cache.put(trade("t1", "SPY260430C00595000", 1));
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.FailedWithError("OWNER:t1:exit", "connect timeout"));

        router.onTrailBreach(trailEvt("t1", "SPY260430C00595000"));

        verify(client, times(1)).submit(any(OrderRequest.class));
        verify(exitEvent, never()).fire(any(TradeExitOrderSubmitted.class));
    }

    @Test
    void cacheMissSkipsSubmitAndEvent() {
        // No cache.put — trade is unknown.
        router.onStop(stopEvt("t-unknown", "SPY260430C00595000"));

        verifyNoInteractions(client, exitEvent);
    }

    @Test
    void clientThrowingDoesNotPropagate() {
        cache.put(trade("t1", "SPY260430C00595000", 1));
        when(client.submit(any(OrderRequest.class))).thenThrow(new RuntimeException("npe"));

        // Must not propagate.
        router.onStop(stopEvt("t1", "SPY260430C00595000"));

        verify(exitEvent, never()).fire(any(TradeExitOrderSubmitted.class));
    }

    @Test
    void clientOrderIdIsDeterministic() {
        cache.put(trade("t1", "SPY260430C00595000", 1));
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Submitted("alpaca-exit-1", "OWNER:t1:exit", "accepted", SUBMITTED));

        router.onStop(stopEvt("t1", "SPY260430C00595000"));
        // Re-fire — same trade, same key.
        cache.put(trade("t1", "SPY260430C00595000", 1));
        router.onTrailBreach(trailEvt("t1", "SPY260430C00595000"));

        ArgumentCaptor<OrderRequest> cap = ArgumentCaptor.forClass(OrderRequest.class);
        verify(client, times(2)).submit(cap.capture());
        assertThat(cap.getAllValues())
                .extracting(OrderRequest::clientOrderId)
                .containsExactly("OWNER:t1:exit", "OWNER:t1:exit");
    }
}
