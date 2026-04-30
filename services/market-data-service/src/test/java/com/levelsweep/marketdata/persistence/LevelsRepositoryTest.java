package com.levelsweep.marketdata.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.levelsweep.shared.domain.marketdata.Levels;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit-style tests for {@link LevelsRepository}. We assert the SQL string shape and
 * the prepared-statement parameter binding via Mockito — the live MERGE statement is
 * exercised in integration tests under the operational track (S7) once Testcontainers
 * is wired in.
 */
class LevelsRepositoryTest {

    @Test
    void upsertSqlContainsKeyMergeClauses() {
        // Sanity-check the MERGE structure so a refactor that breaks the join key or
        // forgets one of the matched-update columns surfaces here, not in production.
        String sql = LevelsRepository.UPSERT_SQL;

        assertThat(sql).startsWithIgnoringCase("MERGE daily_state");
        assertThat(sql).contains("USING (VALUES(?,?,?,?,?,?,?))");
        assertThat(sql).contains("ON tgt.tenant_id = src.tenant_id AND tgt.session_date = src.session_date");
        assertThat(sql).contains("WHEN MATCHED THEN UPDATE SET");
        assertThat(sql).contains("WHEN NOT MATCHED THEN INSERT");
        assertThat(sql).contains("updated_at=SYSUTCDATETIME()");
    }

    @Test
    void selectSqlMatchesByTenantAndSessionDate() {
        String sql = LevelsRepository.SELECT_SQL;

        assertThat(sql).startsWithIgnoringCase("SELECT");
        assertThat(sql).contains("FROM daily_state");
        assertThat(sql).contains("WHERE tenant_id = ? AND session_date = ?");
    }

    @Test
    void bindUpsertParamsBindsAllSevenColumns() throws Exception {
        Levels levels = new Levels(
                "OWNER",
                "SPY",
                LocalDate.of(2026, 4, 30),
                new BigDecimal("594.5000"),
                new BigDecimal("593.7500"),
                new BigDecimal("594.7500"),
                new BigDecimal("593.5000"));
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        LevelsRepository.bindUpsertParams(ps, levels);

        verify(ps, times(1)).setString(1, "OWNER");
        verify(ps, times(1)).setDate(2, Date.valueOf(LocalDate.of(2026, 4, 30)));
        verify(ps, times(1)).setString(3, "SPY");
        verify(ps, times(1)).setBigDecimal(4, new BigDecimal("594.5000"));
        verify(ps, times(1)).setBigDecimal(5, new BigDecimal("593.7500"));
        verify(ps, times(1)).setBigDecimal(6, new BigDecimal("594.7500"));
        verify(ps, times(1)).setBigDecimal(7, new BigDecimal("593.5000"));
        Mockito.verifyNoMoreInteractions(ps);
    }
}
