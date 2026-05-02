package com.levelsweep.execution.persistence;

import com.levelsweep.shared.domain.trade.EodFlattenAttempt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only writer for the {@code eod_flatten_attempts} table per Phase 3
 * Step 6. One row per trade per session, regardless of outcome (FLATTENED /
 * NO_OP / FAILED) — operators query this table to confirm every position was
 * acknowledged before the 16:00 ET 0DTE auto-exercise window.
 *
 * <p>Pure JDBC over the Quarkus MS SQL DataSource — same pattern as
 * {@code DailyRiskStateRepository} and {@code FsmTransitionRepository} in
 * decision-engine. The package-private SQL-binding helper is unit-tested
 * without a real DB by mocking {@link PreparedStatement}.
 *
 * <p>This Instance&lt;&gt; wrap exists to break the merge-order coupling between
 * Phase 3 deployment readiness (real MS SQL DataSource) and CI testing (no
 * DataSource in {@code %test}). When Quarkus binds a real DataSource — prod
 * and dev environments per application.yml — the audit repository persists;
 * in unit/SmokeTest profile it gracefully no-ops. EodFlattenScheduler still
 * emits the FAILED audit row attempt to keep replay parity.
 */
@ApplicationScoped
public class EodFlattenAuditRepository {

    private static final Logger LOG = LoggerFactory.getLogger(EodFlattenAuditRepository.class);

    static final String INSERT_SQL = "INSERT INTO eod_flatten_attempts"
            + " (tenant_id, session_date, trade_id, contract_symbol, attempted_at,"
            + " outcome, alpaca_order_id, failure_reason)"
            + " VALUES (?,?,?,?,?,?,?,?)";

    private final Instance<DataSource> dataSourceInstance;

    @Inject
    public EodFlattenAuditRepository(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    /**
     * Append one audit row. Idempotency is the caller's responsibility — the
     * EOD saga's deterministic {@code clientOrderId} provides broker-side
     * idempotency, and a duplicate audit row from a re-fired cron is
     * acceptable (operators query by max {@code attempted_at}).
     *
     * <p>If the {@link DataSource} bean is unresolvable (the {@code %test}
     * profile disables datasource devservices, so no DataSource exists), this
     * method logs a single WARN and returns without throwing. The
     * EodFlattenScheduler treats the audit write as best-effort regardless —
     * see its {@code recordAudit} swallow-and-log block.
     */
    public void record(EodFlattenAttempt attempt) {
        if (dataSourceInstance.isUnsatisfied()) {
            LOG.warn(
                    "eod audit repository running in stub mode — no DataSource; tenantId={} tradeId={} outcome={}",
                    attempt.tenantId(),
                    attempt.tradeId(),
                    attempt.outcome());
            return;
        }
        DataSource dataSource = dataSourceInstance.get();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            bindInsertParams(ps, attempt);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn(
                    "eod_flatten_attempts insert failed tenantId={} tradeId={} sessionDate={} outcome={}: {}",
                    attempt.tenantId(),
                    attempt.tradeId(),
                    attempt.sessionDate(),
                    attempt.outcome(),
                    e.toString());
            throw new RuntimeException("eod_flatten_attempts insert failed", e);
        }
    }

    /** Package-private: extracted for unit tests of parameter binding. */
    static void bindInsertParams(PreparedStatement ps, EodFlattenAttempt a) throws SQLException {
        ps.setString(1, a.tenantId());
        ps.setDate(2, Date.valueOf(a.sessionDate()));
        ps.setString(3, a.tradeId());
        ps.setString(4, a.contractSymbol());
        ps.setTimestamp(5, Timestamp.from(a.attemptedAt()));
        ps.setString(6, a.outcome());
        if (a.alpacaOrderId().isPresent()) {
            ps.setString(7, a.alpacaOrderId().get());
        } else {
            ps.setNull(7, Types.VARCHAR);
        }
        if (a.failureReason().isPresent()) {
            ps.setString(8, a.failureReason().get());
        } else {
            ps.setNull(8, Types.VARCHAR);
        }
    }
}
