package com.levelsweep.journal.kafka;

import com.levelsweep.journal.audit.AuditRecord;
import com.levelsweep.journal.audit.AuditWriter;
import com.levelsweep.shared.domain.trade.TradeFilled;
import java.util.Objects;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the {@code tenant.fills} topic (architecture-spec
 * §12.1). Producer side: {@code execution-service}'s
 * {@code TradeFilledKafkaPublisher} (Phase 3 Step 3). Consumer group:
 * {@code journal-service} (architecture-spec §12 — one group per logical
 * service so each consumer sees the full stream).
 *
 * <p>Each consumed event lands as one {@code event_type=FILL} row in
 * {@code audit_log.events}. The payload preserves the original record's
 * fields (tradeId, alpacaOrderId, contractSymbol, filledAvgPrice, filledQty,
 * status, filledAt) so the dashboard's Trade Journal pane can render the
 * full fill detail without joining back to MS SQL.
 *
 * <p>Determinism: the consumer's behavior is a pure function of the inbound
 * record — no clock reads, no state. The {@link AuditWriter} stamps
 * {@code written_at} from its injected {@code Clock}, which is the only
 * non-deterministic field in the persisted document.
 *
 * <p>Failure mode: if the Mongo write throws, the {@link AuditWriter}
 * rethrows; Spring Kafka's default error handler retries with backoff.
 * After exhausting retries the record goes to the journal-service DLQ
 * (configured in Phase 7) — until then, retries continue indefinitely with
 * exponential backoff, which is the right cold-path stance: never drop an
 * audit row.
 */
@Component
public class TradeFillConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TradeFillConsumer.class);

    /** Source service tag stamped on every audit row this consumer writes. */
    static final String SOURCE_SERVICE = "execution-service";

    /** Discriminator value for fills in {@code audit_log.events.event_type}. */
    static final String EVENT_TYPE_FILL = "FILL";

    private final AuditWriter auditWriter;

    public TradeFillConsumer(AuditWriter auditWriter) {
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
    }

    @KafkaListener(
            topics = "tenant.fills",
            groupId = "journal-service",
            containerFactory = "tradeFilledKafkaListenerContainerFactory")
    public void onTradeFilled(TradeFilled event) {
        Objects.requireNonNull(event, "event");
        LOG.info(
                "audit fill tenant={} tradeId={} alpacaOrderId={} contractSymbol={} status={} filledQty={} correlationId={}",
                event.tenantId(),
                event.tradeId(),
                event.alpacaOrderId(),
                event.contractSymbol(),
                event.status(),
                event.filledQty(),
                event.correlationId());

        AuditRecord record = AuditRecord.withCorrelation(
                event.tenantId(),
                EVENT_TYPE_FILL,
                SOURCE_SERVICE,
                toPayload(event),
                event.filledAt(),
                event.correlationId());
        auditWriter.write(record);
    }

    /**
     * Build the BSON payload from the typed event. Package-private so unit
     * tests can assert the wire-shape without touching the Kafka listener
     * machinery.
     */
    static Document toPayload(TradeFilled event) {
        Document payload = new Document()
                .append("tradeId", event.tradeId())
                .append("alpacaOrderId", event.alpacaOrderId())
                .append("contractSymbol", event.contractSymbol())
                .append("filledAvgPrice", event.filledAvgPrice().toPlainString())
                .append("filledQty", event.filledQty())
                .append("status", event.status())
                .append("filledAt", event.filledAt());
        return payload;
    }
}
