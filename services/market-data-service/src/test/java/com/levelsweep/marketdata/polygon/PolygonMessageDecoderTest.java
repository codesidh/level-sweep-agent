package com.levelsweep.marketdata.polygon;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolygonMessageDecoderTest {

    private PolygonMessageDecoder decoder;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        decoder = new PolygonMessageDecoder(new ObjectMapper());
        listener = new RecordingListener();
    }

    @Test
    void decodesSingleTrade() {
        String frame = "[{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":1714492800000}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).hasSize(1);
        Tick t = listener.ticks.get(0);
        assertThat(t.symbol()).isEqualTo("SPY");
        assertThat(t.price()).isEqualByComparingTo("594.23");
        assertThat(t.size()).isEqualTo(100L);
        assertThat(t.timestamp()).isEqualTo(Instant.ofEpochMilli(1714492800000L));
    }

    @Test
    void decodesSingleQuote() {
        String frame =
                "[{\"ev\":\"Q\",\"sym\":\"SPY\",\"bp\":594.20,\"bs\":100,\"ap\":594.25,\"as\":200,\"t\":1714492800001}]";
        decoder.decode(frame, listener);
        assertThat(listener.quotes).hasSize(1);
        Quote q = listener.quotes.get(0);
        assertThat(q.symbol()).isEqualTo("SPY");
        assertThat(q.bidPrice()).isEqualByComparingTo("594.20");
        assertThat(q.askPrice()).isEqualByComparingTo("594.25");
        assertThat(q.bidSize()).isEqualTo(100L);
        assertThat(q.askSize()).isEqualTo(200L);
        assertThat(q.mid()).isEqualByComparingTo("594.225");
    }

    @Test
    void decodesMixedFrame() {
        String frame = "["
                + "{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":1714492800000},"
                + "{\"ev\":\"Q\",\"sym\":\"SPY\",\"bp\":594.22,\"bs\":50,\"ap\":594.24,\"as\":75,\"t\":1714492800001},"
                + "{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.24,\"s\":200,\"t\":1714492800002}"
                + "]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).hasSize(2);
        assertThat(listener.quotes).hasSize(1);
    }

    @Test
    void decodesStatusFrame() {
        String frame = "[{\"ev\":\"status\",\"status\":\"auth_success\",\"message\":\"authenticated\"}]";
        decoder.decode(frame, listener);
        assertThat(listener.statuses).containsExactly("auth_success|authenticated");
    }

    @Test
    void ignoresUnknownEventTypes() {
        // 'A' is second-aggregates from Polygon — Phase 1 doesn't consume it.
        String frame =
                "[{\"ev\":\"A\",\"sym\":\"SPY\",\"o\":594.0,\"c\":594.5,\"h\":594.6,\"l\":593.9,\"v\":1000,\"s\":1714492800000}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).isEmpty();
        assertThat(listener.quotes).isEmpty();
    }

    @Test
    void malformedFrameDoesNotThrowAndIncrementsCounter() {
        String frame = "not-json-at-all";
        decoder.decode(frame, listener);
        assertThat(decoder.malformedCount()).isEqualTo(1);
        assertThat(listener.ticks).isEmpty();
    }

    @Test
    void messageMissingRequiredFieldIsSkipped() {
        // No price field — decoder should drop without throwing
        String frame = "[{\"ev\":\"T\",\"sym\":\"SPY\",\"s\":100,\"t\":1714492800000}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).isEmpty();
        assertThat(decoder.malformedCount()).isEqualTo(1);
    }

    @Test
    void emptyArrayIsAccepted() {
        decoder.decode("[]", listener);
        assertThat(listener.ticks).isEmpty();
        assertThat(decoder.malformedCount()).isZero();
    }

    @Test
    void unknownFieldsIgnored() {
        // Polygon may add new fields; we should continue parsing the known ones.
        String frame =
                "[{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":1714492800000,\"newField\":\"future\"}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).hasSize(1);
        assertThat(decoder.malformedCount()).isZero();
    }

    @Test
    void zeroSizeAccepted() {
        // Polygon sometimes reports trades with size 0 (e.g., odd-lot prints) — accept.
        String frame = "[{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.23,\"s\":0,\"t\":1714492800000}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).hasSize(1);
        assertThat(listener.ticks.get(0).size()).isZero();
    }

    @Test
    void listenerExceptionDoesNotPropagate() {
        TickListener throwingListener = new TickListener() {
            @Override
            public void onTick(Tick tick) {
                throw new RuntimeException("downstream blew up");
            }

            @Override
            public void onQuote(Quote quote) {
                // no-op
            }
        };
        String frame = "[{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":1714492800000}]";
        // Should not throw out of decode()
        decoder.decode(frame, throwingListener);
        assertThat(decoder.malformedCount()).isEqualTo(1);
    }

    private static final class RecordingListener implements TickListener {
        final List<Tick> ticks = new ArrayList<>();
        final List<Quote> quotes = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();

        @Override
        public void onTick(Tick tick) {
            ticks.add(tick);
        }

        @Override
        public void onQuote(Quote quote) {
            quotes.add(quote);
        }

        @Override
        public void onStatus(String status, String message) {
            statuses.add(status + "|" + message);
        }
    }

    @Test
    void priceArrivesWithExactScale() {
        // Verify we don't lose precision via double round-trip on common prices
        String frame = "[{\"ev\":\"T\",\"sym\":\"SPY\",\"p\":594.05,\"s\":100,\"t\":1714492800000}]";
        decoder.decode(frame, listener);
        Tick t = listener.ticks.get(0);
        assertThat(t.price()).isEqualByComparingTo(BigDecimal.valueOf(594.05));
    }
}
