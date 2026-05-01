package com.levelsweep.decision.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeProposed;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.Record;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the {@link TradeProposedKafkaPublisher} CDI-event observer correctly
 * relays a {@link TradeProposed} to the outgoing Kafka channel keyed by tenant
 * id. Mirrors {@code BarEmitterTest} from market-data-service: Mockito-driven
 * mock for the {@link MutinyEmitter} so the test runs without a Kafka broker
 * (and without Quarkus Reactive Messaging wiring).
 *
 * <p>Note on {@code send} ambiguity: {@code MutinyEmitter} declares both
 * {@code send(T)} and {@code <M extends Message<? extends T>> send(M)}; an
 * unparameterized {@code any()} matches both. We disambiguate via
 * {@code ArgumentMatchers.<Record<String, TradeProposed>>any()} so the
 * {@code send(T)} overload is selected (which is the one
 * {@link TradeProposedKafkaPublisher} calls).
 */
@ExtendWith(MockitoExtension.class)
class TradeProposedKafkaPublisherTest {

    @Mock
    MutinyEmitter<Record<String, TradeProposed>> emitter;

    TradeProposedKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TradeProposedKafkaPublisher(emitter);
    }

    @SuppressWarnings("unchecked")
    private static Record<String, TradeProposed> anyRecord() {
        return ArgumentMatchers.<Record<String, TradeProposed>>any();
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
                "corr-abc",
                List.of("pdh_sweep", "vwap_above"));
    }

    @Test
    void publishesEventToKafkaKeyedByTenantId() {
        when(emitter.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());
        TradeProposed event = eventOf("OWNER", "trade-1");

        publisher.onTradeProposed(event);

        verify(emitter).send(anyRecord());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordKeyIsTenantIdAndValueIsEvent() {
        when(emitter.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());
        TradeProposed event = eventOf("ACME", "trade-42");

        publisher.onTradeProposed(event);

        ArgumentCaptor<Record<String, TradeProposed>> captor = ArgumentCaptor.forClass(Record.class);
        verify(emitter).send(captor.capture());
        Record<String, TradeProposed> sent = captor.getValue();
        assertThat(sent.key()).isEqualTo("ACME");
        assertThat(sent.value()).isSameAs(event);
    }

    @Test
    void publishDoesNotPropagateChannelFailure() {
        // send() returns a failed Uni; publisher must subscribe with a log-only failure
        // handler and not throw to the calling CDI dispatcher thread (which is the
        // saga's Kafka bar-consumer thread).
        when(emitter.send(anyRecord())).thenReturn(Uni.createFrom().failure(new RuntimeException("broker down")));

        // No exception expected — fire-and-forget pattern.
        publisher.onTradeProposed(eventOf("OWNER", "trade-x"));

        verify(emitter).send(anyRecord());
    }
}
