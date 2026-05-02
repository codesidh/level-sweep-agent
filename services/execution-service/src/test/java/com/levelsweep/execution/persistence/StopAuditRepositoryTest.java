package com.levelsweep.execution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit-style tests for {@link StopAuditRepository}. Mirrors
 * {@link EodFlattenAuditRepositoryTest}: verify SQL shape + parameter binding
 * via Mockito; no real DB.
 */
class StopAuditRepositoryTest {

    private static final Instant BAR_TS = Instant.parse("2026-04-30T13:32:00Z");
    private static final Instant TRIGGERED_AT = Instant.parse("2026-04-30T13:32:00.250Z");

    private static TradeStopTriggered evt() {
        return new TradeStopTriggered(
                "OWNER",
                "trade-1",
                "alpaca-1",
                "SPY260430C00595000",
                BAR_TS,
                new BigDecimal("594.00"),
                "EMA13",
                TRIGGERED_AT,
                "corr-1");
    }

    @Test
    void insertSqlMatchesStopBreachAuditColumns() {
        String sql = StopAuditRepository.INSERT_SQL;
        assertThat(sql).startsWithIgnoringCase("INSERT INTO stop_breach_audit");
        assertThat(sql).contains("tenant_id");
        assertThat(sql).contains("trade_id");
        assertThat(sql).contains("alpaca_order_id");
        assertThat(sql).contains("contract_symbol");
        assertThat(sql).contains("bar_timestamp");
        assertThat(sql).contains("bar_close");
        assertThat(sql).contains("stop_reference");
        assertThat(sql).contains("triggered_at");
        assertThat(sql).contains("correlation_id");
        assertThat(sql).contains("VALUES (?,?,?,?,?,?,?,?,?)");
    }

    @Test
    void bindInsertParamsBindsAllNineColumns() throws Exception {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        StopAuditRepository.bindInsertParams(ps, evt());

        verify(ps, times(1)).setString(1, "OWNER");
        verify(ps, times(1)).setString(2, "trade-1");
        verify(ps, times(1)).setString(3, "alpaca-1");
        verify(ps, times(1)).setString(4, "SPY260430C00595000");
        verify(ps, times(1)).setTimestamp(5, Timestamp.from(BAR_TS));
        verify(ps, times(1)).setBigDecimal(6, new BigDecimal("594.00"));
        verify(ps, times(1)).setString(7, "EMA13");
        verify(ps, times(1)).setTimestamp(8, Timestamp.from(TRIGGERED_AT));
        verify(ps, times(1)).setString(9, "corr-1");
        Mockito.verifyNoMoreInteractions(ps);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordIsNoOpWhenDataSourceUnsatisfied() {
        Instance<DataSource> instance = Mockito.mock(Instance.class);
        Mockito.when(instance.isUnsatisfied()).thenReturn(true);
        StopAuditRepository repo = new StopAuditRepository(instance);

        // Must not throw.
        repo.record(evt());

        verify(instance, times(1)).isUnsatisfied();
        Mockito.verifyNoMoreInteractions(instance);
    }
}
