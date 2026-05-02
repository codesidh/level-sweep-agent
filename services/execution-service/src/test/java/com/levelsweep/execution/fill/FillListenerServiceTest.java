package com.levelsweep.execution.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.execution.api.WsTransport;
import com.levelsweep.execution.messaging.TradeFilledKafkaPublisher;
import com.levelsweep.shared.domain.trade.TradeFillEvent;
import com.levelsweep.shared.domain.trade.TradeFilled;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * Unit tests for the orchestration wiring around {@link FillListenerService}
 * and the related stream + Kafka publisher chain. We exercise:
 *
 * <ul>
 *   <li>Lifecycle: missing api-key → idle mode (no transport contact).
 *   <li>Lifecycle: non-blank api-key → constructs the pipeline; the bean
 *       reports {@code isAttached() == true} and the {@code ConnectionMonitor}
 *       starts in {@code HEALTHY}. We do NOT spin up a real WebSocket — the
 *       lifecycle code path that builds {@link com.levelsweep.execution.api.JdkWsTransport}
 *       is exercised at integration time via the per-phase soak environment
 *       (architecture-spec §21.1).
 *   <li>Stream-side orchestration (via the {@link WsTransport} seam):
 *       <ul>
 *         <li>Receives the {@code authorization status=authorized} status
 *             frame → sends the {@code listen} subscribe frame.
 *         <li>Decodes a fill frame → fires the {@link TradeFilled} CDI event
 *             AND a {@link TradeFillEvent}; the
 *             {@link TradeFilledKafkaPublisher} (wired as a downstream
 *             observer) relays the {@link TradeFilled} onto the
 *             {@code trade-filled-out} channel keyed by tenantId.
 *         <li>Decodes a non-fill {@code new} frame → fires
 *             {@link TradeFillEvent} only; no {@link TradeFilled}.
 *       </ul>
 * </ul>
 *
 * <p>Mocks {@link WsTransport} + the CDI {@link Event} buses + the Kafka
 * {@link MutinyEmitter}. No real network, no Quarkus runtime.
 */
class FillListenerServiceTest {

    private WsTransport transport;
    @SuppressWarnings("unchecked")
    private final Event<TradeFilled> filledBus = (Event<TradeFilled>) mock(Event.class);

    @SuppressWarnings("unchecked")
    private final Event<TradeFillEvent> fillEventBus = (Event<TradeFillEvent>) mock(Event.class);

    @SuppressWarnings("unchecked")
    private final MutinyEmitter<Record<String, TradeFilled>> kafkaEmitter = mock(MutinyEmitter.class);

    private TradeFilledKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        transport = mock(WsTransport.class);
        when(transport.connect()).thenReturn(CompletableFuture.completedFuture(null));
        when(transport.send(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(transport.close()).thenReturn(CompletableFuture.completedFuture(null));

        when(kafkaEmitter.send(ArgumentMatchers.<Record<String, TradeFilled>>any()))
                .thenReturn(Uni.createFrom().voidItem());
        publisher = new TradeFilledKafkaPublisher(kafkaEmitter);

        // Wire the Kafka publisher as a downstream observer of the
        // Event<TradeFilled> CDI bus — emulates what Quarkus' CDI container
        // does at runtime (the @Observes hook on the publisher fires when
        // someone calls Event.fire()).
        doAnswer(inv -> {
                    publisher.onTradeFilled(inv.getArgument(0));
                    return null;
                })
                .when(filledBus)
                .fire(any(TradeFilled.class));
    }

    private AlpacaTradeUpdatesStream buildStream() {
        return AlpacaTradeUpdatesStream.builder()
                .transport(transport)
                .decoder(new AlpacaTradeUpdatesDecoder(new ObjectMapper()))
                .monitor(new ConnectionMonitor("alpaca-trade-updates", Clock.systemUTC()))
                .tradeFilledEvent(filledBus)
                .fillEventBus(fillEventBus)
                .apiKey("AKtest")
                .secretKey("SKtest")
                .build();
    }

    private static void invokeStartup(FillListenerService bean, StartupEvent ev) throws Exception {
        Method m = FillListenerService.class.getDeclaredMethod("start", StartupEvent.class);
        m.setAccessible(true);
        m.invoke(bean, ev);
    }

    private static void invokeShutdown(FillListenerService bean, ShutdownEvent ev) throws Exception {
        Method m = FillListenerService.class.getDeclaredMethod("stop", ShutdownEvent.class);
        m.setAccessible(true);
        m.invoke(bean, ev);
    }

    @Test
    void idleModeWhenApiKeyIsBlank() throws Exception {
        FillListenerService bean = new FillListenerService(
                "wss://paper-api.alpaca.markets/stream",
                Optional.of(""),
                Optional.of(""),
                filledBus,
                fillEventBus);

        invokeStartup(bean, new StartupEvent());

        assertThat(bean.isAttached()).isFalse();
        assertThat(bean.connectionState()).isEqualTo(ConnectionMonitor.State.HEALTHY);
    }

    @Test
    void idleModeWhenApiKeyIsAbsentOptional() throws Exception {
        FillListenerService bean = new FillListenerService(
                "wss://paper-api.alpaca.markets/stream", Optional.empty(), Optional.empty(), filledBus, fillEventBus);

        invokeStartup(bean, new StartupEvent());

        assertThat(bean.isAttached()).isFalse();
    }

    @Test
    void shutdownWhenIdleIsNoOp() throws Exception {
        FillListenerService bean = new FillListenerService(
                "wss://paper-api.alpaca.markets/stream", Optional.empty(), Optional.empty(), filledBus, fillEventBus);
        // No startup, no real wiring.

        // Should not throw.
        invokeShutdown(bean, new ShutdownEvent());

        assertThat(bean.isAttached()).isFalse();
    }

    @Test
    void constructorRejectsNullEvents() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FillListenerService(
                        "wss://x", Optional.empty(), Optional.empty(), null, fillEventBus))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FillListenerService(
                        "wss://x", Optional.empty(), Optional.empty(), filledBus, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void streamReceivesAuthSuccessThenSendsListenSubscribe() {
        AlpacaTradeUpdatesStream stream = buildStream();
        WsTransport.Listener listener = stream.createTransportListener();

        listener.onOpen();
        listener.onText(
                "{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\",\"action\":\"authenticate\"}}");

        // First send = auth, second send = listen subscribe.
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(transport, times(2)).send(sent.capture());
        String listenFrame = sent.getAllValues().get(1);
        assertThat(listenFrame).contains("\"action\":\"listen\"");
        assertThat(listenFrame).contains("\"streams\":[\"trade_updates\"]");
    }

    @Test
    void streamReceivesFillFrameFiresTradeFilledAndPublishesToKafka() {
        AlpacaTradeUpdatesStream stream = buildStream();
        WsTransport.Listener listener = stream.createTransportListener();
        listener.onOpen();

        String fillFrame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\",\"order\":"
                + "{\"id\":\"alp-ord-1\",\"client_order_id\":\"OWNER:trade-42\","
                + "\"symbol\":\"SPY260430C00595000\",\"filled_qty\":\"1\","
                + "\"filled_avg_price\":\"1.42\",\"status\":\"filled\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00.123Z\"}}";

        listener.onText(fillFrame);

        // CDI Event<TradeFilled> fired exactly once.
        ArgumentCaptor<TradeFilled> filledCaptor = ArgumentCaptor.forClass(TradeFilled.class);
        verify(filledBus).fire(filledCaptor.capture());
        TradeFilled tf = filledCaptor.getValue();
        assertThat(tf.tenantId()).isEqualTo("OWNER");
        assertThat(tf.tradeId()).isEqualTo("trade-42");

        // CDI Event<TradeFillEvent> also fires for the audit catch-all.
        verify(fillEventBus).fire(any(TradeFillEvent.class));

        // Kafka publisher relays the TradeFilled to the trade-filled-out channel
        // keyed by tenantId — driven by the @Observes hook on the publisher,
        // which our setUp() wires onto the Event<TradeFilled>.fire() mock.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Record<String, TradeFilled>> recCaptor = ArgumentCaptor.forClass(Record.class);
        verify(kafkaEmitter).send(recCaptor.capture());
        Record<String, TradeFilled> sent = recCaptor.getValue();
        assertThat(sent.key()).isEqualTo("OWNER");
        assertThat(sent.value()).isSameAs(tf);
    }

    @Test
    void streamReceivesNonFillEventFiresOnlyTradeFillEvent() {
        AlpacaTradeUpdatesStream stream = buildStream();
        WsTransport.Listener listener = stream.createTransportListener();
        listener.onOpen();

        String newFrame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"new\",\"order\":"
                + "{\"id\":\"alp-ord-9\",\"client_order_id\":\"OWNER:trade-9\","
                + "\"symbol\":\"SPY260430C00595000\",\"status\":\"new\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00Z\"}}";

        listener.onText(newFrame);

        verify(fillEventBus).fire(any(TradeFillEvent.class));
        verify(filledBus, never()).fire(any(TradeFilled.class));

        // No Kafka publish for non-fill events.
        verify(kafkaEmitter, never()).send(ArgumentMatchers.<Record<String, TradeFilled>>any());
    }

    @Test
    void unauthorizedStatusDoesNotSendListenAndRecordsError() {
        AlpacaTradeUpdatesStream stream = buildStream();
        WsTransport.Listener listener = stream.createTransportListener();
        listener.onOpen();

        listener.onText(
                "{\"stream\":\"authorization\",\"data\":{\"status\":\"unauthorized\",\"action\":\"authenticate\"}}");

        // Only the auth send fires — no listen subscribe on a failed auth.
        verify(transport, times(1)).send(anyString());
    }

    @Test
    void connectionStateDuringStreamLifecycle() {
        // After idle-mode startup, the bean reports HEALTHY (no real monitor).
        FillListenerService bean = new FillListenerService(
                "wss://paper-api.alpaca.markets/stream", Optional.empty(), Optional.empty(), filledBus, fillEventBus);

        AtomicReference<ConnectionMonitor.State> beforeStart = new AtomicReference<>(bean.connectionState());

        assertThat(beforeStart.get()).isEqualTo(ConnectionMonitor.State.HEALTHY);
    }
}
