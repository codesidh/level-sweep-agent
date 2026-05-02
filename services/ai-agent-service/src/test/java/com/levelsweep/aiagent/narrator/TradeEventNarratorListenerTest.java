package com.levelsweep.aiagent.narrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.shared.domain.trade.TradeEodFlattened;
import com.levelsweep.shared.domain.trade.TradeFilled;
import com.levelsweep.shared.domain.trade.TradeOrderRejected;
import com.levelsweep.shared.domain.trade.TradeOrderSubmitted;
import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link TradeEventNarratorListener}. Covers:
 *
 * <ul>
 *   <li>Each of the 6 listener methods builds the correct {@link NarrationRequest}
 *       and forwards to the narrator.</li>
 *   <li>When the narrator produces a narrative, the repository is asked to save
 *       it with the correct event type tag.</li>
 *   <li>When the narrator returns Optional.empty (cost cap, transport failure,
 *       etc.) the repository is NOT called.</li>
 *   <li>Failure isolation: narrator throwing an exception → listener swallows
 *       (does not propagate to caller); same for repository.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TradeEventNarratorListenerTest {

    private static final String TENANT = "OWNER";
    private static final String TRADE_ID = "TR_2026-05-02_001";
    private static final String CORRELATION_ID = "corr_" + TRADE_ID;
    private static final Instant T = Instant.parse("2026-05-02T13:32:30Z");

    @Mock
    private TradeNarrator narrator;

    @Mock
    private TradeNarrativeRepository repository;

    private TradeEventNarratorListener listener;

    @BeforeEach
    void setUp() {
        listener = new TradeEventNarratorListener(narrator, repository);
    }

    @Test
    void onTradeFilledKafkaInvokesNarratorAndPersists() {
        TradeNarrative narrative = sampleNarrative("Entry filled at $1.42.");
        when(narrator.narrate(any(NarrationRequest.class))).thenReturn(Optional.of(narrative));

        listener.onTradeFilledKafka(new TradeFilled(
                TENANT,
                TRADE_ID,
                "AO_42",
                "SPY250502C00500000",
                new BigDecimal("1.42"),
                2,
                "filled",
                T,
                CORRELATION_ID));

        ArgumentCaptor<NarrationRequest> captor = ArgumentCaptor.forClass(NarrationRequest.class);
        verify(narrator, times(1)).narrate(captor.capture());
        NarrationRequest req = captor.getValue();
        assertThat(req.tenantId()).isEqualTo(TENANT);
        assertThat(req.tradeId()).isEqualTo(TRADE_ID);
        assertThat(req.eventType()).isEqualTo(NarrationPromptBuilder.EVENT_FILL);
        assertThat(req.eventPayload()).contains("contract=SPY250502C00500000");
        assertThat(req.eventPayload()).contains("filledAvgPrice=1.42");
        assertThat(req.eventPayload()).contains("filledQty=2");
        assertThat(req.occurredAt()).isEqualTo(T);

        verify(repository, times(1)).save(eq(narrative), eq(NarrationPromptBuilder.EVENT_FILL));
    }

    @Test
    void onTradeOrderSubmittedFiresOrderSubmittedTemplate() {
        when(narrator.narrate(any(NarrationRequest.class))).thenReturn(Optional.of(sampleNarrative("Order accepted.")));

        listener.onTradeOrderSubmitted(new TradeOrderSubmitted(
                TENANT,
                TRADE_ID,
                CORRELATION_ID,
                "SPY250502C00500000",
                2,
                "AO_42",
                "OWNER:" + TRADE_ID,
                "accepted",
                T));

        ArgumentCaptor<NarrationRequest> captor = ArgumentCaptor.forClass(NarrationRequest.class);
        verify(narrator).narrate(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(NarrationPromptBuilder.EVENT_ORDER_SUBMITTED);
        assertThat(captor.getValue().eventPayload()).contains("contract=SPY250502C00500000");
        assertThat(captor.getValue().eventPayload()).contains("quantity=2");

        verify(repository).save(any(), eq(NarrationPromptBuilder.EVENT_ORDER_SUBMITTED));
    }

    @Test
    void onTradeOrderRejectedFiresRejectedTemplate() {
        when(narrator.narrate(any(NarrationRequest.class))).thenReturn(Optional.of(sampleNarrative("Order rejected.")));

        listener.onTradeOrderRejected(new TradeOrderRejected(
                TENANT,
                TRADE_ID,
                CORRELATION_ID,
                "SPY250502C00500000",
                "OWNER:" + TRADE_ID,
                400,
                "insufficient_buying_power",
                T));

        ArgumentCaptor<NarrationRequest> captor = ArgumentCaptor.forClass(NarrationRequest.class);
        verify(narrator).narrate(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(NarrationPromptBuilder.EVENT_REJECTED);
        assertThat(captor.getValue().eventPayload()).contains("httpStatus=400");
        assertThat(captor.getValue().eventPayload()).contains("reason=insufficient_buying_power");

        verify(repository).save(any(), eq(NarrationPromptBuilder.EVENT_REJECTED));
    }

    @Test
    void onTradeStopTriggeredFiresStopTemplate() {
        when(narrator.narrate(any(NarrationRequest.class))).thenReturn(Optional.of(sampleNarrative("Stop fired.")));

        listener.onTradeStopTriggered(new TradeStopTriggered(
                TENANT,
                TRADE_ID,
                "AO_42",
                "SPY250502C00500000",
                T,
                new BigDecimal("0.95"),
                TradeStopTriggered.STOP_REF_EMA13,
                T,
                CORRELATION_ID));

        ArgumentCaptor<NarrationRequest> captor = ArgumentCaptor.forClass(NarrationRequest.class);
        verify(narrator).narrate(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(NarrationPromptBuilder.EVENT_STOP);
        assertThat(captor.getValue().eventPayload()).contains("stopReference=EMA13");
        assertThat(captor.getValue().eventPayload()).contains("barClose=0.95");

        verify(repository).save(any(), eq(NarrationPromptBuilder.EVENT_STOP));
    }

    @Test
    void onTradeTrailBreachedFiresTrailTemplate() {
        when(narrator.narrate(any(NarrationRequest.class))).thenReturn(Optional.of(sampleNarrative("Trail breached.")));

        listener.onTradeTrailBreached(new TradeTrailBreached(
                TENANT,
                TRADE_ID,
                "SPY250502C00500000",
                T,
                new BigDecimal("1.85"),
                new BigDecimal("0.35"),
                CORRELATION_ID));

        ArgumentCaptor<NarrationRequest> captor = ArgumentCaptor.forClass(NarrationRequest.class);
        verify(narrator).narrate(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(NarrationPromptBuilder.EVENT_TRAIL_BREACH);
        assertThat(captor.getValue().eventPayload()).contains("nbboMid=1.85");
        assertThat(captor.getValue().eventPayload()).contains("exitFloorPct=0.35");

        verify(repository).save(any(), eq(NarrationPromptBuilder.EVENT_TRAIL_BREACH));
    }

    @Test
    void onTradeEodFlattenedFiresEodTemplate() {
        when(narrator.narrate(any(NarrationRequest.class))).thenReturn(Optional.of(sampleNarrative("EOD flatten.")));

        listener.onTradeEodFlattened(new TradeEodFlattened(TENANT, TRADE_ID, "AO_42", T, CORRELATION_ID));

        ArgumentCaptor<NarrationRequest> captor = ArgumentCaptor.forClass(NarrationRequest.class);
        verify(narrator).narrate(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(NarrationPromptBuilder.EVENT_EOD_FLATTEN);
        assertThat(captor.getValue().eventPayload()).contains("alpacaOrderId=AO_42");

        verify(repository).save(any(), eq(NarrationPromptBuilder.EVENT_EOD_FLATTEN));
    }

    @Test
    void emptyNarrativeIsNotPersisted() {
        when(narrator.narrate(any(NarrationRequest.class))).thenReturn(Optional.empty());

        listener.onTradeFilledKafka(sampleFilled());

        verify(narrator, times(1)).narrate(any());
        verify(repository, never()).save(any(), any());
    }

    @Test
    void narratorThrowingDoesNotPropagateAndDoesNotPersist() {
        when(narrator.narrate(any(NarrationRequest.class))).thenThrow(new RuntimeException("anthropic exploded"));

        // Calling the listener must not throw — narrator failures are advisory.
        listener.onTradeFilledKafka(sampleFilled());

        verify(narrator, times(1)).narrate(any());
        verify(repository, never()).save(any(), any());
    }

    @Test
    void repositoryThrowingDoesNotPropagate() {
        TradeNarrative narrative = sampleNarrative("Filled.");
        when(narrator.narrate(any(NarrationRequest.class))).thenReturn(Optional.of(narrative));
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down"))
                .when(repository)
                .save(any(), any());

        // Must not throw out of the listener — narrator advisory + repository
        // best-effort. CLAUDE.md guardrail #3.
        listener.onTradeFilledKafka(sampleFilled());

        verify(repository, times(1)).save(eq(narrative), eq(NarrationPromptBuilder.EVENT_FILL));
    }

    @Test
    void nullEventDoesNotInvokeNarrator() {
        listener.onTradeFilledKafka(null);
        listener.onTradeOrderSubmitted(null);
        listener.onTradeOrderRejected(null);
        listener.onTradeStopTriggered(null);
        listener.onTradeTrailBreached(null);
        listener.onTradeEodFlattened(null);

        verify(narrator, never()).narrate(any());
        verify(repository, never()).save(any(), any());
    }

    @Test
    void payloadMappersAreDeterministic() {
        // Same input → byte-identical payload string. The listener's
        // payload-mapper helpers feed the prompt body — replay parity needs
        // them stable.
        TradeFilled f1 = sampleFilled();
        TradeFilled f2 = sampleFilled();
        assertThat(TradeEventNarratorListener.fillPayload(f1)).isEqualTo(TradeEventNarratorListener.fillPayload(f2));
    }

    // ---------- helpers ----------

    private static TradeFilled sampleFilled() {
        return new TradeFilled(
                TENANT,
                TRADE_ID,
                "AO_42",
                "SPY250502C00500000",
                new BigDecimal("1.42"),
                2,
                "filled",
                T,
                CORRELATION_ID);
    }

    private static TradeNarrative sampleNarrative(String text) {
        return new TradeNarrative(TENANT, TRADE_ID, text, T, "claude-sonnet-4-6", "0".repeat(64));
    }
}
