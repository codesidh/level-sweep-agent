package com.levelsweep.execution.persistence;

import com.levelsweep.shared.domain.trade.TradeTrailBreached;
import com.levelsweep.shared.domain.trade.TradeTrailRatcheted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only writer for the {@code trail_audit} table per ADR-0005 §4.
 * Two row kinds — {@code RATCHET} on every floor advance,
 * {@code EXIT} on the floor-breach trigger — share one schema. Operators
 * query this table to reconstruct trailing-stop history; the replay-parity
 * harness asserts byte-equality on it.
 *
 * <p>Pure JDBC over the Quarkus MS SQL DataSource. Mirrors
 * {@link EodFlattenAuditRepository}'s {@code Instance<DataSource>}
 * stub-when-unsatisfied pattern so SmokeTest in the {@code %test} profile
 * does not fail on missing DataSource.
 */
@ApplicationScoped
public class TrailAuditRepository {

    private static final Logger LOG = LoggerFactory.getLogger(TrailAuditRepository.class);

    static final String INSERT_SQL = "INSERT INTO trail_audit"
            + " (tenant_id, trade_id, contract_symbol, observed_at, outcome,"
            + " nbbo_mid, upl_pct, new_floor_pct, exit_floor_pct, correlation_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)";

    /** Outcome literal for a §10.2 floor-advance row. */
    public static final String OUTCOME_RATCHET = "RATCHET";

    /** Outcome literal for a §10.3 exit-trigger row. */
    public static final String OUTCOME_EXIT = "EXIT";

    private final Instance<DataSource> dataSourceInstance;

    @Inject
    public TrailAuditRepository(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    /**
     * Append a {@code RATCHET} row. {@code contractSymbol} is supplied
     * separately because the {@link TradeTrailRatcheted} audit-only event
     * deliberately omits it (the operational consumer doesn't need it; the
     * audit row does).
     */
    public void recordRatchet(TradeTrailRatcheted event, String contractSymbol) {
        if (dataSourceInstance.isUnsatisfied()) {
            LOG.warn(
                    "trail audit repository running in stub mode — no DataSource; tenantId={} tradeId={} outcome=RATCHET",
                    event.tenantId(),
                    event.tradeId());
            return;
        }
        DataSource dataSource = dataSourceInstance.get();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            bindRatchet(ps, event, contractSymbol);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn(
                    "trail_audit RATCHET insert failed tenantId={} tradeId={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    e.toString());
            throw new RuntimeException("trail_audit RATCHET insert failed", e);
        }
    }

    /** Append an {@code EXIT} row. */
    public void recordExit(TradeTrailBreached event) {
        if (dataSourceInstance.isUnsatisfied()) {
            LOG.warn(
                    "trail audit repository running in stub mode — no DataSource; tenantId={} tradeId={} outcome=EXIT",
                    event.tenantId(),
                    event.tradeId());
            return;
        }
        DataSource dataSource = dataSourceInstance.get();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            bindExit(ps, event);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn(
                    "trail_audit EXIT insert failed tenantId={} tradeId={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    e.toString());
            throw new RuntimeException("trail_audit EXIT insert failed", e);
        }
    }

    /** Package-private: extracted for unit tests of parameter binding. */
    static void bindRatchet(PreparedStatement ps, TradeTrailRatcheted e, String contractSymbol) throws SQLException {
        ps.setString(1, e.tenantId());
        ps.setString(2, e.tradeId());
        ps.setString(3, contractSymbol);
        ps.setTimestamp(4, Timestamp.from(e.observedAt()));
        ps.setString(5, OUTCOME_RATCHET);
        ps.setBigDecimal(6, e.nbboMid());
        ps.setBigDecimal(7, e.uplPct());
        ps.setBigDecimal(8, e.newFloorPct());
        ps.setNull(9, Types.DECIMAL); // exit_floor_pct null on RATCHET rows
        ps.setString(10, e.correlationId());
    }

    /** Package-private: extracted for unit tests of parameter binding. */
    static void bindExit(PreparedStatement ps, TradeTrailBreached e) throws SQLException {
        ps.setString(1, e.tenantId());
        ps.setString(2, e.tradeId());
        ps.setString(3, e.contractSymbol());
        ps.setTimestamp(4, Timestamp.from(e.observedAt()));
        ps.setString(5, OUTCOME_EXIT);
        ps.setBigDecimal(6, e.nbboMid());
        ps.setNull(7, Types.DECIMAL); // upl_pct null on EXIT rows
        ps.setNull(8, Types.DECIMAL); // new_floor_pct null on EXIT rows
        ps.setBigDecimal(9, e.exitFloorPct());
        ps.setString(10, e.correlationId());
    }
}
