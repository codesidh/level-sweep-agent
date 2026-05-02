package com.levelsweep.execution.eod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.levelsweep.execution.persistence.EodFlattenAuditRepository;
import com.levelsweep.execution.state.InFlightTradeCache;
import com.levelsweep.execution.state.OrderSubmitter;
import com.levelsweep.shared.domain.trade.EodFlattenAttempt;
import com.levelsweep.shared.domain.trade.InFlightTrade;
import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import com.levelsweep.shared.domain.trade.TradeEodFlattened;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for {@link EodFlattenScheduler}. Pure POJO assembly — no Quarkus
 * harness — so we can exercise the saga's branches in isolation:
 *
 * <ul>
 *   <li>OrderSubmitter unsatisfied → log + return, never touches cache or audit.
 *   <li>Empty cache → no-op.
 *   <li>Single trade → market sell submitted, audit FLATTENED, cache cleared, event fired.
 *   <li>Multiple trades → each submitted independently, all flattened.
 *   <li>Submitter throws → audit FAILED, cache untouched, no event fired, scheduler does NOT propagate.
 *   <li>Mixed success + failure → first FAILED does not block second from flattening.
 * </ul>
 */
class EodFlattenSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    // 15:55:00 ET on a regular session day → 19:55:00 UTC.
    private static final Instant NOW = Instant.parse("2026-04-30T19:55:00Z");
    private static final LocalDate SESSION = LocalDate.of(2026, 4, 30);

    private Clock clock;
    private InFlightTradeCache cache;
    private Instance<OrderSubmitter> submitterInstance;
    private OrderSubmitter submitter;
    private Event<TradeEodFlattened> flattenedEvent;
    private EodFlattenAuditRepository audit;
    private EodFlattenScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clock = Clock.fixed(NOW, ET);
        cache = new InFlightTradeCache();
        submitterInstance = Mockito.mock(Instance.class);
        submitter = Mockito.mock(OrderSubmitter.class);
        flattenedEvent = Mockito.mock(Event.class);
        audit = Mockito.mock(EodFlattenAuditRepository.class);
        scheduler = new EodFlattenScheduler(clock, cache, submitterInstance, flattenedEvent, audit);
    }

    private static InFlightTrade trade(String tradeId, String symbol, int qty) {
        return new InFlightTrade(
                "OWNER", tradeId, symbol, qty, Instant.parse("2026-04-30T13:30:00Z"), "corr-" + tradeId);
    }

    @Test
    void runOnceLogsAndReturnsWhenSubmitterUnresolvable() {
        when(submitterInstance.isUnsatisfied()).thenReturn(true);
        cache.put(trade("t1", "SPY260430C00595000", 1));

        scheduler.runOnce();

        // Cache untouched, no audit, no event, submitter never resolved.
        assertThat(cache.size()).isEqualTo(1);
        verifyNoInteractions(submitter, audit, flattenedEvent);
        verify(submitterInstance, never()).get();
    }

    @Test
    void runOnceSkipsWhenCacheIsEmpty() {
        when(submitterInstance.isUnsatisfied()).thenReturn(false);
        when(submitterInstance.get()).thenReturn(submitter);

        scheduler.runOnce();

        verifyNoInteractions(submitter, audit, flattenedEvent);
    }

    @Test
    void runOnceFlattensSingleTrade() {
        InFlightTrade t = trade("t1", "SPY260430C00595000", 1);
        cache.put(t);
        when(submitterInstance.isUnsatisfied()).thenReturn(false);
        when(submitterInstance.get()).thenReturn(submitter);
        when(submitter.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Submitted("alpaca-order-1", "eod:OWNER:t1", "accepted", NOW));

        scheduler.runOnce();

        // Order submitted with the right shape — market sell, day, deterministic clientOrderId.
        ArgumentCaptor<OrderRequest> reqCap = ArgumentCaptor.forClass(OrderRequest.class);
        verify(submitter, times(1)).submit(reqCap.capture());
        OrderRequest req = reqCap.getValue();
        assertThat(req.tenantId()).isEqualTo("OWNER");
        assertThat(req.tradeId()).isEqualTo("t1");
        assertThat(req.contractSymbol()).isEqualTo("SPY260430C00595000");
        assertThat(req.quantity()).isEqualTo(1);
        assertThat(req.side()).isEqualTo("sell");
        assertThat(req.type()).isEqualTo("market");
        assertThat(req.timeInForce()).isEqualTo("day");
        assertThat(req.limitPrice()).isEmpty();
        assertThat(req.clientOrderId()).isEqualTo("eod:OWNER:t1");

        // FLATTENED audit row, with broker order id captured.
        ArgumentCaptor<EodFlattenAttempt> auditCap = ArgumentCaptor.forClass(EodFlattenAttempt.class);
        verify(audit, times(1)).record(auditCap.capture());
        EodFlattenAttempt a = auditCap.getValue();
        assertThat(a.outcome()).isEqualTo(EodFlattenAttempt.Outcome.FLATTENED);
        assertThat(a.alpacaOrderId()).contains("alpaca-order-1");
        assertThat(a.failureReason()).isEmpty();
        assertThat(a.attemptedAt()).isEqualTo(NOW);
        assertThat(a.sessionDate()).isEqualTo(SESSION);

        // TradeEodFlattened CDI event fired with the broker order id.
        ArgumentCaptor<TradeEodFlattened> evtCap = ArgumentCaptor.forClass(TradeEodFlattened.class);
        verify(flattenedEvent, times(1)).fire(evtCap.capture());
        TradeEodFlattened evt = evtCap.getValue();
        assertThat(evt.tradeId()).isEqualTo("t1");
        assertThat(evt.alpacaOrderId()).isEqualTo("alpaca-order-1");
        assertThat(evt.flattenedAt()).isEqualTo(NOW);
        assertThat(evt.correlationId()).isEqualTo("corr-t1");

        // Cache cleared on success.
        assertThat(cache.size()).isZero();
    }

    @Test
    void runOnceFlattensMultipleTradesIndependently() {
        cache.put(trade("t1", "SPY260430C00595000", 1));
        cache.put(trade("t2", "SPY260430P00590000", 2));
        when(submitterInstance.isUnsatisfied()).thenReturn(false);
        when(submitterInstance.get()).thenReturn(submitter);
        when(submitter.submit(any(OrderRequest.class))).thenAnswer(inv -> {
            OrderRequest req = inv.getArgument(0);
            return new OrderSubmission.Submitted("alpaca-" + req.tradeId(), req.clientOrderId(), "accepted", NOW);
        });

        scheduler.runOnce();

        verify(submitter, times(2)).submit(any(OrderRequest.class));
        verify(audit, times(2)).record(argThat(a -> a.outcome().equals(EodFlattenAttempt.Outcome.FLATTENED)));
        verify(flattenedEvent, times(2)).fire(any(TradeEodFlattened.class));
        assertThat(cache.size()).isZero();
    }

    @Test
    void runOnceRecordsFailedAuditWhenSubmitterThrows() {
        InFlightTrade t = trade("t1", "SPY260430C00595000", 1);
        cache.put(t);
        when(submitterInstance.isUnsatisfied()).thenReturn(false);
        when(submitterInstance.get()).thenReturn(submitter);
        when(submitter.submit(any(OrderRequest.class)))
                .thenThrow(new RuntimeException("alpaca rejected: 422 duplicate client_order_id"));

        // The scheduler must NOT propagate — a single failure must not stop the cron thread.
        scheduler.runOnce();

        ArgumentCaptor<EodFlattenAttempt> auditCap = ArgumentCaptor.forClass(EodFlattenAttempt.class);
        verify(audit, times(1)).record(auditCap.capture());
        EodFlattenAttempt a = auditCap.getValue();
        assertThat(a.outcome()).isEqualTo(EodFlattenAttempt.Outcome.FAILED);
        assertThat(a.alpacaOrderId()).isEmpty();
        assertThat(a.failureReason()).isPresent();
        assertThat(a.failureReason().get()).contains("422 duplicate client_order_id");

        // No success event when the broker throws.
        verifyNoInteractions(flattenedEvent);

        // Cache untouched — operator can retry manually.
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void runOnceContinuesAfterFirstTradeFails() {
        cache.put(trade("t1", "SPY260430C00595000", 1));
        cache.put(trade("t2", "SPY260430P00590000", 2));
        when(submitterInstance.isUnsatisfied()).thenReturn(false);
        when(submitterInstance.get()).thenReturn(submitter);
        when(submitter.submit(any(OrderRequest.class))).thenAnswer(inv -> {
            OrderRequest req = inv.getArgument(0);
            if (req.tradeId().equals("t1")) {
                throw new RuntimeException("alpaca 503 timeout");
            }
            return new OrderSubmission.Submitted("alpaca-" + req.tradeId(), req.clientOrderId(), "accepted", NOW);
        });

        scheduler.runOnce();

        // Both trades got an audit row — one FAILED, one FLATTENED.
        verify(audit, times(2)).record(any(EodFlattenAttempt.class));
        verify(audit, times(1))
                .record(argThat(a -> a.outcome().equals(EodFlattenAttempt.Outcome.FAILED)
                        && a.tradeId().equals("t1")));
        verify(audit, times(1))
                .record(argThat(a -> a.outcome().equals(EodFlattenAttempt.Outcome.FLATTENED)
                        && a.tradeId().equals("t2")));

        // Only one success event fired (for t2).
        verify(flattenedEvent, times(1)).fire(any(TradeEodFlattened.class));

        // t1 still in cache (failed); t2 cleared.
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.snapshot()).extracting(InFlightTrade::tradeId).containsExactly("t1");
    }

    @Test
    void runOnceSwallowsAuditPersistenceFailureOnFlattenedPath() {
        InFlightTrade t = trade("t1", "SPY260430C00595000", 1);
        cache.put(t);
        when(submitterInstance.isUnsatisfied()).thenReturn(false);
        when(submitterInstance.get()).thenReturn(submitter);
        when(submitter.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Submitted("alpaca-1", "eod:OWNER:t1", "accepted", NOW));
        Mockito.doThrow(new RuntimeException("db down")).when(audit).record(any(EodFlattenAttempt.class));

        // Audit failure must not crash the cron handler — the broker side has
        // already accepted the exit, the operator can reconcile via Alpaca's
        // own order log.
        scheduler.runOnce();

        verify(submitter, times(1)).submit(any(OrderRequest.class));
        // Event still fires — broker accepted the exit.
        verify(flattenedEvent, times(1)).fire(any(TradeEodFlattened.class));
    }

    @Test
    void clientOrderIdIsDeterministicAcrossRuns() {
        // Re-fire the cron after a hypothetical JVM restart with the same trade
        // → same client_order_id → Alpaca rejects the second as a duplicate.
        // We assert the saga produces the same key both times.
        InFlightTrade t = trade("t1", "SPY260430C00595000", 1);
        cache.put(t);
        when(submitterInstance.isUnsatisfied()).thenReturn(false);
        when(submitterInstance.get()).thenReturn(submitter);
        when(submitter.submit(any(OrderRequest.class)))
                .thenReturn(new OrderSubmission.Submitted("alpaca-1", "eod:OWNER:t1", "accepted", NOW));

        scheduler.runOnce();

        // Re-seed cache and run again — reuse same trade.
        cache.put(t);
        scheduler.runOnce();

        ArgumentCaptor<OrderRequest> cap = ArgumentCaptor.forClass(OrderRequest.class);
        verify(submitter, times(2)).submit(cap.capture());
        assertThat(cap.getAllValues())
                .extracting(OrderRequest::clientOrderId)
                .containsExactly("eod:OWNER:t1", "eod:OWNER:t1");
    }
}
