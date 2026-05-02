package com.levelsweep.marketdata.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
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
 * Verifies the symbol-keyed publish path of {@link IndicatorSnapshotEmitter}. Mirrors
 * {@link BarEmitterTest} — Mockito-driven mock for the {@link MutinyEmitter} so the
 * test runs without a Kafka broker (and without Quarkus Reactive Messaging wiring).
 *
 * <p>Note on {@code send} ambiguity: {@code MutinyEmitter} declares both {@code send(T)}
 * and {@code <M extends Message<? extends T>> send(M)}; an unparameterized {@code any()}
 * matches both. We disambiguate via {@code ArgumentMatchers.<Record<String, IndicatorSnapshot>>any()}
 * so the {@code send(T)} overload is selected (which is the one
 * {@link IndicatorSnapshotEmitter} calls).
 */
@ExtendWith(MockitoExtension.class)
class IndicatorSnapshotEmitterTest {

    private static final Instant TS = Instant.parse("2026-04-30T13:32:00Z");

    @Mock
    MutinyEmitter<Record<String, IndicatorSnapshot>> indicators2m;

    IndicatorSnapshotEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new IndicatorSnapshotEmitter(indicators2m);
    }

    @SuppressWarnings("unchecked")
    private static Record<String, IndicatorSnapshot> anyRecord() {
        return ArgumentMatchers.<Record<String, IndicatorSnapshot>>any();
    }

    private static IndicatorSnapshot snapOf(String symbol) {
        return new IndicatorSnapshot(
                symbol,
                TS,
                BigDecimal.valueOf(594.10),
                BigDecimal.valueOf(594.05),
                BigDecimal.valueOf(593.20),
                BigDecimal.valueOf(1.45));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordKeyIsSymbolAndValueIsSnapshot() {
        when(indicators2m.send(anyRecord())).thenReturn(Uni.createFrom().voidItem());
        IndicatorSnapshot snap = snapOf("SPY");

        emitter.emit(snap);

        ArgumentCaptor<Record<String, IndicatorSnapshot>> captor = ArgumentCaptor.forClass(Record.class);
        verify(indicators2m).send(captor.capture());
        Record<String, IndicatorSnapshot> sent = captor.getValue();
        assertThat(sent.key()).isEqualTo("SPY");
        assertThat(sent.value()).isSameAs(snap);
    }

    @Test
    void emitDoesNotPropagateChannelFailure() {
        // send() returns a failed Uni; emitter must subscribe with a log-only failure
        // handler and not throw to the calling indicator-engine thread (otherwise a
        // broker hiccup would corrupt the IndicatorEngine's bar-fanout loop).
        when(indicators2m.send(anyRecord())).thenReturn(Uni.createFrom().failure(new RuntimeException("broker down")));

        // No exception expected — fire-and-forget pattern.
        emitter.emit(snapOf("SPY"));

        verify(indicators2m).send(anyRecord());
    }

    @Test
    void rejectsNullSnapshot() {
        // Null guard prevents a poison record from corrupting the channel; matches
        // BarEmitter#emit(Bar)'s contract.
        assertThatNullPointerException().isThrownBy(() -> emitter.emit(null)).withMessage("snapshot");
    }
}
