package com.levelsweep.execution.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.execution.api.WsTransport;
import com.levelsweep.shared.domain.trade.TradeFillEvent;
import com.levelsweep.shared.domain.trade.TradeFilled;
import jakarta.enterprise.event.Event;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link AlpacaTradeUpdatesStream}. Verifies the orchestration
 * contract:
 *
 * <ul>
 *   <li>{@code start()} delegates to the {@link WsTransport#connect()}.
 *   <li>{@code stop()} delegates to {@link WsTransport#close()}.
 *   <li>On socket open, the auth body is sent verbatim with the configured
 *       key + secret embedded.
 *   <li>On {@code authorization status=authorized}, the listen frame is sent.
 *   <li>Inbound text frames flow through the decoder into the CDI {@link Event}
 *       buses ({@link TradeFilled} and {@link TradeFillEvent}).
 *   <li>Credentials never leak into any log line emitted during the handshake.
 * </ul>
 *
 * <p>Mocks {@link WsTransport} — no real WebSocket. Uses fake credentials
 * ({@code AKtest} / {@code SKtest}); the test asserts those literal strings
 * never appear in any captured log message.
 */
class AlpacaTradeUpdatesStreamTest {

    /**
     * Captures all log records routed through {@code java.util.logging}.
     * Quarkus + JBoss Logging route SLF4J through JUL, so a JUL Handler on the
     * root logger captures every log event regardless of which API the
     * production code uses.
     */
    static final class CapturingHandler extends Handler {
        final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            String msg = record.getMessage();
            messages.add(msg == null ? "" : msg);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    private WsTransport transport;
    private CapturingHandler handler;
    private java.util.logging.Logger julRoot;

    @BeforeEach
    void setUp() {
        transport = mock(WsTransport.class);
        when(transport.connect()).thenReturn(CompletableFuture.completedFuture(null));
        when(transport.send(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(transport.close()).thenReturn(CompletableFuture.completedFuture(null));

        handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        julRoot = java.util.logging.Logger.getLogger("");
        julRoot.addHandler(handler);
        julRoot.setLevel(Level.ALL);
    }

    @SuppressWarnings("unchecked")
    private static <T> Event<T> mockEvent() {
        return (Event<T>) mock(Event.class);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        julRoot.removeHandler(handler);
    }

    private AlpacaTradeUpdatesStream buildStream(Event<TradeFilled> filled, Event<TradeFillEvent> fillEvents) {
        return AlpacaTradeUpdatesStream.builder()
                .transport(transport)
                .decoder(new AlpacaTradeUpdatesDecoder(new ObjectMapper()))
                .monitor(new ConnectionMonitor("alpaca-trade-updates", Clock.systemUTC()))
                .tradeFilledEvent(filled)
                .fillEventBus(fillEvents)
                .apiKey("AKtest")
                .secretKey("SKtest")
                .build();
    }

    @Test
    void startDelegatesToTransportConnect() {
        AlpacaTradeUpdatesStream s = buildStream(mockEvent(), mockEvent());

        s.start();

        verify(transport).connect();
    }

    @Test
    void stopDelegatesToTransportClose() {
        AlpacaTradeUpdatesStream s = buildStream(mockEvent(), mockEvent());

        s.stop();

        verify(transport).close();
    }

    @Test
    void onOpenSendsAuthFrameWithCredentials() {
        AlpacaTradeUpdatesStream s = buildStream(mockEvent(), mockEvent());
        WsTransport.Listener listener = s.createTransportListener();

        listener.onOpen();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(transport).send(sent.capture());
        String authFrame = sent.getValue();
        // The trade-updates auth frame uses the "authenticate" action with key_id/secret_key
        // (per architecture-spec §3.6 — different shape from the stocks-data WS).
        assertThat(authFrame).contains("\"action\":\"authenticate\"");
        assertThat(authFrame).contains("\"key_id\":\"AKtest\"");
        assertThat(authFrame).contains("\"secret_key\":\"SKtest\"");
    }

    @Test
    void onOpenSendsAuthOnlyOnce() {
        AlpacaTradeUpdatesStream s = buildStream(mockEvent(), mockEvent());
        WsTransport.Listener listener = s.createTransportListener();

        listener.onOpen();
        listener.onOpen();
        listener.onOpen();

        // Auth is sent exactly once even on duplicate open (e.g. JDK WS retry).
        verify(transport, times(1)).send(anyString());
    }

    @Test
    void authorizedStatusTriggersListenFrame() {
        AlpacaTradeUpdatesStream s = buildStream(mockEvent(), mockEvent());
        WsTransport.Listener listener = s.createTransportListener();

        listener.onOpen();
        // Simulate Alpaca returning the authorization-success frame.
        String authResp =
                "{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\",\"action\":\"authenticate\"}}";
        listener.onText(authResp);

        // First send = auth frame; second send = listen frame.
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(transport, times(2)).send(sent.capture());
        String listenFrame = sent.getAllValues().get(1);
        assertThat(listenFrame).contains("\"action\":\"listen\"");
        assertThat(listenFrame).contains("\"streams\":[\"trade_updates\"]");
    }

    @Test
    void inboundFillFrameFiresTradeFilledAndTradeFillEvent() {
        Event<TradeFilled> filledBus = mockEvent();
        Event<TradeFillEvent> fillEventBus = mockEvent();
        AlpacaTradeUpdatesStream s = buildStream(filledBus, fillEventBus);

        WsTransport.Listener listener = s.createTransportListener();
        listener.onOpen();

        String fillFrame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\",\"order\":"
                + "{\"id\":\"alp-ord-1\",\"client_order_id\":\"OWNER:trade-42\","
                + "\"symbol\":\"SPY260430C00595000\",\"filled_qty\":\"1\","
                + "\"filled_avg_price\":\"1.42\",\"status\":\"filled\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00.123Z\"}}";

        listener.onText(fillFrame);

        ArgumentCaptor<TradeFilled> filledCaptor = ArgumentCaptor.forClass(TradeFilled.class);
        verify(filledBus).fire(filledCaptor.capture());
        TradeFilled tf = filledCaptor.getValue();
        assertThat(tf.tenantId()).isEqualTo("OWNER");
        assertThat(tf.tradeId()).isEqualTo("trade-42");
        assertThat(tf.alpacaOrderId()).isEqualTo("alp-ord-1");
        assertThat(tf.status()).isEqualTo("filled");

        verify(fillEventBus).fire(org.mockito.ArgumentMatchers.any(TradeFillEvent.class));
    }

    @Test
    void inboundNonFillFrameFiresOnlyTradeFillEvent() {
        Event<TradeFilled> filledBus = mockEvent();
        Event<TradeFillEvent> fillEventBus = mockEvent();
        AlpacaTradeUpdatesStream s = buildStream(filledBus, fillEventBus);

        WsTransport.Listener listener = s.createTransportListener();
        listener.onOpen();

        String newFrame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"new\",\"order\":"
                + "{\"id\":\"alp-ord-9\",\"client_order_id\":\"OWNER:trade-9\","
                + "\"symbol\":\"SPY260430C00595000\",\"status\":\"new\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00Z\"}}";

        listener.onText(newFrame);

        verify(fillEventBus).fire(org.mockito.ArgumentMatchers.any(TradeFillEvent.class));
        verify(filledBus, times(0)).fire(org.mockito.ArgumentMatchers.any(TradeFilled.class));
    }

    @Test
    void credentialsNeverAppearInLogOutput() {
        AlpacaTradeUpdatesStream s = buildStream(mockEvent(), mockEvent());
        WsTransport.Listener listener = s.createTransportListener();

        // Drive every code path that logs.
        listener.onOpen();
        listener.onText("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");
        listener.onText("{\"stream\":\"listening\",\"data\":{\"streams\":[\"trade_updates\"]}}");
        listener.onError(new RuntimeException("simulated"));
        listener.onClose(1000, "normal closure");

        // No log message captured during the handshake should contain either
        // the api-key or the secret-key — the auth frame must travel through
        // the transport seam only, never through the logger.
        for (String msg : handler.messages) {
            assertThat(msg).doesNotContain("AKtest");
            assertThat(msg).doesNotContain("SKtest");
        }
    }

    @Test
    void connectionStateExposesMonitorState() {
        AlpacaTradeUpdatesStream s = buildStream(mockEvent(), mockEvent());

        assertThat(s.connectionState()).isEqualTo(ConnectionMonitor.State.HEALTHY);
        assertThat(s.monitor()).isNotNull();
    }

    @Test
    void onErrorRecordsErrorOnMonitor() {
        AlpacaTradeUpdatesStream s = buildStream(mockEvent(), mockEvent());
        WsTransport.Listener listener = s.createTransportListener();

        // 3 errors in a row → DEGRADED.
        for (int i = 0; i < 3; i++) {
            listener.onError(new RuntimeException("err-" + i));
        }

        assertThat(s.connectionState()).isEqualTo(ConnectionMonitor.State.DEGRADED);
    }
}
