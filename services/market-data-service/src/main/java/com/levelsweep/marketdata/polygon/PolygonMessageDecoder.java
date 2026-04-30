package com.levelsweep.marketdata.polygon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses raw Polygon WebSocket frames (arrays of {@link PolygonMessage}) and
 * dispatches them to a {@link TickListener}. Pure logic — no IO, no
 * concurrency, fully deterministic.
 *
 * <p>Frame format (example):
 *
 * <pre>
 *   [
 *     {"ev":"T","sym":"SPY","p":594.23,"s":100,"t":1714492800000},
 *     {"ev":"Q","sym":"SPY","bp":594.20,"bs":100,"ap":594.25,"as":200,"t":1714492800001}
 *   ]
 * </pre>
 *
 * <p>Malformed messages are logged and dropped without throwing — the
 * connection should keep flowing past one bad frame. Counts of malformed
 * messages are tracked so callers can alert on excessive corruption.
 */
public final class PolygonMessageDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(PolygonMessageDecoder.class);

    private final ObjectMapper json;
    private long malformedCount;

    public PolygonMessageDecoder(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /** Decode a single text frame from the WebSocket and dispatch to listener. */
    public void decode(String frame, TickListener listener) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(listener, "listener");
        List<PolygonMessage> messages;
        try {
            messages = json.readValue(
                    frame, json.getTypeFactory().constructCollectionType(List.class, PolygonMessage.class));
        } catch (JsonProcessingException e) {
            malformedCount++;
            LOG.warn("dropped malformed Polygon frame (count={}): {}", malformedCount, e.getOriginalMessage());
            return;
        }
        for (PolygonMessage m : messages) {
            dispatch(m, listener);
        }
    }

    private void dispatch(PolygonMessage m, TickListener listener) {
        try {
            if (m.isTrade()) {
                listener.onTick(toTick(m));
            } else if (m.isQuote()) {
                listener.onQuote(toQuote(m));
            } else if (m.isStatus()) {
                listener.onStatus(m.status(), m.message());
            }
            // Other event types (A, AM, etc.) silently dropped per Phase 1 scope.
        } catch (Exception e) {
            malformedCount++;
            LOG.warn("dropped Polygon message (count={}, event={}): {}", malformedCount, m.event(), e.getMessage());
        }
    }

    private static Tick toTick(PolygonMessage m) {
        if (m.symbol() == null || m.price() == null || m.timestampMillis() == null) {
            throw new IllegalArgumentException("trade missing required fields");
        }
        long size = m.size() == null ? 0L : m.size();
        return new Tick(m.symbol(), BigDecimal.valueOf(m.price()), size, Instant.ofEpochMilli(m.timestampMillis()));
    }

    private static Quote toQuote(PolygonMessage m) {
        if (m.symbol() == null || m.bidPrice() == null || m.askPrice() == null || m.timestampMillis() == null) {
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
                Instant.ofEpochMilli(m.timestampMillis()));
    }

    /** Number of frames or messages dropped due to parse / validation errors. */
    public long malformedCount() {
        return malformedCount;
    }
}
