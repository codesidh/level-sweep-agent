package com.levelsweep.marketdata.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.marketdata.api.TickListener;
import com.levelsweep.shared.domain.marketdata.Quote;
import com.levelsweep.shared.domain.marketdata.Tick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlpacaMessageDecoderTest {

    private AlpacaMessageDecoder decoder;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        decoder = new AlpacaMessageDecoder(new ObjectMapper());
        listener = new RecordingListener();
    }

    @Test
    void decodesSingleTrade() {
        // Alpaca trade envelope: T-field discriminator, ISO-8601 timestamps, conditions array on `c`
        String frame = "[{\"T\":\"t\",\"S\":\"SPY\",\"i\":1,\"x\":\"D\",\"p\":594.23,\"s\":100,"
                + "\"c\":[\"@\",\"I\"],\"t\":\"2026-04-30T13:30:00Z\",\"z\":\"C\"}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).hasSize(1);
        Tick t = listener.ticks.get(0);
        assertThat(t.symbol()).isEqualTo("SPY");
        assertThat(t.price()).isEqualByComparingTo("594.23");
        assertThat(t.size()).isEqualTo(100L);
        assertThat(t.timestamp()).isEqualTo(Instant.parse("2026-04-30T13:30:00Z"));
    }

    @Test
    void decodesSingleQuote() {
        String frame = "[{\"T\":\"q\",\"S\":\"SPY\",\"bp\":594.20,\"bs\":100,\"ap\":594.25,\"as\":200,"
                + "\"bx\":\"C\",\"ax\":\"H\",\"t\":\"2026-04-30T13:30:00.001Z\"}]";
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
                + "{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":\"2026-04-30T13:30:00Z\"},"
                + "{\"T\":\"q\",\"S\":\"SPY\",\"bp\":594.22,\"bs\":50,\"ap\":594.24,\"as\":75,\"t\":\"2026-04-30T13:30:00.5Z\"},"
                + "{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.24,\"s\":200,\"t\":\"2026-04-30T13:30:01Z\"}"
                + "]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).hasSize(2);
        assertThat(listener.quotes).hasSize(1);
    }

    @Test
    void decodesSuccessStatusFrame() {
        String frame = "[{\"T\":\"success\",\"msg\":\"connected\"}," + "{\"T\":\"success\",\"msg\":\"authenticated\"}]";
        decoder.decode(frame, listener);
        assertThat(listener.statuses).hasSize(2);
        assertThat(listener.statuses.get(0)).isEqualTo("success|connected");
        assertThat(listener.statuses.get(1)).isEqualTo("success|authenticated");
    }

    @Test
    void decodesSubscriptionConfirmation() {
        String frame = "[{\"T\":\"subscription\",\"trades\":[\"SPY\"],\"quotes\":[\"SPY\"],\"bars\":[\"SPY\"]}]";
        decoder.decode(frame, listener);
        assertThat(listener.statuses).hasSize(1);
        assertThat(listener.statuses.get(0))
                .contains("trades=SPY")
                .contains("quotes=SPY")
                .contains("bars=SPY");
    }

    @Test
    void decodesErrorFrame() {
        String frame = "[{\"T\":\"error\",\"code\":402,\"msg\":\"auth required\"}]";
        decoder.decode(frame, listener);
        assertThat(listener.statuses).hasSize(1);
        assertThat(listener.statuses.get(0)).contains("error").contains("402").contains("auth required");
    }

    @Test
    void barFrameDoesNotEmitTickOrQuote() {
        // Phase 1 builds bars from ticks; upstream pre-aggregated bars are ignored.
        String frame = "[{\"T\":\"b\",\"S\":\"SPY\",\"o\":594.0,\"h\":594.5,\"l\":593.9,"
                + "\"c\":594.4,\"v\":12345,\"t\":\"2026-04-30T13:31:00Z\"}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).isEmpty();
        assertThat(listener.quotes).isEmpty();
    }

    @Test
    void malformedFrameDoesNotThrowAndIncrementsCounter() {
        decoder.decode("not-json-at-all", listener);
        assertThat(decoder.malformedCount()).isEqualTo(1);
        assertThat(listener.ticks).isEmpty();
    }

    @Test
    void messageMissingRequiredFieldIsSkipped() {
        // No price field
        String frame = "[{\"T\":\"t\",\"S\":\"SPY\",\"s\":100,\"t\":\"2026-04-30T13:30:00Z\"}]";
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
        // Forward compatibility for new Alpaca fields
        String frame = "[{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.23,\"s\":100,"
                + "\"t\":\"2026-04-30T13:30:00Z\",\"newField\":\"future\",\"z\":\"C\"}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).hasSize(1);
        assertThat(decoder.malformedCount()).isZero();
    }

    @Test
    void zeroSizeAccepted() {
        // Alpaca occasionally publishes odd-lot prints with size 0
        String frame = "[{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.23,\"s\":0,\"t\":\"2026-04-30T13:30:00Z\"}]";
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
        String frame = "[{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.23,\"s\":100,\"t\":\"2026-04-30T13:30:00Z\"}]";
        decoder.decode(frame, throwingListener);
        assertThat(decoder.malformedCount()).isEqualTo(1);
    }

    @Test
    void priceArrivesWithExactScale() {
        String frame = "[{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.05,\"s\":100,\"t\":\"2026-04-30T13:30:00Z\"}]";
        decoder.decode(frame, listener);
        Tick t = listener.ticks.get(0);
        assertThat(t.price()).isEqualByComparingTo(BigDecimal.valueOf(594.05));
    }

    @Test
    void conditionsArrayOnTradeDoesNotBreakParsing() {
        // The `c` field is a List<String> on trades and a Double on bars; our DTO uses Object
        String frame = "[{\"T\":\"t\",\"S\":\"SPY\",\"p\":594.23,\"s\":100,\"c\":[\"@\",\"I\",\"F\"],"
                + "\"t\":\"2026-04-30T13:30:00Z\"}]";
        decoder.decode(frame, listener);
        assertThat(listener.ticks).hasSize(1);
        assertThat(decoder.malformedCount()).isZero();
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
}
