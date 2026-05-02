package com.levelsweep.execution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import com.levelsweep.shared.domain.trade.TradeTrailRatcheted;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TrailAuditRepositoryTest {

    private static final Instant TS = Instant.parse("2026-04-30T15:00:00Z");

    @Test
    void insertSqlMatchesTrailAuditColumns() {
        String sql = TrailAuditRepository.INSERT_SQL;
        assertThat(sql).startsWithIgnoringCase("INSERT INTO trail_audit");
        assertThat(sql).contains("tenant_id");
        assertThat(sql).contains("trade_id");
        assertThat(sql).contains("contract_symbol");
        assertThat(sql).contains("observed_at");
        assertThat(sql).contains("outcome");
        assertThat(sql).contains("nbbo_mid");
        assertThat(sql).contains("upl_pct");
        assertThat(sql).contains("new_floor_pct");
        assertThat(sql).contains("exit_floor_pct");
        assertThat(sql).contains("correlation_id");
        assertThat(sql).contains("VALUES (?,?,?,?,?,?,?,?,?,?)");
    }

    @Test
    void bindRatchetBindsAllTenColumns() throws Exception {
        TradeTrailRatcheted evt = new TradeTrailRatcheted(
                "OWNER",
                "trade-1",
                TS,
                new BigDecimal("13.00"),
                new BigDecimal("0.30"),
                new BigDecimal("0.25"),
                "corr-1");
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        TrailAuditRepository.bindRatchet(ps, evt, "SPY260430C00595000");

        verify(ps, times(1)).setString(1, "OWNER");
        verify(ps, times(1)).setString(2, "trade-1");
        verify(ps, times(1)).setString(3, "SPY260430C00595000");
        verify(ps, times(1)).setTimestamp(4, Timestamp.from(TS));
        verify(ps, times(1)).setString(5, "RATCHET");
        verify(ps, times(1)).setBigDecimal(6, new BigDecimal("13.00"));
        verify(ps, times(1)).setBigDecimal(7, new BigDecimal("0.30"));
        verify(ps, times(1)).setBigDecimal(8, new BigDecimal("0.25"));
        verify(ps, times(1)).setNull(9, Types.DECIMAL);
        verify(ps, times(1)).setString(10, "corr-1");
        Mockito.verifyNoMoreInteractions(ps);
    }

    @Test
    void bindExitBindsAllTenColumns() throws Exception {
        TradeTrailBreached evt = new TradeTrailBreached(
                "OWNER",
                "trade-1",
                "SPY260430C00595000",
                TS,
                new BigDecimal("13.50"),
                new BigDecimal("0.35"),
                "corr-1");
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        TrailAuditRepository.bindExit(ps, evt);

        verify(ps, times(1)).setString(1, "OWNER");
        verify(ps, times(1)).setString(2, "trade-1");
        verify(ps, times(1)).setString(3, "SPY260430C00595000");
        verify(ps, times(1)).setTimestamp(4, Timestamp.from(TS));
        verify(ps, times(1)).setString(5, "EXIT");
        verify(ps, times(1)).setBigDecimal(6, new BigDecimal("13.50"));
        verify(ps, times(1)).setNull(7, Types.DECIMAL);
        verify(ps, times(1)).setNull(8, Types.DECIMAL);
        verify(ps, times(1)).setBigDecimal(9, new BigDecimal("0.35"));
        verify(ps, times(1)).setString(10, "corr-1");
        Mockito.verifyNoMoreInteractions(ps);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordRatchetIsNoOpWhenDataSourceUnsatisfied() {
        Instance<DataSource> instance = Mockito.mock(Instance.class);
        Mockito.when(instance.isUnsatisfied()).thenReturn(true);
        TrailAuditRepository repo = new TrailAuditRepository(instance);

        TradeTrailRatcheted evt = new TradeTrailRatcheted(
                "OWNER", "t1", TS, new BigDecimal("13.00"), new BigDecimal("0.30"), new BigDecimal("0.25"), "corr-1");

        repo.recordRatchet(evt, "SPY260430C00595000");

        verify(instance, times(1)).isUnsatisfied();
        Mockito.verifyNoMoreInteractions(instance);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordExitIsNoOpWhenDataSourceUnsatisfied() {
        Instance<DataSource> instance = Mockito.mock(Instance.class);
        Mockito.when(instance.isUnsatisfied()).thenReturn(true);
        TrailAuditRepository repo = new TrailAuditRepository(instance);

        TradeTrailBreached evt = new TradeTrailBreached(
                "OWNER", "t1", "SPY260430C00595000", TS, new BigDecimal("13.50"), new BigDecimal("0.35"), "corr-1");

        repo.recordExit(evt);

        verify(instance, times(1)).isUnsatisfied();
        Mockito.verifyNoMoreInteractions(instance);
    }
}
