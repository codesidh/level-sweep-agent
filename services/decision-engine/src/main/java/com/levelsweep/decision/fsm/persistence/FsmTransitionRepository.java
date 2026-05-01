package com.levelsweep.decision.fsm.persistence;

import com.levelsweep.shared.fsm.FsmTransition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only writer for the {@code fsm_transitions} table per architecture-spec
 * §13.1. Pure JDBC over the Quarkus MS SQL DataSource — same pattern as
 * {@code LevelsRepository} in the market-data service.
 *
 * <p>States and events are persisted as their {@code Object.toString()} form so the
 * table schema is FSM-agnostic. The {@code fsm_kind} discriminator + {@code
 * fsm_version} on each row let a replay harness pick the right deserializer.
 */
@ApplicationScoped
public class FsmTransitionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FsmTransitionRepository.class);

    static final String INSERT_SQL = "INSERT INTO fsm_transitions"
            + " (tenant_id, session_date, fsm_kind, fsm_id, fsm_version,"
            + " from_state, to_state, event, occurred_at, payload_json, correlation_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";

    static final String SELECT_BY_FSM_SQL = "SELECT id, tenant_id, session_date, fsm_kind, fsm_id,"
            + " fsm_version, from_state, to_state, event, occurred_at, payload_json, correlation_id"
            + " FROM fsm_transitions"
            + " WHERE fsm_kind = ? AND fsm_id = ?"
            + " ORDER BY occurred_at ASC, id ASC";

    private final DataSource dataSource;

    @Inject
    public FsmTransitionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Append one transition row. Idempotency is the caller's responsibility. */
    public void record(FsmTransition<?, ?> transition) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            bindInsertParams(ps, transition);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn(
                    "fsm_transitions insert failed tenantId={} fsmKind={} fsmId={} event={}: {}",
                    transition.tenantId(),
                    transition.fsmKind(),
                    transition.fsmId(),
                    transition.event(),
                    e.toString());
            throw new RuntimeException("fsm_transitions insert failed", e);
        }
    }

    /** Read all transitions for a single FSM instance, in occurrence order. */
    public List<FsmTransitionRow> findByFsm(String fsmKind, String fsmId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT_BY_FSM_SQL)) {
            ps.setString(1, fsmKind);
            ps.setString(2, fsmId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FsmTransitionRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            LOG.warn("fsm_transitions read failed fsmKind={} fsmId={}: {}", fsmKind, fsmId, e.toString());
            throw new RuntimeException("fsm_transitions read failed", e);
        }
    }

    /** Package-private: extracted for unit tests of parameter binding. */
    static void bindInsertParams(PreparedStatement ps, FsmTransition<?, ?> tr) throws SQLException {
        ps.setString(1, tr.tenantId());
        ps.setDate(2, Date.valueOf(tr.sessionDate()));
        ps.setString(3, tr.fsmKind());
        ps.setString(4, tr.fsmId());
        ps.setInt(5, tr.fsmVersion());
        if (tr.fromState().isPresent()) {
            ps.setString(6, tr.fromState().get().toString());
        } else {
            ps.setNull(6, Types.VARCHAR);
        }
        ps.setString(7, tr.toState().toString());
        ps.setString(8, tr.event().toString());
        ps.setTimestamp(9, Timestamp.from(tr.occurredAt()));
        if (tr.payloadJson().isPresent()) {
            ps.setString(10, tr.payloadJson().get());
        } else {
            ps.setNull(10, Types.NVARCHAR);
        }
        if (tr.correlationId().isPresent()) {
            ps.setString(11, tr.correlationId().get());
        } else {
            ps.setNull(11, Types.VARCHAR);
        }
    }

    /** Package-private: extracted for unit tests of row mapping. */
    static FsmTransitionRow mapRow(ResultSet rs) throws SQLException {
        Timestamp occurred = rs.getTimestamp("occurred_at");
        Instant occurredAt = occurred == null ? Instant.EPOCH : occurred.toInstant();
        return new FsmTransitionRow(
                rs.getLong("id"),
                rs.getString("tenant_id"),
                rs.getDate("session_date").toLocalDate(),
                rs.getString("fsm_kind"),
                rs.getString("fsm_id"),
                rs.getInt("fsm_version"),
                Optional.ofNullable(rs.getString("from_state")),
                rs.getString("to_state"),
                rs.getString("event"),
                occurredAt,
                Optional.ofNullable(rs.getString("payload_json")),
                Optional.ofNullable(rs.getString("correlation_id")));
    }
}
