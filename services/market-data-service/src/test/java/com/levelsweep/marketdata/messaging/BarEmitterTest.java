package com.levelsweep.marketdata.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
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
 * Verifies the timeframe → channel routing inside {@link BarEmitter}. Uses Mockito-driven
 * mocks for the four {@link MutinyEmitter} dependencies so the test runs without a Kafka
 * broker (and without Quarkus Reactive Messaging wiring).
 *
 * <p>Note on {@code send} ambiguity: {@code MutinyEmitter} declares both {@code send(T)}
 * and {@code <M extends Message<? extends T>> send(M)}; an unparameterized {@code any()}
 * matches both. We disambiguate via {@code ArgumentMatchers.<Record<String, Bar>>any()}
 * so the {@code send(T)} overload is selected (which is the one {@link BarEmitter} calls).
 */
@ExtendWith(MockitoExtension.class)
class BarEmitterTest {

    @Mock
    MutinyEmitter<Record<String, Bar>> oneMin;

    @Mock
    MutinyEmitter<Record<String, Bar>> twoMin;

    @Mock
    MutinyEmitter<Record<String, Bar>> fifteenMin;

    @Mock
    MutinyEmitter<Record<String, Bar>> daily;

    BarEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new BarEmitter(oneMin, twoMin, fifteenMin, daily);
    }

    @SuppressWarnings("unchecked")
    private static Record<String, Bar> anyRecord() {
        return ArgumentMatchers.<Record<String, Bar>>any();
    }

    private static Bar barOf(Timeframe tf) {
        Instant open = Instant.parse("2026-04-30T13:30:00Z");
        return new Bar(
                "SPY",
                tf,
                open,
                open.plus(tf.duration()),
                BigDecimal.valueOf(594.00),
                BigDecimal.valueOf(594.50),
                BigDecimal.valueOf(593.75),
                BigDecimal.valueOf(594.25),
                1_000L,
                10L);
    }

    @Test
    void routesOneMinBarToOneMinChannel() {
        when(oneMin.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());

        emitter.emit(barOf(Timeframe.ONE_MIN));

        verify(oneMin).send(anyRecord());
        verifyNoInteractions(twoMin, fifteenMin, daily);
    }

    @Test
    void routesTwoMinBarToTwoMinChannel() {
        when(twoMin.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());

        emitter.emit(barOf(Timeframe.TWO_MIN));

        verify(twoMin).send(anyRecord());
        verifyNoInteractions(oneMin, fifteenMin, daily);
    }

    @Test
    void routesFifteenMinBarToFifteenMinChannel() {
        when(fifteenMin.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());

        emitter.emit(barOf(Timeframe.FIFTEEN_MIN));

        verify(fifteenMin).send(anyRecord());
        verifyNoInteractions(oneMin, twoMin, daily);
    }

    @Test
    void routesDailyBarToDailyChannel() {
        when(daily.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());

        emitter.emit(barOf(Timeframe.DAILY));

        verify(daily).send(anyRecord());
        verifyNoInteractions(oneMin, twoMin, fifteenMin);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordKeyIsSymbolAndValueIsBar() {
        when(oneMin.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());
        Bar bar = barOf(Timeframe.ONE_MIN);

        emitter.emit(bar);

        ArgumentCaptor<Record<String, Bar>> captor = ArgumentCaptor.forClass(Record.class);
        verify(oneMin).send(captor.capture());
        Record<String, Bar> sent = captor.getValue();
        assertThat(sent.key()).isEqualTo("SPY");
        assertThat(sent.value()).isSameAs(bar);
    }

    @Test
    void emitDoesNotPropagateChannelFailure() {
        // send() returns a failed Uni; emitter must subscribe with a log-only failure
        // handler and not throw to the calling drainer thread.
        when(oneMin.send(anyRecord())).thenReturn(Uni.createFrom().failure(new RuntimeException("broker down")));

        // No exception expected — fire-and-forget pattern.
        emitter.emit(barOf(Timeframe.ONE_MIN));

        verify(oneMin).send(anyRecord());
    }
}
