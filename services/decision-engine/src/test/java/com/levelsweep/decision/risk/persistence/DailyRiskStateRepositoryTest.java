package com.levelsweep.decision.risk.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.levelsweep.shared.domain.risk.DailyRiskState;
import com.levelsweep.shared.domain.risk.RiskEvent;
import com.levelsweep.shared.domain.risk.RiskEventType;
import com.levelsweep.shared.domain.risk.RiskState;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit-style tests for {@link DailyRiskStateRepository}. Mirrors the
 * {@code LevelsRepositoryTest} pattern in market-data-service: assert the SQL
 * shape and the prepared-statement parameter binding via Mockito. The live
 * MERGE / INSERT statements are exercised in integration tests under the
 * operational track once Testcontainers is wired in.
 */
class DailyRiskStateRepositoryTest {

    private static final String TENANT = "OWNER";
    private static final LocalDate SESSION = LocalDate.of(2026, 4, 30);
    private static final Instant T0 = Instant.parse("2026-04-30T13:29:00Z");

    @Test
    void upsertSqlContainsKeyMergeClauses() {
        String sql = DailyRiskStateRepository.UPSERT_SQL;

        assertThat(sql).startsWithIgnoringCase("MERGE daily_state");
        assertThat(sql).contains("USING (VALUES(?,?,?,?,?,?,?,?,?,?))");
        assertThat(sql).contains("ON tgt.tenant_id = src.tenant_id AND tgt.session_date = src.session_date");
        assertThat(sql).contains("WHEN MATCHED THEN UPDATE SET");
        assertThat(sql).contains("starting_equity=src.starting_equity");
        assertThat(sql).contains("risk_state=src.risk_state");
        assertThat(sql).contains("WHEN NOT MATCHED THEN INSERT");
        assertThat(sql).contains("updated_at=SYSUTCDATETIME()");
    }

    @Test
    void selectSqlReadsRiskColumns() {
        String sql = DailyRiskStateRepository.SELECT_SQL;

        assertThat(sql).startsWithIgnoringCase("SELECT");
        assertThat(sql).contains("FROM daily_state");
        assertThat(sql).contains("WHERE tenant_id = ? AND session_date = ?");
        assertThat(sql).contains("starting_equity");
        assertThat(sql).contains("daily_loss_budget");
        assertThat(sql).contains("realized_loss");
        assertThat(sql).contains("trades_taken");
        assertThat(sql).contains("risk_state");
        assertThat(sql).contains("halted_at");
        assertThat(sql).contains("halt_reason");
    }

    @Test
    void insertEventSqlMatchesRiskEventsColumns() {
        String sql = DailyRiskStateRepository.INSERT_EVENT_SQL;

        assertThat(sql).startsWithIgnoringCase("INSERT INTO risk_events");
        assertThat(sql).contains("tenant_id");
        assertThat(sql).contains("session_date");
        assertThat(sql).contains("occurred_at");
        assertThat(sql).contains("event_type");
        assertThat(sql).contains("from_state");
        assertThat(sql).contains("to_state");
        assertThat(sql).contains("delta_amount");
        assertThat(sql).contains("cumulative_loss");
        assertThat(sql).contains("reason");
        assertThat(sql).contains("VALUES (?,?,?,?,?,?,?,?,?)");
    }

    @Test
    void bindUpsertParamsBindsAllTenColumnsForHealthy() throws Exception {
        DailyRiskState s = new DailyRiskState(
                TENANT,
                SESSION,
                new BigDecimal("5000.0000"),
                new BigDecimal("100.0000"),
                new BigDecimal("30.0000"),
                2,
                RiskState.HEALTHY,
                Optional.empty(),
                Optional.empty());
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        DailyRiskStateRepository.bindUpsertParams(ps, s, "RISK");

        verify(ps, times(1)).setString(1, TENANT);
        verify(ps, times(1)).setDate(2, Date.valueOf(SESSION));
        verify(ps, times(1)).setBigDecimal(3, new BigDecimal("5000.0000"));
        verify(ps, times(1)).setBigDecimal(4, new BigDecimal("100.0000"));
        verify(ps, times(1)).setBigDecimal(5, new BigDecimal("30.0000"));
        verify(ps, times(1)).setInt(6, 2);
        verify(ps, times(1)).setString(7, "HEALTHY");
        verify(ps, times(1)).setNull(8, Types.TIMESTAMP);
        verify(ps, times(1)).setNull(9, Types.VARCHAR);
        verify(ps, times(1)).setString(10, "RISK");
        Mockito.verifyNoMoreInteractions(ps);
    }

    @Test
    void bindUpsertParamsBindsHaltedFieldsWhenSet() throws Exception {
        DailyRiskState s = new DailyRiskState(
                TENANT,
                SESSION,
                new BigDecimal("5000.0000"),
                new BigDecimal("100.0000"),
                new BigDecimal("100.0000"),
                3,
                RiskState.HALTED,
                Optional.of(T0),
                Optional.of("BUDGET_EXHAUSTED"));
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        DailyRiskStateRepository.bindUpsertParams(ps, s, "RISK");

        verify(ps, times(1)).setString(7, "HALTED");
        verify(ps, times(1)).setTimestamp(8, Timestamp.from(T0));
        verify(ps, times(1)).setString(9, "BUDGET_EXHAUSTED");
    }

    @Test
    void bindEventParamsBindsAllNineColumnsForBudgetConsumed() throws Exception {
        RiskEvent event = new RiskEvent(
                TENANT,
                SESSION,
                T0,
                RiskEventType.BUDGET_CONSUMED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new BigDecimal("30.00")),
                Optional.of(new BigDecimal("30.00")),
                "BUDGET_CONSUMED");
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        DailyRiskStateRepository.bindEventParams(ps, event);

        verify(ps, times(1)).setString(1, TENANT);
        verify(ps, times(1)).setDate(2, Date.valueOf(SESSION));
        verify(ps, times(1)).setTimestamp(3, Timestamp.from(T0));
        verify(ps, times(1)).setString(4, "BUDGET_CONSUMED");
        verify(ps, times(1)).setNull(5, Types.VARCHAR);
        verify(ps, times(1)).setNull(6, Types.VARCHAR);
        verify(ps, times(1)).setBigDecimal(7, new BigDecimal("30.00"));
        verify(ps, times(1)).setBigDecimal(8, new BigDecimal("30.00"));
        verify(ps, times(1)).setString(9, "BUDGET_CONSUMED");
        Mockito.verifyNoMoreInteractions(ps);
    }

    @Test
    void bindEventParamsBindsTransitionStates() throws Exception {
        RiskEvent event = new RiskEvent(
                TENANT,
                SESSION,
                T0,
                RiskEventType.STATE_TRANSITION,
                Optional.of(RiskState.HEALTHY),
                Optional.of(RiskState.BUDGET_LOW),
                Optional.empty(),
                Optional.of(new BigDecimal("75.00")),
                "HEALTHY->BUDGET_LOW");
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        DailyRiskStateRepository.bindEventParams(ps, event);

        verify(ps, times(1)).setString(4, "STATE_TRANSITION");
        verify(ps, times(1)).setString(5, "HEALTHY");
        verify(ps, times(1)).setString(6, "BUDGET_LOW");
        verify(ps, times(1)).setNull(7, Types.DECIMAL);
        verify(ps, times(1)).setBigDecimal(8, new BigDecimal("75.00"));
    }

    @Test
    void placeholderSymbolIsExposedForCoverage() {
        assertThat(DailyRiskStateRepository.placeholderSymbol()).isEqualTo("RISK");
    }
}
