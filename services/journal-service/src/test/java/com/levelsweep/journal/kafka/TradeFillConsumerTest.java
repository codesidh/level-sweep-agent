package com.levelsweep.journal.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.levelsweep.journal.audit.AuditRecord;
import com.levelsweep.journal.audit.AuditWriter;
import com.levelsweep.shared.domain.trade.TradeFilled;
import java.math.BigDecimal;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TradeFillConsumer}. Drives the consumer with a
 * synthetic {@link TradeFilled} event built directly from shared-domain;
 * mocks {@link AuditWriter}. No Spring Kafka, no embedded broker — pure POJO
 * exercise of the listener method's mapping logic.
 */
@ExtendWith(MockitoExtension.class)
class TradeFillConsumerTest {

    @Mock
    private AuditWriter auditWriter;

    @Test
    void mapsTradeFilledToAuditRecordWithFillEventType() {
        TradeFillConsumer consumer = new TradeFillConsumer(auditWriter);
        TradeFilled event = sampleFill();

        consumer.onTradeFilled(event);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter).write(captor.capture());
        AuditRecord record = captor.getValue();

        // Multi-tenant: tenantId carried straight through.
        assertThat(record.tenantId()).isEqualTo("OWNER");
        // Discriminator taxonomy.
        assertThat(record.eventType()).isEqualTo("FILL");
        // Source service tag — operators filter on this in the dashboard.
        assertThat(record.sourceService()).isEqualTo("execution-service");
        // Source-time timestamp on the event maps to occurred_at.
        assertThat(record.occurredAt()).isEqualTo(event.filledAt());
        // Saga correlation propagated for trade-lifetime stitching.
        assertThat(record.correlationId()).contains("corr-1");
    }

    @Test
    void payloadPreservesAllFillFields() {
        TradeFilled event = sampleFill();

        Document payload = TradeFillConsumer.toPayload(event);

        // Every field operators / dashboard pull from the audit row must be
        // present. Guard the wire shape so a future record-class addition
        // doesn't silently drop a field.
        assertThat(payload.getString("tradeId")).isEqualTo("T1");
        assertThat(payload.getString("alpacaOrderId")).isEqualTo("alp-1");
        assertThat(payload.getString("contractSymbol")).isEqualTo("SPY260502C00500000");
        // BigDecimal serialized as a plain string (no exponent / no rounding).
        assertThat(payload.getString("filledAvgPrice")).isEqualTo("1.23");
        assertThat(payload.getInteger("filledQty")).isEqualTo(2);
        assertThat(payload.getString("status")).isEqualTo("filled");
        assertThat(payload.get("filledAt", Instant.class)).isEqualTo(event.filledAt());
    }

    @Test
    void writePathDoesNotMutateInputEvent() {
        TradeFillConsumer consumer = new TradeFillConsumer(auditWriter);
        TradeFilled event = sampleFill();
        String originalAlpacaOrderId = event.alpacaOrderId();

        consumer.onTradeFilled(event);

        // Records are immutable, but a sanity check belts-and-braces. The
        // consumer must NEVER mutate an inbound event — the saga-compensation
        // skill requires every replay-relevant input to be untouched.
        assertThat(event.alpacaOrderId()).isEqualTo(originalAlpacaOrderId);
    }

    private static TradeFilled sampleFill() {
        return new TradeFilled(
                "OWNER",
                "T1",
                "alp-1",
                "SPY260502C00500000",
                new BigDecimal("1.23"),
                2,
                "filled",
                Instant.parse("2026-05-02T13:30:00Z"),
                "corr-1");
    }
}
