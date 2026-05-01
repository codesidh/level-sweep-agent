package com.levelsweep.decision.risk.persistence;

import com.levelsweep.shared.domain.risk.DailyRiskState;
import com.levelsweep.shared.domain.risk.RiskEvent;
import com.levelsweep.shared.domain.risk.RiskState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MS SQL persistence for the Risk FSM (architecture-spec §13.1). Two tables
 * are touched here:
 *
 * <ul>
 *   <li>{@code daily_state} — extended in {@code V103} with risk columns; this
 *       repository's MERGE statement owns those columns. The pre-existing
 *       {@code LevelsRepository.upsert} (market-data-service) writes the four
 *       reference levels and relies on the V103 column defaults — the two
 *       repositories therefore coexist on the same row without trampling each
 *       other's columns.
 *   <li>{@code risk_events} — append-only log per {@link RiskEvent}.
 * </ul>
 *
 * <p>JDBC pattern mirrors {@code LevelsRepository} for consistency: try-with-
 * resources on {@code Connection} + {@code PreparedStatement}, parameter
 * binding extracted into a package-private helper for unit-testing without a
 * live DB.
 */
@ApplicationScoped
public class DailyRiskStateRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DailyRiskStateRepository.class);

    static final String UPSERT_SQL = "MERGE daily_state AS tgt"
            + " USING (VALUES(?,?,?,?,?,?,?,?,?,?)) AS src(tenant_id,session_date,starting_equity,"
            + "daily_loss_budget,realized_loss,trades_taken,risk_state,halted_at,halt_reason,symbol)"
            + " ON tgt.tenant_id = src.tenant_id AND tgt.session_date = src.session_date"
            + " WHEN MATCHED THEN UPDATE SET starting_equity=src.starting_equity,"
            + " daily_loss_budget=src.daily_loss_budget, realized_loss=src.realized_loss,"
            + " trades_taken=src.trades_taken, risk_state=src.risk_state, halted_at=src.halted_at,"
            + " halt_reason=src.halt_reason, updated_at=SYSUTCDATETIME()"
            + " WHEN NOT MATCHED THEN INSERT (tenant_id, session_date, symbol, starting_equity,"
            + " daily_loss_budget, realized_loss, trades_taken, risk_state, halted_at, halt_reason,"
            + " created_at, updated_at) VALUES (src.tenant_id, src.session_date, src.symbol,"
            + " src.starting_equity, src.daily_loss_budget, src.realized_loss, src.trades_taken,"
            + " src.risk_state, src.halted_at, src.halt_reason, SYSUTCDATETIME(), SYSUTCDATETIME());";

    static final String SELECT_SQL = "SELECT tenant_id, session_date, starting_equity,"
            + " daily_loss_budget, realized_loss, trades_taken, risk_state, halted_at, halt_reason"
            + " FROM daily_state WHERE tenant_id = ? AND session_date = ?";

    static final String INSERT_EVENT_SQL = "INSERT INTO risk_events"
            + " (tenant_id, session_date, occurred_at, event_type, from_state, to_state,"
            + " delta_amount, cumulative_loss, reason)"
            + " VALUES (?,?,?,?,?,?,?,?,?)";

    /**
     * Placeholder symbol stored on the daily_state row when the Risk repository
     * inserts before the LevelsRepository does. The {@code symbol} column is
     * NOT NULL but logically belongs to the levels writer; if no row exists
     * yet, we seed a placeholder that the levels-side update (or operators)
     * can overwrite.
     */
    private static final String PLACEHOLDER_SYMBOL = "RISK";

    private final DataSource dataSource;

    @Inject
    public DailyRiskStateRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Read the risk-side projection of daily_state for {@code (tenantId, sessionDate)}. */
    public Optional<DailyRiskState> find(String tenantId, LocalDate sessionDate) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT_SQL)) {
            ps.setString(1, tenantId);
            ps.setDate(2, Date.valueOf(sessionDate));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.warn(
                    "daily_state risk read failed tenantId={} sessionDate={}: {}", tenantId, sessionDate, e.toString());
            throw new RuntimeException("daily_state risk read failed", e);
        }
    }

    /** Upsert the risk-side projection of daily_state. Idempotent on (tenantId, sessionDate). */
    public void upsert(DailyRiskState state) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(UPSERT_SQL)) {
            bindUpsertParams(ps, state, PLACEHOLDER_SYMBOL);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn(
                    "daily_state risk upsert failed tenantId={} sessionDate={}: {}",
                    state.tenantId(),
                    state.sessionDate(),
                    e.toString());
            throw new RuntimeException("daily_state risk upsert failed", e);
        }
    }

    /** Append a single risk event. */
    public void recordEvent(RiskEvent event) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(INSERT_EVENT_SQL)) {
            bindEventParams(ps, event);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn(
                    "risk_events insert failed tenantId={} sessionDate={} type={}: {}",
                    event.tenantId(),
                    event.sessionDate(),
                    event.type(),
                    e.toString());
            throw new RuntimeException("risk_events insert failed", e);
        }
    }

    // --- package-private SQL-binding helpers (unit-testable without a DB) ---

    static void bindUpsertParams(PreparedStatement ps, DailyRiskState state, String symbol) throws SQLException {
        ps.setString(1, state.tenantId());
        ps.setDate(2, Date.valueOf(state.sessionDate()));
        ps.setBigDecimal(3, state.startingEquity());
        ps.setBigDecimal(4, state.dailyLossBudget());
        ps.setBigDecimal(5, state.realizedLoss());
        ps.setInt(6, state.tradesTaken());
        ps.setString(7, state.state().name());
        if (state.haltedAt().isPresent()) {
            ps.setTimestamp(8, Timestamp.from(state.haltedAt().get()));
        } else {
            ps.setNull(8, Types.TIMESTAMP);
        }
        if (state.haltReason().isPresent()) {
            ps.setString(9, state.haltReason().get());
        } else {
            ps.setNull(9, Types.VARCHAR);
        }
        ps.setString(10, symbol);
    }

    static void bindEventParams(PreparedStatement ps, RiskEvent event) throws SQLException {
        ps.setString(1, event.tenantId());
        ps.setDate(2, Date.valueOf(event.sessionDate()));
        ps.setTimestamp(3, Timestamp.from(event.occurredAt()));
        ps.setString(4, event.type().name());
        if (event.fromState().isPresent()) {
            ps.setString(5, event.fromState().get().name());
        } else {
            ps.setNull(5, Types.VARCHAR);
        }
        if (event.toState().isPresent()) {
            ps.setString(6, event.toState().get().name());
        } else {
            ps.setNull(6, Types.VARCHAR);
        }
        setBigDecimalOrNull(ps, 7, event.deltaAmount().orElse(null));
        setBigDecimalOrNull(ps, 8, event.cumulativeLoss().orElse(null));
        ps.setString(9, event.reason());
    }

    private static void setBigDecimalOrNull(PreparedStatement ps, int idx, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.DECIMAL);
        } else {
            ps.setBigDecimal(idx, value);
        }
    }

    private static DailyRiskState mapRow(ResultSet rs) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        LocalDate sessionDate = rs.getDate("session_date").toLocalDate();
        BigDecimal startingEquity = rs.getBigDecimal("starting_equity");
        BigDecimal dailyLossBudget = rs.getBigDecimal("daily_loss_budget");
        BigDecimal realizedLoss = rs.getBigDecimal("realized_loss");
        int tradesTaken = rs.getInt("trades_taken");
        RiskState state = RiskState.valueOf(rs.getString("risk_state"));
        Timestamp haltedAtTs = rs.getTimestamp("halted_at");
        String haltReason = rs.getString("halt_reason");

        return new DailyRiskState(
                tenantId,
                sessionDate,
                startingEquity == null ? BigDecimal.ZERO : startingEquity,
                dailyLossBudget == null ? BigDecimal.ZERO : dailyLossBudget,
                realizedLoss == null ? BigDecimal.ZERO : realizedLoss,
                tradesTaken,
                state,
                haltedAtTs == null ? Optional.empty() : Optional.of(haltedAtTs.toInstant()),
                haltReason == null ? Optional.empty() : Optional.of(haltReason));
    }

    /** Visible for tests — placeholder symbol used when seeding a fresh row. */
    static String placeholderSymbol() {
        return PLACEHOLDER_SYMBOL;
    }
}
