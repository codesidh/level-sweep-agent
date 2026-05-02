package com.levelsweep.execution.persistence;

import com.levelsweep.shared.domain.trade.TradeStopTriggered;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only writer for the {@code stop_breach_audit} table per ADR-0005 §3.
 * One row per firing §9 stop trigger. Operators query this table to
 * reconstruct stop-out history for a session — the replay-parity harness
 * also asserts byte-equality on it.
 *
 * <p>Pure JDBC over the Quarkus MS SQL DataSource — same pattern as
 * {@link EodFlattenAuditRepository}. The package-private SQL-binding helper
 * is unit-tested without a real DB by mocking {@link PreparedStatement}.
 *
 * <p>{@code Instance<DataSource>} wrap exists so the {@code %test} profile
 * (which disables datasource devservices) doesn't break SmokeTest at boot.
 * When Quarkus binds a real DataSource — prod and dev profiles — the audit
 * repository persists; in test profile it gracefully no-ops with a single
 * WARN. The {@link com.levelsweep.execution.stopwatch.StopWatcherService}
 * also wraps audit calls in try/catch so a transient DB error does not stop
 * the trigger event from firing.
 */
@ApplicationScoped
public class StopAuditRepository {

    private static final Logger LOG = LoggerFactory.getLogger(StopAuditRepository.class);

    static final String INSERT_SQL = "INSERT INTO stop_breach_audit"
            + " (tenant_id, trade_id, alpaca_order_id, contract_symbol,"
            + " bar_timestamp, bar_close, stop_reference, triggered_at, correlation_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?)";

    private final Instance<DataSource> dataSourceInstance;

    @Inject
    public StopAuditRepository(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    /**
     * Append one audit row. Idempotency is the caller's responsibility — the
     * trigger event itself is immutable per (tenantId, tradeId, barTimestamp)
     * tuple, so a duplicate from a re-fired pipeline is acceptable (operators
     * query by max {@code triggered_at} or filter by the bar timestamp).
     */
    public void record(TradeStopTriggered event) {
        if (dataSourceInstance.isUnsatisfied()) {
            LOG.warn(
                    "stop audit repository running in stub mode — no DataSource; tenantId={} tradeId={} stopReference={}",
                    event.tenantId(),
                    event.tradeId(),
                    event.stopReference());
            return;
        }
        DataSource dataSource = dataSourceInstance.get();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            bindInsertParams(ps, event);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn(
                    "stop_breach_audit insert failed tenantId={} tradeId={} stopReference={}: {}",
                    event.tenantId(),
                    event.tradeId(),
                    event.stopReference(),
                    e.toString());
            throw new RuntimeException("stop_breach_audit insert failed", e);
        }
    }

    /** Package-private: extracted for unit tests of parameter binding. */
    static void bindInsertParams(PreparedStatement ps, TradeStopTriggered e) throws SQLException {
        ps.setString(1, e.tenantId());
        ps.setString(2, e.tradeId());
        ps.setString(3, e.alpacaOrderId());
        ps.setString(4, e.contractSymbol());
        ps.setTimestamp(5, Timestamp.from(e.barTimestamp()));
        ps.setBigDecimal(6, e.barClose());
        ps.setString(7, e.stopReference());
        ps.setTimestamp(8, Timestamp.from(e.triggeredAt()));
        ps.setString(9, e.correlationId());
    }
}
