package com.levelsweep.execution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.levelsweep.shared.domain.trade.EodFlattenAttempt;
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
 * Unit-style tests for {@link EodFlattenAuditRepository}. Mirrors
 * {@code DailyRiskStateRepositoryTest} / {@code LevelsRepositoryTest} —
 * assert SQL shape and prepared-statement parameter binding via Mockito. The
 * live INSERT against MS SQL is exercised under the operational integration
 * track once Testcontainers is wired in.
 */
class EodFlattenAuditRepositoryTest {

    private static final String TENANT = "OWNER";
    private static final String TRADE_ID = "trade-abc";
    private static final String CONTRACT = "SPY260430C00595000";
    private static final LocalDate SESSION = LocalDate.of(2026, 4, 30);
    private static final Instant NOW = Instant.parse("2026-04-30T19:55:00Z");

    @Test
    void insertSqlMatchesEodFlattenAttemptsColumns() {
        String sql = EodFlattenAuditRepository.INSERT_SQL;

        assertThat(sql).startsWithIgnoringCase("INSERT INTO eod_flatten_attempts");
        assertThat(sql).contains("tenant_id");
        assertThat(sql).contains("session_date");
        assertThat(sql).contains("trade_id");
        assertThat(sql).contains("contract_symbol");
        assertThat(sql).contains("attempted_at");
        assertThat(sql).contains("outcome");
        assertThat(sql).contains("alpaca_order_id");
        assertThat(sql).contains("failure_reason");
        assertThat(sql).contains("VALUES (?,?,?,?,?,?,?,?)");
    }

    @Test
    void bindInsertParamsBindsAllEightColumnsForFlattened() throws Exception {
        EodFlattenAttempt a = new EodFlattenAttempt(
                TENANT,
                SESSION,
                NOW,
                TRADE_ID,
                CONTRACT,
                EodFlattenAttempt.Outcome.FLATTENED,
                Optional.of("alpaca-order-xyz"),
                Optional.empty());
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        EodFlattenAuditRepository.bindInsertParams(ps, a);

        verify(ps, times(1)).setString(1, TENANT);
        verify(ps, times(1)).setDate(2, Date.valueOf(SESSION));
        verify(ps, times(1)).setString(3, TRADE_ID);
        verify(ps, times(1)).setString(4, CONTRACT);
        verify(ps, times(1)).setTimestamp(5, Timestamp.from(NOW));
        verify(ps, times(1)).setString(6, "FLATTENED");
        verify(ps, times(1)).setString(7, "alpaca-order-xyz");
        verify(ps, times(1)).setNull(8, Types.VARCHAR);
        Mockito.verifyNoMoreInteractions(ps);
    }

    @Test
    void bindInsertParamsBindsFailureReasonAndNullsAlpacaId() throws Exception {
        EodFlattenAttempt a = new EodFlattenAttempt(
                TENANT,
                SESSION,
                NOW,
                TRADE_ID,
                CONTRACT,
                EodFlattenAttempt.Outcome.FAILED,
                Optional.empty(),
                Optional.of("broker timeout after 10s"));
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        EodFlattenAuditRepository.bindInsertParams(ps, a);

        verify(ps, times(1)).setString(6, "FAILED");
        verify(ps, times(1)).setNull(7, Types.VARCHAR);
        verify(ps, times(1)).setString(8, "broker timeout after 10s");
    }

    @Test
    void bindInsertParamsBindsNoOpOutcome() throws Exception {
        EodFlattenAttempt a = new EodFlattenAttempt(
                TENANT,
                SESSION,
                NOW,
                TRADE_ID,
                CONTRACT,
                EodFlattenAttempt.Outcome.NO_OP,
                Optional.empty(),
                Optional.empty());
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        EodFlattenAuditRepository.bindInsertParams(ps, a);

        verify(ps, times(1)).setString(6, "NO_OP");
        verify(ps, times(1)).setNull(7, Types.VARCHAR);
        verify(ps, times(1)).setNull(8, Types.VARCHAR);
    }

    @Test
    void outcomeConstantsAreCanonical() {
        assertThat(EodFlattenAttempt.Outcome.FLATTENED).isEqualTo("FLATTENED");
        assertThat(EodFlattenAttempt.Outcome.NO_OP).isEqualTo("NO_OP");
        assertThat(EodFlattenAttempt.Outcome.FAILED).isEqualTo("FAILED");
    }
}
