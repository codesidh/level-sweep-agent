package com.levelsweep.execution.fill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.trade.TradeFillEvent;
import com.levelsweep.shared.domain.trade.TradeFilled;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AlpacaTradeUpdatesDecoder}. Pure POJO — no Quarkus,
 * no IO. Verifies the JSON → record dispatch contract for every event type
 * the listener cares about (architecture-spec §3.6).
 */
class AlpacaTradeUpdatesDecoderTest {

    private AlpacaTradeUpdatesDecoder decoder;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        decoder = new AlpacaTradeUpdatesDecoder(new ObjectMapper());
        listener = new RecordingListener();
    }

    @Test
    void decodesFillEvent() {
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\",\"order\":"
                + "{\"id\":\"alp-ord-1\",\"client_order_id\":\"OWNER:trade-42\","
                + "\"symbol\":\"SPY260430C00595000\",\"filled_qty\":\"1\","
                + "\"filled_avg_price\":\"1.42\",\"status\":\"filled\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00.123Z\"}}";

        decoder.decode(frame, listener);

        assertThat(listener.fillEvents).hasSize(1);
        assertThat(listener.tradeFills).hasSize(1);
        TradeFilled f = listener.tradeFills.get(0);
        assertThat(f.tenantId()).isEqualTo("OWNER");
        assertThat(f.tradeId()).isEqualTo("trade-42");
        assertThat(f.alpacaOrderId()).isEqualTo("alp-ord-1");
        assertThat(f.contractSymbol()).isEqualTo("SPY260430C00595000");
        assertThat(f.filledAvgPrice()).isEqualByComparingTo(new BigDecimal("1.42"));
        assertThat(f.filledQty()).isEqualTo(1);
        assertThat(f.status()).isEqualTo("filled");
        assertThat(f.filledAt()).isEqualTo(Instant.parse("2026-04-30T13:30:00.123Z"));
        assertThat(f.correlationId()).isEqualTo("alp-ord-1");
    }

    @Test
    void decodesPartialFillWithStatusPropagated() {
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"partial_fill\",\"order\":"
                + "{\"id\":\"alp-ord-2\",\"client_order_id\":\"OWNER:trade-42\","
                + "\"symbol\":\"SPY260430C00595000\",\"filled_qty\":\"1\","
                + "\"filled_avg_price\":\"1.20\",\"status\":\"partially_filled\"},"
                + "\"timestamp\":\"2026-04-30T13:30:01Z\"}}";

        decoder.decode(frame, listener);

        assertThat(listener.tradeFills).hasSize(1);
        assertThat(listener.tradeFills.get(0).status()).isEqualTo("partial_fill");
        assertThat(listener.fillEvents).hasSize(1);
        assertThat(listener.fillEvents.get(0).alpacaEvent()).isEqualTo("partial_fill");
    }

    @Test
    void decodesNewEventAsFillEventOnlyNoTradeFilled() {
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"new\",\"order\":"
                + "{\"id\":\"alp-ord-3\",\"client_order_id\":\"OWNER:trade-99\","
                + "\"symbol\":\"SPY260430C00595000\",\"status\":\"new\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00Z\"}}";

        decoder.decode(frame, listener);

        assertThat(listener.fillEvents).hasSize(1);
        assertThat(listener.fillEvents.get(0).alpacaEvent()).isEqualTo("new");
        assertThat(listener.tradeFills).isEmpty();
    }

    @Test
    void decodesRejectedEventCarriesReason() {
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"rejected\",\"order\":"
                + "{\"id\":\"alp-ord-4\",\"client_order_id\":\"OWNER:trade-101\","
                + "\"symbol\":\"SPY260430C00595000\",\"status\":\"rejected\","
                + "\"reject_reason\":\"insufficient_buying_power\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00Z\"}}";

        decoder.decode(frame, listener);

        assertThat(listener.tradeFills).isEmpty();
        assertThat(listener.fillEvents).hasSize(1);
        TradeFillEvent ev = listener.fillEvents.get(0);
        assertThat(ev.alpacaEvent()).isEqualTo("rejected");
        assertThat(ev.reason()).contains("insufficient_buying_power");
    }

    @Test
    void decodesCanceledEventWithDataReason() {
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"canceled\",\"order\":"
                + "{\"id\":\"alp-ord-5\",\"client_order_id\":\"OWNER:trade-77\","
                + "\"symbol\":\"SPY260430C00595000\",\"status\":\"canceled\"},"
                + "\"reason\":\"manual_cancel\","
                + "\"timestamp\":\"2026-04-30T13:30:00Z\"}}";

        decoder.decode(frame, listener);

        assertThat(listener.fillEvents).hasSize(1);
        assertThat(listener.fillEvents.get(0).reason()).contains("manual_cancel");
    }

    @Test
    void decodesAuthorizationStatusFiresStatusOnly() {
        String frame =
                "{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\",\"action\":\"authenticate\"}}";

        decoder.decode(frame, listener);

        assertThat(listener.fillEvents).isEmpty();
        assertThat(listener.tradeFills).isEmpty();
        assertThat(listener.statusEvents).hasSize(1);
        assertThat(listener.statusEvents.get(0).stream()).isEqualTo("authorization");
        assertThat(listener.statusEvents.get(0).status()).isEqualTo("authorized");
    }

    @Test
    void decodesListeningStatusFiresStatusOnly() {
        String frame = "{\"stream\":\"listening\",\"data\":{\"streams\":[\"trade_updates\"]}}";

        decoder.decode(frame, listener);

        assertThat(listener.statusEvents).hasSize(1);
        assertThat(listener.statusEvents.get(0).stream()).isEqualTo("listening");
    }

    @Test
    void malformedJsonIsDroppedAndCounted() {
        String frame = "this is not json {";
        decoder.decode(frame, listener);
        assertThat(listener.fillEvents).isEmpty();
        assertThat(listener.tradeFills).isEmpty();
        assertThat(decoder.malformedCount()).isEqualTo(1);
    }

    @Test
    void nonObjectFrameIsDroppedAndCounted() {
        decoder.decode("[1,2,3]", listener);
        assertThat(listener.fillEvents).isEmpty();
        assertThat(decoder.malformedCount()).isEqualTo(1);
    }

    @Test
    void unknownStreamIsSilentlyIgnored() {
        decoder.decode("{\"stream\":\"some_future_stream\",\"data\":{}}", listener);
        assertThat(listener.fillEvents).isEmpty();
        assertThat(listener.statusEvents).isEmpty();
        // Forward-compat: unknown stream is not malformed.
        assertThat(decoder.malformedCount()).isZero();
    }

    @Test
    void fillEventMissingPriceIsDroppedAsMalformed() {
        // No filled_avg_price; only TradeFillEvent fires (catch-all), TradeFilled
        // is rejected by the validator and the message counts as malformed.
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\",\"order\":"
                + "{\"id\":\"alp-ord-6\",\"client_order_id\":\"OWNER:trade-1\","
                + "\"symbol\":\"SPY260430C00595000\",\"filled_qty\":\"1\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00Z\"}}";

        decoder.decode(frame, listener);

        // The listener may have received a partial onFillEvent before the throw —
        // implementation detail. The contract is that no TradeFilled is fired.
        assertThat(listener.tradeFills).isEmpty();
        assertThat(decoder.malformedCount()).isEqualTo(1);
    }

    @Test
    void fillEventFromUntaggedClientOrderIdIsLoggedAndDropped() {
        // client_order_id without a colon → can't parse tenantId/tradeId →
        // drop the typed TradeFilled but still surface the catch-all.
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\",\"order\":"
                + "{\"id\":\"alp-ord-7\",\"client_order_id\":\"manual-test-fill\","
                + "\"symbol\":\"SPY260430C00595000\",\"filled_qty\":\"1\","
                + "\"filled_avg_price\":\"1.50\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00Z\"}}";

        decoder.decode(frame, listener);

        assertThat(listener.fillEvents).hasSize(1);
        assertThat(listener.tradeFills).isEmpty();
    }

    @Test
    void decodingIsDeterministic() {
        // Same JSON → same TradeFilled twice.
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\",\"order\":"
                + "{\"id\":\"alp-ord-1\",\"client_order_id\":\"OWNER:trade-42\","
                + "\"symbol\":\"SPY260430C00595000\",\"filled_qty\":\"1\","
                + "\"filled_avg_price\":\"1.42\"},"
                + "\"timestamp\":\"2026-04-30T13:30:00.123Z\"}}";

        AlpacaTradeUpdatesDecoder d1 = new AlpacaTradeUpdatesDecoder(new ObjectMapper());
        AlpacaTradeUpdatesDecoder d2 = new AlpacaTradeUpdatesDecoder(new ObjectMapper());
        RecordingListener l1 = new RecordingListener();
        RecordingListener l2 = new RecordingListener();
        d1.decode(frame, l1);
        d2.decode(frame, l2);

        assertThat(l1.tradeFills).hasSize(1);
        assertThat(l2.tradeFills).hasSize(1);
        assertThat(l1.tradeFills.get(0)).isEqualTo(l2.tradeFills.get(0));
    }

    @Test
    void numericFieldsAlsoParsed() {
        // Alpaca occasionally sends filled_qty/filled_avg_price as numeric;
        // both forms must round-trip.
        String frame = "{\"stream\":\"trade_updates\",\"data\":{\"event\":\"fill\",\"order\":"
                + "{\"id\":\"alp-ord-8\",\"client_order_id\":\"OWNER:trade-1\","
                + "\"symbol\":\"SPY260430C00595000\",\"filled_qty\":2,"
                + "\"filled_avg_price\":1.42},"
                + "\"timestamp\":\"2026-04-30T13:30:00Z\"}}";

        decoder.decode(frame, listener);

        assertThat(listener.tradeFills).hasSize(1);
        assertThat(listener.tradeFills.get(0).filledQty()).isEqualTo(2);
        assertThat(listener.tradeFills.get(0).filledAvgPrice()).isEqualByComparingTo("1.42");
    }

    static final class RecordingListener implements AlpacaTradeUpdatesDecoder.Listener {
        final List<TradeFilled> tradeFills = new ArrayList<>();
        final List<TradeFillEvent> fillEvents = new ArrayList<>();
        final List<StatusEvent> statusEvents = new ArrayList<>();

        @Override
        public void onTradeFilled(TradeFilled filled) {
            tradeFills.add(filled);
        }

        @Override
        public void onFillEvent(TradeFillEvent event) {
            fillEvents.add(event);
        }

        @Override
        public void onStatus(String stream, String status, String message) {
            statusEvents.add(new StatusEvent(stream, status, message));
        }
    }

    record StatusEvent(String stream, String status, String message) {}
}
