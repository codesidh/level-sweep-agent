package com.levelsweep.marketdata.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link MongoBarRepository}. We exercise the static
 * {@code toDocument} helper directly — Testcontainers-based integration tests are
 * deferred to the operational track (S7) so CI doesn't take on a Docker dependency.
 */
class MongoBarRepositoryTest {

    private static final Instant OPEN = Instant.parse("2026-04-30T13:30:00Z");
    private static final Instant CLOSE = Instant.parse("2026-04-30T13:32:00Z");
    private static final Instant INSERTED = Instant.parse("2026-04-30T13:32:00.500Z");

    @Test
    void toDocumentIncludesAllRequiredFields() {
        Bar bar = new Bar(
                "SPY",
                Timeframe.TWO_MIN,
                OPEN,
                CLOSE,
                new BigDecimal("594.00"),
                new BigDecimal("594.50"),
                new BigDecimal("593.75"),
                new BigDecimal("594.25"),
                12_345L,
                42L);

        Document d = MongoBarRepository.toDocument(bar, "OWNER", INSERTED);

        assertThat(d.getString("tenantId")).isEqualTo("OWNER");
        assertThat(d.getString("symbol")).isEqualTo("SPY");
        assertThat(d.getString("timeframe")).isEqualTo("TWO_MIN");
        assertThat(d.get("openTime")).isEqualTo(Date.from(OPEN));
        assertThat(d.get("closeTime")).isEqualTo(Date.from(CLOSE));
        assertThat(d.get("insertedAt")).isEqualTo(Date.from(INSERTED));
        assertThat(d.getLong("volume")).isEqualTo(12_345L);
        assertThat(d.getLong("ticks")).isEqualTo(42L);
    }

    @Test
    void toDocumentStoresPricesAsPlainStrings() {
        // Use a price with extra trailing precision to confirm we store toPlainString
        // (which preserves scale) rather than Decimal128 / double.
        BigDecimal open = new BigDecimal("594.0000");
        BigDecimal high = new BigDecimal("594.5000");
        BigDecimal low = new BigDecimal("593.7500");
        BigDecimal close = new BigDecimal("594.2500");
        Bar bar = new Bar("SPY", Timeframe.ONE_MIN, OPEN, CLOSE, open, high, low, close, 100L, 5L);

        Document d = MongoBarRepository.toDocument(bar, "OWNER", INSERTED);

        assertThat(d.getString("o")).isEqualTo("594.0000");
        assertThat(d.getString("h")).isEqualTo("594.5000");
        assertThat(d.getString("l")).isEqualTo("593.7500");
        assertThat(d.getString("c")).isEqualTo("594.2500");

        // Round-trip safety: the reader side rebuilds a BigDecimal with no precision loss.
        assertThat(new BigDecimal(d.getString("o"))).isEqualByComparingTo(open);
        assertThat(new BigDecimal(d.getString("c"))).isEqualByComparingTo(close);
    }

    @Test
    void toDocumentUsesProvidedTenantIdNotHardcoded() {
        Bar bar = new Bar(
                "SPY",
                Timeframe.DAILY,
                OPEN,
                CLOSE,
                new BigDecimal("594.00"),
                new BigDecimal("594.00"),
                new BigDecimal("594.00"),
                new BigDecimal("594.00"),
                0L,
                0L);

        Document d = MongoBarRepository.toDocument(bar, "tenant-xyz", INSERTED);

        assertThat(d.getString("tenantId")).isEqualTo("tenant-xyz");
    }
}
