package com.levelsweep.marketdata.persistence;

import com.levelsweep.shared.domain.marketdata.Levels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MS SQL persistence for the daily reference levels (PDH/PDL/PMH/PML) per
 * architecture-spec §13.1. One row per {@code (tenant_id, session_date)}; upserts via
 * the SQL Server {@code MERGE} statement.
 *
 * <p>Phase 1 ships the writer scaffolding only — no live caller invokes
 * {@link #upsert(Levels)} yet. S5 wires the {@code LevelCalculator} → repository call
 * once the live RTH/overnight bar feeds drive level computation.
 */
@ApplicationScoped
public class LevelsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(LevelsRepository.class);

    static final String UPSERT_SQL = "MERGE daily_state AS tgt"
            + " USING (VALUES(?,?,?,?,?,?,?)) AS src(tenant_id,session_date,symbol,pdh,pdl,pmh,pml)"
            + " ON tgt.tenant_id = src.tenant_id AND tgt.session_date = src.session_date"
            + " WHEN MATCHED THEN UPDATE SET symbol=src.symbol, pdh=src.pdh, pdl=src.pdl,"
            + " pmh=src.pmh, pml=src.pml, updated_at=SYSUTCDATETIME()"
            + " WHEN NOT MATCHED THEN INSERT (tenant_id, session_date, symbol, pdh, pdl, pmh, pml,"
            + " created_at, updated_at) VALUES (src.tenant_id, src.session_date, src.symbol,"
            + " src.pdh, src.pdl, src.pmh, src.pml, SYSUTCDATETIME(), SYSUTCDATETIME());";

    static final String SELECT_SQL = "SELECT tenant_id, symbol, session_date, pdh, pdl, pmh, pml"
            + " FROM daily_state WHERE tenant_id = ? AND session_date = ?";

    private final DataSource dataSource;

    @Inject
    public LevelsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Upsert a {@link Levels} row. Idempotent on (tenantId, sessionDate). */
    public void upsert(Levels levels) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(UPSERT_SQL)) {
            bindUpsertParams(ps, levels);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn(
                    "daily_state upsert failed tenantId={} sessionDate={} symbol={}: {}",
                    levels.tenantId(),
                    levels.sessionDate(),
                    levels.symbol(),
                    e.toString());
            throw new RuntimeException("daily_state upsert failed", e);
        }
    }

    /** Read back the row for a given (tenantId, sessionDate). */
    public Optional<Levels> findBySession(String tenantId, LocalDate sessionDate) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT_SQL)) {
            ps.setString(1, tenantId);
            ps.setDate(2, Date.valueOf(sessionDate));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Levels l = new Levels(
                        rs.getString("tenant_id"),
                        rs.getString("symbol"),
                        rs.getDate("session_date").toLocalDate(),
                        rs.getBigDecimal("pdh"),
                        rs.getBigDecimal("pdl"),
                        rs.getBigDecimal("pmh"),
                        rs.getBigDecimal("pml"));
                return Optional.of(l);
            }
        } catch (SQLException e) {
            LOG.warn("daily_state read failed tenantId={} sessionDate={}: {}", tenantId, sessionDate, e.toString());
            throw new RuntimeException("daily_state read failed", e);
        }
    }

    /** Package-private: extracted for unit tests of parameter binding. */
    static void bindUpsertParams(PreparedStatement ps, Levels levels) throws SQLException {
        ps.setString(1, levels.tenantId());
        ps.setDate(2, Date.valueOf(levels.sessionDate()));
        ps.setString(3, levels.symbol());
        setBigDecimalOrNull(ps, 4, levels.pdh());
        setBigDecimalOrNull(ps, 5, levels.pdl());
        setBigDecimalOrNull(ps, 6, levels.pmh());
        setBigDecimalOrNull(ps, 7, levels.pml());
    }

    private static void setBigDecimalOrNull(PreparedStatement ps, int idx, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.DECIMAL);
        } else {
            ps.setBigDecimal(idx, value);
        }
    }
}
