package com.levelsweep.marketdata.live;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.alpaca.AlpacaRestClient;
import com.levelsweep.marketdata.alpaca.AlpacaStream;
import com.levelsweep.marketdata.api.JdkWsTransport;
import com.levelsweep.marketdata.api.TickListener;
import com.levelsweep.marketdata.api.WsTransport;
import com.levelsweep.marketdata.bars.BarAggregator;
import com.levelsweep.marketdata.bars.BarListener;
import com.levelsweep.marketdata.buffer.TickRingBuffer;
import com.levelsweep.marketdata.connection.ConnectionMonitor;
import com.levelsweep.marketdata.indicators.IndicatorEngine;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production analog of {@link com.levelsweep.marketdata.replay.DataLayerPipeline}, driven by
 * a live Alpaca WebSocket feed instead of synthetic ticks.
 *
 * <p>Wiring:
 *
 * <pre>
 *   Alpaca WS  →  AlpacaStream  →  TickRingBuffer
 *                                      ↓
 *                              drainer thread (single-consumer)
 *                                      ↓
 *                                BarAggregator
 *                                      ↓
 *                            BarListener fan-out
 *                                      ↓
 *                              IndicatorEngine
 *
 *   Quotes  →  AlpacaStream  →  quote-counter (placeholder; Phase 3 wires trail manager)
 * </pre>
 *
 * <p>If the Alpaca API key is blank (the dev/replay default) the pipeline still constructs
 * the buffer + aggregator + indicator engine + drainer so the in-process replay path keeps
 * working locally — it just skips the WS connect. Health endpoints (Phase 1 step S2) will
 * report the FSM state as {@code HEALTHY} (the monitor still exists; it just never sees
 * traffic) and operators can distinguish "idle" from "connected" via the connection-state
 * gauge.
 *
 * <p>Determinism: the drainer thread's idle sleep ({@link LockSupport#parkNanos}) is not
 * business logic. All business-clock reads stay in {@link BarAggregator} / {@link IndicatorEngine}
 * via tick timestamps.
 */
@ApplicationScoped
public class LivePipeline {

    private static final Logger LOG = LoggerFactory.getLogger(LivePipeline.class);

    // America/New_York is the canonical bar-alignment zone (Architecture Principle #10).
    static final ZoneId BAR_ZONE = ZoneId.of("America/New_York");
    // Drainer pulls in chunks; chunk size is small enough to keep latency tight under load
    // and large enough to amortize lock acquisition on the buffer.
    private static final int DRAIN_BATCH = 1024;
    // Idle-park duration when the drain returns empty. Coarser than tight-spin, fine
    // enough that bar-boundary latency stays well under 1s.
    private static final long IDLE_PARK_NANOS = 1_000_000L; // 1 ms
    // Shutdown bound for awaiting the drainer thread.
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;
    // EMA pre-warm window. EMA200 needs ~200 2-min bars (~6h40m of RTH); we widen
    // to 14h to cover an overnight gap without requiring the previous trading day.
    static final Duration PREWARM_LOOKBACK = Duration.ofHours(14);
    static final int PREWARM_LIMIT = 200;
    // If pre-warm exceeds this duration, log a warning — typical fetch is well under 1s.
    private static final long PREWARM_SLOW_MILLIS = 5_000L;

    private final AlpacaConfig cfg;
    private final TickRingBuffer buffer;
    private final ConnectionMonitor connectionMonitor;
    private final BarAggregator barAggregator;
    private final IndicatorEngine indicatorEngine;
    private final AlpacaRestClient restClient;
    private final Clock clock;
    private final AtomicLong quoteCount = new AtomicLong();
    // Additional bar listeners registered post-construction (e.g. persistence wiring).
    // Copy-on-write so the bar fan-out lambda iterates a stable snapshot per call.
    private final CopyOnWriteArrayList<BarListener> additionalBarListeners = new CopyOnWriteArrayList<>();

    private volatile AlpacaStream stream;
    private volatile Thread drainer;
    private volatile boolean shutdown;

    @Inject
    public LivePipeline(AlpacaConfig cfg, AlpacaRestClient restClient, Clock clock) {
        this(
                cfg,
                new TickRingBuffer(cfg.ringBufferCapacity()),
                new ConnectionMonitor("alpaca-ws", Clock.systemUTC()),
                restClient,
                clock);
    }

    /**
     * Backwards-compatible single-arg ctor used by older unit tests that don't need to
     * exercise the pre-warm or scheduler paths. Constructs a default-sized buffer +
     * monitor and skips pre-warm (the {@link AlpacaRestClient} dependency is null).
     */
    public LivePipeline(AlpacaConfig cfg) {
        this(
                cfg,
                new TickRingBuffer(cfg.ringBufferCapacity()),
                new ConnectionMonitor("alpaca-ws", Clock.systemUTC()),
                null,
                Clock.systemUTC());
    }

    /** Test seam: lets a test inject a sized buffer + monitor without touching env config. */
    LivePipeline(
            AlpacaConfig cfg,
            TickRingBuffer buffer,
            ConnectionMonitor connectionMonitor,
            AlpacaRestClient restClient,
            Clock clock) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.connectionMonitor = Objects.requireNonNull(connectionMonitor, "connectionMonitor");
        this.restClient = restClient; // may be null in legacy tests; pre-warm is skipped if null
        this.clock = clock != null ? clock : Clock.systemUTC();

        // Phase 1 ships single-symbol (SPY). Multi-symbol expansion will register one
        // BarAggregator per symbol upstream of a fan-out router; the per-symbol contract
        // of BarAggregator already enforces this (it ignores ticks for other symbols).
        String symbol = cfg.symbols().isEmpty() ? "SPY" : cfg.symbols().get(0);

        // IndicatorEngine first so the bar fan-out lambda below can capture it as a
        // definitely-assigned final local (Java's definite-assignment doesn't track
        // lambda captures across out-of-order constructor assignments).
        java.util.function.Consumer<IndicatorSnapshot> snapSink = snap -> {
            // Phase 2 wires the Decision Engine onto this. Phase 1 just logs at DEBUG.
            if (LOG.isDebugEnabled()) {
                LOG.debug("indicator snapshot symbol={} timestamp={}", snap.symbol(), snap.timestamp());
            }
        };
        this.indicatorEngine = new IndicatorEngine(symbol, snapSink);
        BarListener barFanout = new BarListener() {
            @Override
            public void onBar(Bar bar) {
                // Indicator engine is the always-on internal subscriber.
                try {
                    indicatorEngine.onBar(bar);
                } catch (RuntimeException e) {
                    LOG.warn("indicator engine onBar failed: {}", e.toString());
                }
                // Fan out to externally-registered listeners (persistence, future
                // decision engine wiring, etc.). Per-listener exception isolation —
                // one listener's failure must not block the others.
                for (BarListener listener : additionalBarListeners) {
                    try {
                        listener.onBar(bar);
                    } catch (RuntimeException e) {
                        LOG.warn(
                                "additional bar listener {} onBar failed: {}",
                                listener.getClass().getName(),
                                e.toString());
                    }
                }
            }
        };
        // Quote routing: in the live path, AlpacaStream dispatches quotes directly to
        // the listener registered with the stream (see quoteCountingListener()). Quotes
        // never flow through the ring buffer or the aggregator, so the aggregator's
        // optional quote-forward seam is unused here. Phase 3 trail manager will hook
        // the WS-side listener directly.
        this.barAggregator = new BarAggregator(
                symbol,
                BAR_ZONE,
                List.of(Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.FIFTEEN_MIN, Timeframe.DAILY),
                barFanout);
    }

    void start(@Observes StartupEvent ev) {
        LOG.info(
                "LivePipeline starting symbols={} ringBufferCapacity={} feed={} dependency={}",
                cfg.symbols(),
                cfg.ringBufferCapacity(),
                cfg.feed(),
                connectionMonitor.dependency());

        // EMA pre-warm runs synchronously BEFORE the drainer starts and BEFORE the WS
        // connects, so the IndicatorEngine has populated 13/48/200 windows before the
        // first live bar arrives. Skip when api-key is blank (dev / replay) — the system
        // still runs unbootstrapped and EMA200 just stays null longer.
        if (!cfg.apiKey().isBlank() && restClient != null) {
            prewarmIndicators();
        }

        // Drainer is always started — even when the WS is skipped — so tests and the
        // in-process replay path can offer ticks straight to the buffer and observe
        // bars flow through the aggregator.
        startDrainer();

        if (cfg.apiKey().isBlank()) {
            LOG.warn("alpaca credentials missing — skipping live WS connect; service running in idle mode");
            return;
        }

        // Construct the AlpacaStream + transport. The Builder requires a transport up
        // front, but the transport's Listener has to be the one returned by
        // AlpacaStream#createTransportListener — which exists only after build().
        // Resolve the chicken-and-egg via an AtomicReference indirection: the
        // transport delegates every callback through the ref, and we set the ref
        // immediately after building the stream.
        AtomicReference<WsTransport.Listener> listenerRef = new AtomicReference<>();
        WsTransport transport = new JdkWsTransport(
                URI.create(cfg.wsUrl()),
                HttpClient.newHttpClient(),
                AlpacaStream.defaultConnectTimeout(),
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

        AlpacaStream s = AlpacaStream.builder()
                .transport(transport)
                .monitor(connectionMonitor)
                .buffer(buffer)
                .listener(quoteCountingListener())
                .symbols(cfg.symbols())
                .apiKey(cfg.apiKey())
                .secretKey(cfg.secretKey())
                .build();
        listenerRef.set(s.createTransportListener());
        this.stream = s;

        // Kick the connect — fire-and-forget; AlpacaStream + ConnectionMonitor handle
        // failures by transitioning the FSM. An outer supervisor (Phase 7) will decide
        // when to recreate + restart on UNHEALTHY.
        s.start();
    }

    void stop(@Observes ShutdownEvent ev) {
        LOG.info("LivePipeline stopping");
        shutdown = true;
        Thread d = drainer;
        if (d != null) {
            d.interrupt();
            try {
                d.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_AWAIT_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AlpacaStream s = stream;
        if (s != null) {
            try {
                s.stop().toCompletableFuture().get(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warn("alpaca stream stop did not complete cleanly: {}", e.toString());
            }
        }
    }

    /**
     * Pre-warm the {@link IndicatorEngine} EMA windows by replaying a window of recent
     * 2-min historical bars from Alpaca. We deliberately feed bars only into the
     * indicator engine (not the {@link BarAggregator}) — seeding the aggregator would
     * create phantom in-flight bar state at the next live tick boundary.
     *
     * <p>Package-private so tests can drive it directly without needing to spin up the
     * WS connect path.
     */
    void prewarmIndicators() {
        String symbol = cfg.symbols().isEmpty() ? "SPY" : cfg.symbols().get(0);
        Instant now = Instant.now(clock);
        Instant start = now.minus(PREWARM_LOOKBACK);
        long t0 = System.currentTimeMillis();
        try {
            List<Bar> historical = restClient.fetchHistoricalBars(symbol, Timeframe.TWO_MIN, start, now, PREWARM_LIMIT);
            int seeded = 0;
            for (Bar bar : historical) {
                // Pre-warm bars are fed directly into the indicator engine — the aggregator
                // is intentionally bypassed to avoid corrupting its in-flight bar state.
                try {
                    indicatorEngine.onBar(bar);
                    seeded++;
                } catch (RuntimeException e) {
                    LOG.warn("indicator engine prewarm onBar failed: {}", e.toString());
                }
            }
            long elapsed = System.currentTimeMillis() - t0;
            if (elapsed > PREWARM_SLOW_MILLIS) {
                LOG.warn("prewarm slow: {} ms (threshold {} ms)", elapsed, PREWARM_SLOW_MILLIS);
            }
            if (seeded == 0) {
                LOG.warn(
                        "prewarm fetched 0 bars for symbol={} window=[{}, {}) — EMA windows remain cold",
                        symbol,
                        start,
                        now);
                return;
            }
            LOG.info(
                    "prewarm complete: {} bars seeded; latest ema13={} ema48={} ema200={}",
                    seeded,
                    indicatorEngine.currentEma13(),
                    indicatorEngine.currentEma48(),
                    indicatorEngine.currentEma200());
        } catch (RuntimeException e) {
            // Pre-warm is best-effort — never fail startup if it fails.
            LOG.warn("prewarm failed for symbol={}: {} — continuing without warm EMA state", symbol, e.toString());
        }
    }

    private void startDrainer() {
        shutdown = false;
        // Virtual thread — drainer spends most of its life parked. Single-consumer
        // contract: only this thread drains the buffer.
        Thread t = Thread.ofVirtual().name("market-data-drainer").unstarted(this::drainLoop);
        this.drainer = t;
        t.start();
    }

    private void drainLoop() {
        while (!shutdown) {
            List<Tick> batch = buffer.drain(DRAIN_BATCH);
            if (batch.isEmpty()) {
                LockSupport.parkNanos(IDLE_PARK_NANOS);
                continue;
            }
            for (Tick tick : batch) {
                barAggregator.onTick(tick);
            }
        }
    }

    /**
     * Listener handed to {@link AlpacaStream}: ticks are absorbed by the ring buffer
     * inside {@code AlpacaStream.InternalListener} so we don't need to handle them here.
     * Quotes are counted (Phase 3 trail manager will replace this).
     */
    private TickListener quoteCountingListener() {
        return new TickListener() {
            @Override
            public void onTick(Tick tick) {
                // No-op — the bar path consumes ticks through the buffer + drainer.
            }

            @Override
            public void onQuote(Quote quote) {
                quoteCount.incrementAndGet();
            }
        };
    }

    public BarAggregator barAggregator() {
        return barAggregator;
    }

    public IndicatorEngine indicatorEngine() {
        return indicatorEngine;
    }

    public ConnectionMonitor connectionMonitor() {
        return connectionMonitor;
    }

    public TickRingBuffer tickRingBuffer() {
        return buffer;
    }

    public long quoteCount() {
        return quoteCount.get();
    }

    /** Whether the live WS path was wired (false in dev/replay when api key is blank). */
    public boolean wsAttached() {
        return stream != null;
    }

    /**
     * Register an additional bar listener for the fan-out path. Thread-safe — listeners
     * may be added at startup (via {@link StartupEvent} observers) or later. The internal
     * {@link IndicatorEngine} is always invoked first; registered listeners are invoked
     * in registration order. Per-listener exceptions are logged and swallowed so one
     * failing sink can't block the others.
     */
    public void registerBarListener(BarListener listener) {
        Objects.requireNonNull(listener, "listener");
        additionalBarListeners.add(listener);
    }
}
