package com.levelsweep.marketdata.alpaca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.marketdata.api.TickListener;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses raw Alpaca WebSocket frames (arrays of {@link AlpacaMessage}) and
 * dispatches them to a {@link TickListener}. Pure logic — no IO, no
 * concurrency, fully deterministic.
 *
 * <p>Frame format (example):
 *
 * <pre>
 *   [
 *     {"T":"t","S":"SPY","p":594.23,"s":100,"i":1,"t":"2026-04-30T13:30:00Z","x":"D"},
 *     {"T":"q","S":"SPY","bp":594.20,"bs":100,"ap":594.25,"as":200,"t":"...","bx":"C","ax":"H"}
 *   ]
 * </pre>
 *
 * <p>Status messages ({@code T:success}, {@code T:subscription},
 * {@code T:error}) flow through {@link TickListener#onStatus} so the
 * orchestrator can react (e.g., trip the Connection FSM on auth failure).
 *
 * <p>Malformed messages are logged and dropped without throwing — the
 * connection should keep flowing past one bad frame. Counts of malformed
 * messages are tracked for observability.
 */
public final class AlpacaMessageDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaMessageDecoder.class);

    private final ObjectMapper json;
    private long malformedCount;

    public AlpacaMessageDecoder(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /** Decode a single text frame from the WebSocket and dispatch to listener. */
    public void decode(String frame, TickListener listener) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(listener, "listener");
        List<AlpacaMessage> messages;
        try {
            messages = json.readValue(
                    frame, json.getTypeFactory().constructCollectionType(List.class, AlpacaMessage.class));
        } catch (JsonProcessingException e) {
            malformedCount++;
            LOG.warn("dropped malformed Alpaca frame (count={}): {}", malformedCount, e.getOriginalMessage());
            return;
        }
        for (AlpacaMessage m : messages) {
            dispatch(m, listener);
        }
    }

    private void dispatch(AlpacaMessage m, TickListener listener) {
        try {
            if (m.isTrade()) {
                listener.onTick(toTick(m));
            } else if (m.isQuote()) {
                listener.onQuote(toQuote(m));
            } else if (m.isSuccess()) {
                listener.onStatus("success", m.message());
            } else if (m.isSubscription()) {
                listener.onStatus(
                        "subscription",
                        "trades=" + safeJoin(m.tradeSubs())
                                + " quotes=" + safeJoin(m.quoteSubs())
                                + " bars=" + safeJoin(m.barSubs()));
            } else if (m.isError()) {
                listener.onStatus("error", "code=" + m.code() + " msg=" + m.message());
            } else if (m.isBar()) {
                // Bar messages are dispatched as status — the BarAggregator builds bars
                // from ticks, not from the upstream's pre-aggregated minute bars (we want
                // sub-minute aggregations like 2-min and the strategy doesn't use 1-min
                // upstream bars). If a future caller wants the upstream bars, expose
                // a separate channel here.
                LOG.trace("upstream bar (ignored): {} c={} t={}", m.symbol(), m.barClose(), m.timestamp());
            }
            // Other event types silently dropped.
        } catch (Exception e) {
            malformedCount++;
            LOG.warn("dropped Alpaca message (count={}, type={}): {}", malformedCount, m.type(), e.getMessage());
        }
    }

    private static Tick toTick(AlpacaMessage m) {
        if (m.symbol() == null || m.price() == null || m.timestamp() == null) {
            throw new IllegalArgumentException("trade missing required fields");
        }
        long size = m.size() == null ? 0L : m.size();
        return new Tick(m.symbol(), BigDecimal.valueOf(m.price()), size, Instant.parse(m.timestamp()));
    }

    private static Quote toQuote(AlpacaMessage m) {
        if (m.symbol() == null || m.bidPrice() == null || m.askPrice() == null || m.timestamp() == null) {
            throw new IllegalArgumentException("quote missing required fields");
        }
        long bidSize = m.bidSize() == null ? 0L : m.bidSize();
        long askSize = m.askSize() == null ? 0L : m.askSize();
        return new Quote(
                m.symbol(),
                BigDecimal.valueOf(m.bidPrice()),
                bidSize,
                BigDecimal.valueOf(m.askPrice()),
                askSize,
                Instant.parse(m.timestamp()));
    }

    private static String safeJoin(List<String> list) {
        return list == null ? "[]" : String.join(",", list);
    }

    /** Number of frames or messages dropped due to parse / validation errors. */
    public long malformedCount() {
        return malformedCount;
    }
}
