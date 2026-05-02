package com.levelsweep.execution.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.shared.domain.trade.TradeFilled;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.Record;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the {@link TradeFilledKafkaPublisher} CDI-event observer correctly
 * relays a {@link TradeFilled} to the {@code trade-filled-out} channel keyed
 * by tenant id (architecture-spec §12.1 — {@code tenant.fills} is keyed by
 * {@code tenant_id}). Mirrors decision-engine's {@code
 * TradeProposedKafkaPublisherTest} pattern.
 *
 * <p>Note on {@code send} ambiguity: {@code MutinyEmitter} declares both
 * {@code send(T)} and {@code <M extends Message<? extends T>> send(M)}; an
 * unparameterized {@code any()} matches both. We disambiguate via a typed
 * helper so the {@code send(T)} overload is selected (the one
 * {@link TradeFilledKafkaPublisher} calls).
 */
@ExtendWith(MockitoExtension.class)
class TradeFilledKafkaPublisherTest {

    @Mock
    MutinyEmitter<Record<String, TradeFilled>> emitter;

    TradeFilledKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TradeFilledKafkaPublisher(emitter);
    }

    @SuppressWarnings("unchecked")
    private static Record<String, TradeFilled> anyRecord() {
        return ArgumentMatchers.<Record<String, TradeFilled>>any();
    }

    private static TradeFilled fillOf(String tenantId, String tradeId) {
        return new TradeFilled(
                tenantId,
                tradeId,
                "alp-ord-" + tradeId,
                "SPY260430C00595000",
                new BigDecimal("1.42"),
                1,
                "filled",
                Instant.parse("2026-04-30T13:30:00.123Z"),
                "corr-" + tradeId);
    }

    @Test
    void publishesEventToKafkaKeyedByTenantId() {
        when(emitter.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());
        TradeFilled event = fillOf("OWNER", "trade-1");

        publisher.onTradeFilled(event);

        verify(emitter).send(anyRecord());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordKeyIsTenantIdAndValueIsEvent() {
        when(emitter.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());
        TradeFilled event = fillOf("ACME", "trade-42");

        publisher.onTradeFilled(event);

        ArgumentCaptor<Record<String, TradeFilled>> captor = ArgumentCaptor.forClass(Record.class);
        verify(emitter).send(captor.capture());
        Record<String, TradeFilled> sent = captor.getValue();
        assertThat(sent.key()).isEqualTo("ACME");
        assertThat(sent.value()).isSameAs(event);
    }

    @Test
    void publishDoesNotPropagateChannelFailure() {
        // send() returns a failed Uni; publisher must subscribe with a log-only
        // failure handler and not throw to the calling CDI dispatcher thread
        // (which is the WS callback thread). Fire-and-forget per the publisher's
        // emit pattern.
        when(emitter.send(anyRecord())).thenReturn(Uni.createFrom().failure(new RuntimeException("broker down")));

        // No exception expected.
        publisher.onTradeFilled(fillOf("OWNER", "trade-x"));

        verify(emitter).send(anyRecord());
    }

    @Test
    void constructorRejectsNullEmitter() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TradeFilledKafkaPublisher(null))
                .isInstanceOf(NullPointerException.class);
    }
}
