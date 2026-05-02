package com.levelsweep.execution.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.execution.alpaca.AlpacaTradingClient;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import com.levelsweep.shared.domain.trade.TradeOrderRejected;
import com.levelsweep.shared.domain.trade.TradeOrderSubmitted;
import com.levelsweep.shared.domain.trade.TradeProposed;
import jakarta.enterprise.event.Event;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plain-JUnit unit tests for {@link OrderPlacingTradeRouter}. Mockito-stubs the
 * {@link AlpacaTradingClient} so we can drive each {@link OrderSubmission}
 * variant; verifies the right CDI event fires with the right correlation id.
 *
 * <p>Determinism check: same {@link TradeProposed} produces the same
 * {@code OrderRequest} clientOrderId across two builds.
 */
class OrderPlacingTradeRouterTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-30T13:32:30Z"), ZoneOffset.UTC);

    private AlpacaTradingClient client;
    private Event<TradeOrderSubmitted> submittedEvent;
    private Event<TradeOrderRejected> rejectedEvent;
    private OrderPlacingTradeRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(AlpacaTradingClient.class);
        submittedEvent = (Event<TradeOrderSubmitted>) mock(Event.class);
        rejectedEvent = (Event<TradeOrderRejected>) mock(Event.class);
        router = new OrderPlacingTradeRouter(client, FIXED_CLOCK, submittedEvent, rejectedEvent, 1);
    }

    @Test
    void submittedOutcomeFiresTradeOrderSubmittedWithRouterFields() {
        TradeProposed event = eventOf("OWNER", "trade-abc");
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Submitted(
                        "alpaca-id-123", "OWNER:trade-abc", "accepted", FIXED_CLOCK.instant()));

        router.onTradeProposed(event);

        ArgumentCaptor<TradeOrderSubmitted> captor = ArgumentCaptor.forClass(TradeOrderSubmitted.class);
        verify(submittedEvent, times(1)).fire(captor.capture());
        verify(rejectedEvent, never()).fire(any(TradeOrderRejected.class));

        TradeOrderSubmitted fired = captor.getValue();
        assertThat(fired.tenantId()).isEqualTo("OWNER");
        assertThat(fired.tradeId()).isEqualTo("trade-abc");
        assertThat(fired.correlationId()).isEqualTo("corr-trade-abc");
        assertThat(fired.contractSymbol()).isEqualTo("SPY260430C00595000");
        assertThat(fired.quantity()).isEqualTo(1);
        assertThat(fired.alpacaOrderId()).isEqualTo("alpaca-id-123");
        assertThat(fired.clientOrderId()).isEqualTo("OWNER:trade-abc");
        assertThat(fired.status()).isEqualTo("accepted");
        assertThat(fired.submittedAt()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    void rejectedOutcomeFiresTradeOrderRejectedWithHttpStatus() {
        TradeProposed event = eventOf("OWNER", "trade-abc");
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Rejected("OWNER:trade-abc", 422, "duplicate client_order_id"));

        router.onTradeProposed(event);

        verify(submittedEvent, never()).fire(any(TradeOrderSubmitted.class));
        ArgumentCaptor<TradeOrderRejected> captor = ArgumentCaptor.forClass(TradeOrderRejected.class);
        verify(rejectedEvent, times(1)).fire(captor.capture());

        TradeOrderRejected fired = captor.getValue();
        assertThat(fired.tenantId()).isEqualTo("OWNER");
        assertThat(fired.tradeId()).isEqualTo("trade-abc");
        assertThat(fired.correlationId()).isEqualTo("corr-trade-abc");
        assertThat(fired.contractSymbol()).isEqualTo("SPY260430C00595000");
        assertThat(fired.clientOrderId()).isEqualTo("OWNER:trade-abc");
        assertThat(fired.httpStatus()).isEqualTo(422);
        assertThat(fired.reason()).isEqualTo("duplicate client_order_id");
        assertThat(fired.rejectedAt()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    void failedWithErrorOutcomeFiresTradeOrderRejectedWithTransportSentinel() {
        TradeProposed event = eventOf("OWNER", "trade-abc");
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.FailedWithError(
                        "OWNER:trade-abc", new IOException("connection reset").toString()));

        router.onTradeProposed(event);

        ArgumentCaptor<TradeOrderRejected> captor = ArgumentCaptor.forClass(TradeOrderRejected.class);
        verify(rejectedEvent, times(1)).fire(captor.capture());
        verify(submittedEvent, never()).fire(any(TradeOrderSubmitted.class));

        TradeOrderRejected fired = captor.getValue();
        assertThat(fired.httpStatus()).isEqualTo(TradeOrderRejected.HTTP_STATUS_TRANSPORT_FAILURE);
        assertThat(fired.reason()).contains("connection reset");
    }

    @Test
    void buildRequestProducesDeterministicClientOrderIdAndLimitPrice() {
        TradeProposed event = eventOf("OWNER", "trade-abc");

        OrderRequest req1 = router.buildRequest(event);
        OrderRequest req2 = router.buildRequest(event);

        assertThat(req1.clientOrderId()).isEqualTo("OWNER:trade-abc");
        assertThat(req2.clientOrderId()).isEqualTo(req1.clientOrderId());
        assertThat(req2.limitPrice()).isEqualTo(req1.limitPrice());
        // Sanity: 1.20 / 1.25 → mid 1.225 → BUY +0.01 → 1.235 → 1.24
        assertThat(req1.limitPrice()).hasValue(new BigDecimal("1.24"));
        assertThat(req1.side()).isEqualTo(OrderRequest.SIDE_BUY);
        assertThat(req1.type()).isEqualTo(OrderRequest.TYPE_LIMIT);
        assertThat(req1.timeInForce()).isEqualTo(OrderRequest.TIF_DAY);
        assertThat(req1.quantity()).isEqualTo(1);
        assertThat(req1.contractSymbol()).isEqualTo("SPY260430C00595000");
    }

    @Test
    void requestPassedToClientCarriesProposedTradeFields() {
        TradeProposed event = eventOf("OWNER", "trade-abc");
        when(client.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Submitted(
                        "alpaca-id-123", "OWNER:trade-abc", "accepted", FIXED_CLOCK.instant()));

        router.onTradeProposed(event);

        ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(client).submit(captor.capture());
        OrderRequest sent = captor.getValue();
        assertThat(sent.tenantId()).isEqualTo("OWNER");
        assertThat(sent.tradeId()).isEqualTo("trade-abc");
        assertThat(sent.contractSymbol()).isEqualTo("SPY260430C00595000");
        assertThat(sent.clientOrderId()).isEqualTo("OWNER:trade-abc");
        assertThat(sent.quantity()).isEqualTo(1);
    }

    @Test
    void rejectsConfiguredQuantityZeroOrNegative() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new OrderPlacingTradeRouter(client, FIXED_CLOCK, submittedEvent, rejectedEvent, 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new OrderPlacingTradeRouter(client, FIXED_CLOCK, submittedEvent, rejectedEvent, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentTradesProduceDifferentClientOrderIds() {
        TradeProposed first = eventOf("OWNER", "trade-1");
        TradeProposed second = eventOf("ACME", "trade-2");

        OrderRequest r1 = router.buildRequest(first);
        OrderRequest r2 = router.buildRequest(second);

        assertThat(r1.clientOrderId()).isEqualTo("OWNER:trade-1");
        assertThat(r2.clientOrderId()).isEqualTo("ACME:trade-2");
        assertThat(r1.clientOrderId()).isNotEqualTo(r2.clientOrderId());
    }

    private static TradeProposed eventOf(String tenantId, String tradeId) {
        return new TradeProposed(
                tenantId,
                tradeId,
                LocalDate.parse("2026-04-30"),
                Instant.parse("2026-04-30T13:32:00Z"),
                "SPY",
                OptionSide.CALL,
                "SPY260430C00595000",
                BigDecimal.valueOf(1.20),
                BigDecimal.valueOf(1.25),
                BigDecimal.valueOf(1.225),
                Optional.of(BigDecimal.valueOf(0.18)),
                Optional.of(BigDecimal.valueOf(0.50)),
                "corr-" + tradeId,
                List.of("pdh_sweep"));
    }
}
