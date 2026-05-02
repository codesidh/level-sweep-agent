package com.levelsweep.execution.fill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.trade.TradeFillEvent;
import com.levelsweep.shared.domain.trade.TradeFilled;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses raw Alpaca trade-updates WebSocket frames and dispatches them into a
 * {@link Listener}. Pure logic — no IO, no concurrency, fully deterministic.
 *
 * <p>Frame format (per architecture-spec §3.6 and
 * {@code docs/alpaca-trading-api-skill.md} §14):
 *
 * <pre>
 *   {
 *     "stream": "trade_updates",
 *     "data": {
 *       "event": "fill",
 *       "order": {
 *         "id": "&lt;alpaca-order-id&gt;",
 *         "client_order_id": "&lt;tenantId&gt;:&lt;tradeId&gt;",
 *         "symbol": "SPY250130C00600000",
 *         "filled_qty": "1",
 *         "filled_avg_price": "1.42",
 *         "status": "filled"
 *       },
 *       "timestamp": "2026-04-30T13:30:00.123Z"
 *     }
 *   }
 * </pre>
 *
 * <p>The listener receives:
 *
 * <ul>
 *   <li>For {@code event = fill | partial_fill}: both a {@link TradeFilled}
 *       (typed, validated, the canonical "this trade is now filled" event)
 *       AND a {@link TradeFillEvent} (catch-all, for the audit log).
 *   <li>For {@code event = new | canceled | expired | rejected}: only the
 *       {@link TradeFillEvent} catch-all — no {@link TradeFilled}.
 *   <li>For status frames ({@code stream = authorization | listening}): a
 *       status callback so the orchestrator can route auth_success → subscribe.
 * </ul>
 *
 * <p>Malformed frames are logged at WARN and dropped without throwing — a
 * single bad frame must not knock the connection down. The malformed-count
 * is exposed for observability.
 */
public final class AlpacaTradeUpdatesDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(AlpacaTradeUpdatesDecoder.class);

    private final ObjectMapper json;
    private long malformedCount;

    public AlpacaTradeUpdatesDecoder(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /** Listener for decoded events + status. */
    public interface Listener {
        /** Fired for {@code fill} and {@code partial_fill} events. */
        void onTradeFilled(TradeFilled filled);

        /** Fired for every parsed Alpaca trade-updates event (including fills). */
        void onFillEvent(TradeFillEvent event);

        /**
         * Fired for non-data status frames. Status flow:
         *
         * <ul>
         *   <li>{@code authorization status=authorized} — auth committed; safe to subscribe
         *   <li>{@code authorization status=unauthorized} — auth failed
         *   <li>{@code listening streams=[...]} — subscribe acknowledged
         * </ul>
         */
        default void onStatus(String stream, String status, String message) {
            // default no-op
        }
    }

    /** Decode a single text frame from the WebSocket and dispatch to listener. */
    public void decode(String frame, Listener listener) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(listener, "listener");
        JsonNode root;
        try {
            root = json.readTree(frame);
        } catch (JsonProcessingException e) {
            malformedCount++;
            LOG.warn("dropped malformed trade-updates frame (count={}): {}", malformedCount, e.getOriginalMessage());
            return;
        }
        if (root == null || !root.isObject()) {
            malformedCount++;
            LOG.warn("dropped non-object trade-updates frame (count={})", malformedCount);
            return;
        }

        String stream = textOrEmpty(root, "stream");
        JsonNode data = root.get("data");

        // Status frames — auth + listen acks.
        // Alpaca's trade-updates WS responds to auth with:
        //   {"stream":"authorization","data":{"status":"authorized","action":"authenticate"}}
        // and to listen with:
        //   {"stream":"listening","data":{"streams":["trade_updates"]}}
        if ("authorization".equals(stream)) {
            String status = data == null ? "" : textOrEmpty(data, "status");
            String action = data == null ? "" : textOrEmpty(data, "action");
            listener.onStatus(stream, status, action);
            return;
        }
        if ("listening".equals(stream)) {
            String streams = data == null || !data.has("streams")
                    ? ""
                    : data.get("streams").toString();
            listener.onStatus(stream, "listening", streams);
            return;
        }

        // Trade-update event.
        if (!"trade_updates".equals(stream) || data == null || !data.isObject()) {
            // Unknown stream — not malformed (forward-compat with new Alpaca streams),
            // just unobserved. Log at trace.
            LOG.trace("ignoring frame stream={}", stream);
            return;
        }

        try {
            dispatchTradeUpdate(data, listener);
        } catch (RuntimeException e) {
            malformedCount++;
            LOG.warn("dropped trade-update message (count={}): {}", malformedCount, e.getMessage());
        }
    }

    private void dispatchTradeUpdate(JsonNode data, Listener listener) {
        String event = textOrEmpty(data, "event");
        if (event.isEmpty()) {
            throw new IllegalArgumentException("trade_updates frame missing data.event");
        }
        JsonNode order = data.get("order");
        if (order == null || !order.isObject()) {
            throw new IllegalArgumentException("trade_updates frame missing data.order");
        }

        String alpacaOrderId = textOrEmpty(order, "id");
        if (alpacaOrderId.isEmpty()) {
            throw new IllegalArgumentException("trade_updates frame missing data.order.id");
        }
        String clientOrderId = textOrEmpty(order, "client_order_id");
        String contractSymbol = textOrEmpty(order, "symbol");

        // Per S2's idempotency contract, client_order_id is "<tenantId>:<tradeId>".
        // Split defensively — events from out-of-band orders (e.g. manual fills
        // made on the paper account for testing) may not follow our format.
        String tenantId = "OWNER";
        String tradeId = "";
        int colonIdx = clientOrderId.indexOf(':');
        if (colonIdx > 0 && colonIdx < clientOrderId.length() - 1) {
            tenantId = clientOrderId.substring(0, colonIdx);
            tradeId = clientOrderId.substring(colonIdx + 1);
        }

        Instant occurredAt = parseTimestampOrThrow(data, "timestamp");

        Optional<BigDecimal> filledAvgPrice = parseDecimal(order, "filled_avg_price");
        Optional<Integer> filledQty = parseInt(order, "filled_qty");
        Optional<String> reason = parseReason(order, data);

        TradeFillEvent fillEvent = new TradeFillEvent(
                tenantId,
                alpacaOrderId,
                clientOrderId == null ? "" : clientOrderId,
                event,
                occurredAt,
                filledAvgPrice,
                filledQty,
                reason);
        listener.onFillEvent(fillEvent);

        // Only fill / partial_fill events produce the typed TradeFilled.
        if (!"fill".equals(event) && !"partial_fill".equals(event)) {
            return;
        }

        // For typed TradeFilled, every required field must be present + valid.
        if (filledAvgPrice.isEmpty()) {
            throw new IllegalArgumentException("fill event missing order.filled_avg_price");
        }
        if (filledQty.isEmpty() || filledQty.get() <= 0) {
            throw new IllegalArgumentException("fill event has invalid order.filled_qty: " + filledQty);
        }
        if (contractSymbol.isEmpty()) {
            throw new IllegalArgumentException("fill event missing order.symbol");
        }
        if (tradeId.isEmpty()) {
            // Out-of-band order (no tenantId:tradeId tag) — log and drop the typed event.
            // The TradeFillEvent catch-all already went through for the audit log.
            LOG.warn(
                    "ignoring fill from untagged order alpacaOrderId={} clientOrderId={}",
                    alpacaOrderId,
                    clientOrderId);
            return;
        }

        // Correlation id: Alpaca preserves no extra metadata across the WS roundtrip,
        // so we adopt the alpacaOrderId as the correlationId for the TradeFilled. The
        // S2 OrderSubmitted event will record the (tradeId → alpacaOrderId) mapping
        // in the audit trail, and the trail manager will join on alpacaOrderId. This
        // is consistent with the "correlationId threads end-to-end" pattern from
        // TradeProposed (the saga uses tradeId; we use alpacaOrderId here because the
        // upstream WS frame has no other stable token).
        String correlationId = alpacaOrderId;

        // Alpaca emits "fill" / "partial_fill"; TradeFilled.status validates {"filled", "partial_fill"} (past-tense for fill).
        String tradeFilledStatus = "fill".equals(event) ? "filled" : event;
        TradeFilled filled = new TradeFilled(
                tenantId,
                tradeId,
                alpacaOrderId,
                contractSymbol,
                filledAvgPrice.get(),
                filledQty.get(),
                tradeFilledStatus,
                occurredAt,
                correlationId);
        listener.onTradeFilled(filled);
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return "";
        }
        return v.asText("");
    }

    private static Optional<BigDecimal> parseDecimal(JsonNode node, String field) {
        if (node == null) {
            return Optional.empty();
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return Optional.empty();
        }
        // Alpaca sends both string and numeric — handle both.
        try {
            if (v.isTextual()) {
                String s = v.asText();
                if (s.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new BigDecimal(s));
            }
            if (v.isNumber()) {
                return Optional.of(v.decimalValue());
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<Integer> parseInt(JsonNode node, String field) {
        if (node == null) {
            return Optional.empty();
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return Optional.empty();
        }
        try {
            if (v.isTextual()) {
                String s = v.asText();
                if (s.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(Integer.parseInt(s));
            }
            if (v.isNumber()) {
                return Optional.of(v.intValue());
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> parseReason(JsonNode order, JsonNode data) {
        // Alpaca puts the reason in different places depending on the event type.
        // Try both: order.reject_reason for rejected; data.reason for canceled/expired.
        String fromOrder = textOrEmpty(order, "reject_reason");
        if (!fromOrder.isEmpty()) {
            return Optional.of(fromOrder);
        }
        String fromData = textOrEmpty(data, "reason");
        if (!fromData.isEmpty()) {
            return Optional.of(fromData);
        }
        return Optional.empty();
    }

    private static Instant parseTimestampOrThrow(JsonNode data, String field) {
        String raw = textOrEmpty(data, field);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("trade_updates frame missing data." + field);
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("trade_updates frame has invalid timestamp: " + raw, e);
        }
    }

    /** Number of frames or messages dropped due to parse / validation errors. */
    public long malformedCount() {
        return malformedCount;
    }
}
