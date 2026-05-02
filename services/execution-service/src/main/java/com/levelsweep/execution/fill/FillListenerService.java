package com.levelsweep.execution.fill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.execution.api.JdkWsTransport;
import com.levelsweep.execution.api.WsTransport;
import com.levelsweep.shared.domain.trade.TradeFillEvent;
import com.levelsweep.shared.domain.trade.TradeFilled;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle bean for the Alpaca trade-updates WebSocket listener.
 *
 * <p>Wiring on {@link StartupEvent}:
 *
 * <ul>
 *   <li>If {@code alpaca.api-key} is non-blank, construct the
 *       {@link AlpacaTradeUpdatesStream} + {@link JdkWsTransport} +
 *       {@link AlpacaTradeUpdatesDecoder} + {@link ConnectionMonitor},
 *       open the WS connection (auth → wait for {@code authorized} → listen).
 *   <li>If blank (the dev/replay default), enter idle mode: the bean still
 *       constructs but skips the WS connect. Mirrors market-data-service's
 *       {@code LivePipeline} idle-mode pattern. Operators can distinguish
 *       "idle" from "connected" via {@link #isAttached()}.
 * </ul>
 *
 * <p>On {@link ShutdownEvent} the WS connection is closed cleanly.
 *
 * <p>Decoded {@link TradeFilled} / {@link TradeFillEvent} flow through CDI's
 * event bus; the {@code TradeFilledKafkaPublisher} relays {@link TradeFilled}
 * onto the {@code tenant.fills} Kafka topic for downstream consumption by
 * decision-engine (drives the per-trade FSM ENTERED → ACTIVE transition).
 *
 * <p>{@link Optional Optional&lt;String&gt;} for api-key/secret-key follows the
 * canonical Quarkus may-be-absent pattern (mirrors AlpacaOptionsClient): a
 * {@code defaultValue = ""} treats the property as missing and crashes the
 * {@code %test} profile boot when the env vars aren't set.
 */
@ApplicationScoped
public class FillListenerService {

    private static final Logger LOG = LoggerFactory.getLogger(FillListenerService.class);
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;

    private final String wsUrl;
    private final String apiKey;
    private final String secretKey;
    private final Event<TradeFilled> tradeFilledEvent;
    private final Event<TradeFillEvent> fillEventBus;

    private volatile AlpacaTradeUpdatesStream stream;

    @Inject
    public FillListenerService(
            @ConfigProperty(name = "alpaca.trade-updates-url", defaultValue = "wss://paper-api.alpaca.markets/stream")
                    String wsUrl,
            @ConfigProperty(name = "alpaca.api-key") Optional<String> apiKey,
            @ConfigProperty(name = "alpaca.secret-key") Optional<String> secretKey,
            Event<TradeFilled> tradeFilledEvent,
            Event<TradeFillEvent> fillEventBus) {
        this.wsUrl = Objects.requireNonNull(wsUrl, "wsUrl");
        this.apiKey = apiKey.orElse("");
        this.secretKey = secretKey.orElse("");
        this.tradeFilledEvent = Objects.requireNonNull(tradeFilledEvent, "tradeFilledEvent");
        this.fillEventBus = Objects.requireNonNull(fillEventBus, "fillEventBus");
    }

    void start(@Observes StartupEvent ev) {
        if (apiKey.isBlank()) {
            LOG.warn("alpaca credentials missing — fill listener idle (no trade-updates WS connect)");
            return;
        }

        LOG.info("FillListenerService starting wsUrl={} apiKeyPresent={}", wsUrl, !apiKey.isBlank());

        ConnectionMonitor monitor = new ConnectionMonitor("alpaca-trade-updates", Clock.systemUTC());
        AlpacaTradeUpdatesDecoder decoder = new AlpacaTradeUpdatesDecoder(new ObjectMapper());

        // Chicken-and-egg: the transport needs a Listener up-front, but the
        // Listener has to be the one returned by createTransportListener() —
        // which exists only after build(). Resolve via an AtomicReference
        // indirection. Same pattern as LivePipeline in market-data-service.
        AtomicReference<WsTransport.Listener> listenerRef = new AtomicReference<>();
        WsTransport transport = new JdkWsTransport(
                URI.create(wsUrl),
                HttpClient.newHttpClient(),
                AlpacaTradeUpdatesStream.defaultConnectTimeout(),
                new WsTransport.Listener() {
                    @Override
                    public void onOpen() {
                        WsTransport.Listener delegate = listenerRef.get();
                        if (delegate != null) {
                            delegate.onOpen();
                        }
                    }

                    @Override
                    public void onText(String frame) {
                        WsTransport.Listener delegate = listenerRef.get();
                        if (delegate != null) {
                            delegate.onText(frame);
                        }
                    }

                    @Override
                    public void onError(Throwable cause) {
                        WsTransport.Listener delegate = listenerRef.get();
                        if (delegate != null) {
                            delegate.onError(cause);
                        }
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        WsTransport.Listener delegate = listenerRef.get();
                        if (delegate != null) {
                            delegate.onClose(code, reason);
                        }
                    }
                });

        AlpacaTradeUpdatesStream s = AlpacaTradeUpdatesStream.builder()
                .transport(transport)
                .decoder(decoder)
                .monitor(monitor)
                .tradeFilledEvent(tradeFilledEvent)
                .fillEventBus(fillEventBus)
                .apiKey(apiKey)
                .secretKey(secretKey)
                .build();
        listenerRef.set(s.createTransportListener());
        this.stream = s;

        // Fire-and-forget connect; ConnectionMonitor surfaces failures.
        s.start();
    }

    void stop(@Observes ShutdownEvent ev) {
        AlpacaTradeUpdatesStream s = stream;
        if (s == null) {
            return;
        }
        LOG.info("FillListenerService stopping");
        try {
            s.stop().toCompletableFuture().get(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("alpaca trade-updates stream stop did not complete cleanly: {}", e.toString());
        }
    }

    /** Whether the WS pipeline is wired (false in idle mode when api key is blank). */
    public boolean isAttached() {
        return stream != null;
    }

    public ConnectionMonitor.State connectionState() {
        AlpacaTradeUpdatesStream s = stream;
        return s == null ? ConnectionMonitor.State.HEALTHY : s.connectionState();
    }
}
