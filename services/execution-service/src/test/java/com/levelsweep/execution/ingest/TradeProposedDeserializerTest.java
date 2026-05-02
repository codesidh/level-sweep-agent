package com.levelsweep.execution.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeProposed;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * QuarkusTest-backed unit tests for {@link TradeProposedDeserializer}. The
 * deserializer extends Quarkus's {@code ObjectMapperDeserializer} which pulls
 * the application {@link ObjectMapper} from ARC at runtime — so this test
 * boots Quarkus to get the real bean graph (with
 * {@code jackson-datatype-jsr310} + {@code jackson-datatype-jdk8} pre-registered
 * by {@code quarkus-jackson}).
 *
 * <p>Verifies that a Kafka {@code byte[]} payload — produced by the
 * {@code TradeProposedKafkaPublisher} on the decision-engine side via
 * {@code ObjectMapperSerializer} — round-trips back into a fully-populated
 * {@link TradeProposed} record with every field intact (including the
 * {@code Optional<BigDecimal>} IV / delta and the immutable
 * {@code signalReasons} list).
 *
 * <p>The wire-shape is fixed by the producer; this test catches accidental
 * field renames, removed-from-record fields, or default-mapper drift.
 */
@QuarkusTest
class TradeProposedDeserializerTest {

    @Inject
    ObjectMapper mapper;

    @Test
    void roundTripsAllFields() throws Exception {
        TradeProposed original = new TradeProposed(
                "OWNER",
                "trade-abc",
                LocalDate.parse("2026-04-30"),
                Instant.parse("2026-04-30T13:32:00Z"),
                "SPY",
                OptionSide.CALL,
                "SPY260430C00595000",
                new BigDecimal("1.20"),
                new BigDecimal("1.25"),
                new BigDecimal("1.225"),
                Optional.of(new BigDecimal("0.18")),
                Optional.of(new BigDecimal("0.50")),
                "corr-trade-abc",
                List.of("pdh_sweep", "ema_confluence"));
        byte[] wire = mapper.writeValueAsBytes(original);

        TradeProposedDeserializer deserializer = new TradeProposedDeserializer();
        try {
            TradeProposed decoded = deserializer.deserialize("tenant.commands", wire);

            assertThat(decoded).isNotNull();
            assertThat(decoded.tenantId()).isEqualTo("OWNER");
            assertThat(decoded.tradeId()).isEqualTo("trade-abc");
            assertThat(decoded.sessionDate()).isEqualTo(LocalDate.parse("2026-04-30"));
            assertThat(decoded.proposedAt()).isEqualTo(Instant.parse("2026-04-30T13:32:00Z"));
            assertThat(decoded.underlying()).isEqualTo("SPY");
            assertThat(decoded.side()).isEqualTo(OptionSide.CALL);
            assertThat(decoded.contractSymbol()).isEqualTo("SPY260430C00595000");
            assertThat(decoded.entryNbboBid()).isEqualByComparingTo("1.20");
            assertThat(decoded.entryNbboAsk()).isEqualByComparingTo("1.25");
            assertThat(decoded.entryMid()).isEqualByComparingTo("1.225");
            assertThat(decoded.impliedVolatility()).isPresent().get().satisfies(iv -> assertThat(iv)
                    .isEqualByComparingTo("0.18"));
            assertThat(decoded.delta()).isPresent().get().satisfies(d -> assertThat(d)
                    .isEqualByComparingTo("0.50"));
            assertThat(decoded.correlationId()).isEqualTo("corr-trade-abc");
            assertThat(decoded.signalReasons()).containsExactly("pdh_sweep", "ema_confluence");
        } finally {
            deserializer.close();
        }
    }

    @Test
    void deserializesPutSideEventWithEmptyOptionals() throws Exception {
        TradeProposed original = new TradeProposed(
                "OWNER",
                "trade-put",
                LocalDate.parse("2026-04-30"),
                Instant.parse("2026-04-30T14:02:00Z"),
                "SPY",
                OptionSide.PUT,
                "SPY260430P00580000",
                new BigDecimal("2.30"),
                new BigDecimal("2.35"),
                new BigDecimal("2.325"),
                Optional.empty(),
                Optional.empty(),
                "corr-trade-put",
                List.of("pdl_sweep"));
        byte[] wire = mapper.writeValueAsBytes(original);

        TradeProposedDeserializer deserializer = new TradeProposedDeserializer();
        try {
            TradeProposed decoded = deserializer.deserialize("tenant.commands", wire);

            assertThat(decoded.side()).isEqualTo(OptionSide.PUT);
            assertThat(decoded.contractSymbol()).isEqualTo("SPY260430P00580000");
            // Optional<BigDecimal> empty round-trips correctly when the strike
            // selector did not provide IV or delta.
            assertThat(decoded.impliedVolatility()).isEmpty();
            assertThat(decoded.delta()).isEmpty();
            assertThat(decoded.signalReasons()).containsExactly("pdl_sweep");
        } finally {
            deserializer.close();
        }
    }

    @Test
    void identicalWirePayloadsDecodeToEqualRecords() throws Exception {
        // Determinism check: two identical wire payloads decode to two records
        // that compare-equal field-for-field. The replay-parity harness relies
        // on this — a TradeProposed delivered twice (out-of-order recovery)
        // must yield the same record.
        TradeProposed original = new TradeProposed(
                "OWNER",
                "trade-determinism",
                LocalDate.parse("2026-04-30"),
                Instant.parse("2026-04-30T15:14:00Z"),
                "SPY",
                OptionSide.CALL,
                "SPY260430C00598000",
                new BigDecimal("0.85"),
                new BigDecimal("0.90"),
                new BigDecimal("0.875"),
                Optional.of(new BigDecimal("0.21")),
                Optional.of(new BigDecimal("0.42")),
                "corr-determinism",
                List.of("pdh_sweep"));
        byte[] wire = mapper.writeValueAsBytes(original);

        TradeProposedDeserializer deserializer = new TradeProposedDeserializer();
        try {
            TradeProposed first = deserializer.deserialize("tenant.commands", wire);
            TradeProposed second = deserializer.deserialize(
                    "tenant.commands", new String(wire, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));

            assertThat(second).isEqualTo(first);
        } finally {
            deserializer.close();
        }
    }
}
