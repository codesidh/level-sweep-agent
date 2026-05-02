package com.levelsweep.aiagent.narrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.trade.TradeFilled;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * QuarkusTest-backed unit tests for {@link TradeFilledDeserializer}. Mirrors
 * execution-service's {@code TradeProposedDeserializerTest} pattern.
 *
 * <p>The deserializer extends Quarkus's {@code ObjectMapperDeserializer} which
 * pulls the application {@link ObjectMapper} from ARC at runtime — so this
 * test boots Quarkus to get the real bean graph (with
 * {@code jackson-datatype-jsr310} pre-registered by {@code quarkus-jackson},
 * required for {@link Instant} round-tripping).
 *
 * <p>Verifies that a Kafka {@code byte[]} payload — produced by
 * execution-service's {@code TradeFilledKafkaPublisher} via
 * {@code ObjectMapperSerializer} — round-trips back into a fully-populated
 * {@link TradeFilled} record. The wire shape is fixed by the producer; this
 * test catches accidental field renames or default-mapper drift.
 */
@QuarkusTest
class TradeFilledDeserializerTest {

    @Inject
    ObjectMapper mapper;

    @Test
    void roundTripsAllFields() throws Exception {
        TradeFilled original = new TradeFilled(
                "OWNER",
                "TR_2026-05-02_001",
                "AO_42",
                "SPY250502C00500000",
                new BigDecimal("1.42"),
                2,
                "filled",
                Instant.parse("2026-05-02T13:32:30Z"),
                "corr_TR_2026-05-02_001");
        byte[] wire = mapper.writeValueAsBytes(original);

        TradeFilledDeserializer deserializer = new TradeFilledDeserializer();
        try {
            TradeFilled decoded = deserializer.deserialize("tenant.fills", wire);

            assertThat(decoded).isNotNull();
            assertThat(decoded.tenantId()).isEqualTo("OWNER");
            assertThat(decoded.tradeId()).isEqualTo("TR_2026-05-02_001");
            assertThat(decoded.alpacaOrderId()).isEqualTo("AO_42");
            assertThat(decoded.contractSymbol()).isEqualTo("SPY250502C00500000");
            assertThat(decoded.filledAvgPrice()).isEqualByComparingTo(new BigDecimal("1.42"));
            assertThat(decoded.filledQty()).isEqualTo(2);
            assertThat(decoded.status()).isEqualTo("filled");
            assertThat(decoded.filledAt()).isEqualTo(Instant.parse("2026-05-02T13:32:30Z"));
            assertThat(decoded.correlationId()).isEqualTo("corr_TR_2026-05-02_001");
        } finally {
            deserializer.close();
        }
    }

    @Test
    void deserializesPartialFillStatus() throws Exception {
        TradeFilled original = new TradeFilled(
                "OWNER",
                "TR_2026-05-02_002",
                "AO_43",
                "SPY250502P00498000",
                new BigDecimal("2.05"),
                1,
                "partial_fill",
                Instant.parse("2026-05-02T14:00:00Z"),
                "corr_TR_2026-05-02_002");
        byte[] wire = mapper.writeValueAsBytes(original);

        TradeFilledDeserializer deserializer = new TradeFilledDeserializer();
        try {
            TradeFilled decoded = deserializer.deserialize("tenant.fills", wire);

            assertThat(decoded.status()).isEqualTo("partial_fill");
            assertThat(decoded.filledQty()).isEqualTo(1);
        } finally {
            deserializer.close();
        }
    }

    @Test
    void identicalPayloadsDecodeToEqualRecords() throws Exception {
        TradeFilled original = new TradeFilled(
                "OWNER",
                "TR_DET",
                "AO_99",
                "SPY250502C00501000",
                new BigDecimal("0.85"),
                2,
                "filled",
                Instant.parse("2026-05-02T13:40:00Z"),
                "corr_TR_DET");
        byte[] wire = mapper.writeValueAsBytes(original);

        TradeFilledDeserializer deserializer = new TradeFilledDeserializer();
        try {
            TradeFilled first = deserializer.deserialize("tenant.fills", wire);
            TradeFilled second = deserializer.deserialize("tenant.fills", wire);
            assertThat(second).isEqualTo(first);
        } finally {
            deserializer.close();
        }
    }
}
