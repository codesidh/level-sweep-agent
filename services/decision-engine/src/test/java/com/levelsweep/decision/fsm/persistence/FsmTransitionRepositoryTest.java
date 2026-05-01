package com.levelsweep.decision.fsm.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.shared.fsm.FsmTransition;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link FsmTransitionRepository}. Asserts the SQL string
 * shape, the prepared-statement parameter binding, and the result-set row mapping
 * via JDK dynamic proxies — Mockito is not on this module's test classpath.
 *
 * <p>The live INSERT/SELECT round-trip is exercised in operational integration
 * tests once Testcontainers is wired in.
 */
class FsmTransitionRepositoryTest {

    private enum S {
        A,
        B
    }

    private enum E {
        GO
    }

    @Test
    void insertSqlMentionsAllElevenColumns() {
        String sql = FsmTransitionRepository.INSERT_SQL;

        assertThat(sql).startsWithIgnoringCase("INSERT INTO fsm_transitions");
        assertThat(sql)
                .contains(
                        "tenant_id",
                        "session_date",
                        "fsm_kind",
                        "fsm_id",
                        "fsm_version",
                        "from_state",
                        "to_state",
                        "event",
                        "occurred_at",
                        "payload_json",
                        "correlation_id");
        assertThat(sql).contains("VALUES (?,?,?,?,?,?,?,?,?,?,?)");
    }

    @Test
    void selectByFsmSqlOrdersByOccurredAtAndId() {
        String sql = FsmTransitionRepository.SELECT_BY_FSM_SQL;

        assertThat(sql).startsWithIgnoringCase("SELECT");
        assertThat(sql).contains("FROM fsm_transitions");
        assertThat(sql).contains("WHERE fsm_kind = ? AND fsm_id = ?");
        assertThat(sql).contains("ORDER BY occurred_at ASC, id ASC");
    }

    @Test
    void bindInsertParamsBindsAllElevenColumnsForFullTransition() throws Exception {
        Instant occurredAt = Instant.parse("2026-04-30T14:30:00Z");
        FsmTransition<S, E> tr = new FsmTransition<>(
                "OWNER",
                LocalDate.of(2026, 4, 30),
                "TEST",
                "fsm-1",
                7,
                Optional.of(S.A),
                S.B,
                E.GO,
                occurredAt,
                Optional.of("{\"foo\":1}"),
                Optional.of("corr-1"));
        RecordingHandler rec = new RecordingHandler();
        PreparedStatement ps = (PreparedStatement)
                Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, rec);

        FsmTransitionRepository.bindInsertParams(ps, tr);

        rec.assertSetString(1, "OWNER");
        rec.assertSet("setDate", 2, Date.valueOf(LocalDate.of(2026, 4, 30)));
        rec.assertSetString(3, "TEST");
        rec.assertSetString(4, "fsm-1");
        rec.assertSet("setInt", 5, 7);
        rec.assertSetString(6, "A");
        rec.assertSetString(7, "B");
        rec.assertSetString(8, "GO");
        rec.assertSet("setTimestamp", 9, Timestamp.from(occurredAt));
        rec.assertSetString(10, "{\"foo\":1}");
        rec.assertSetString(11, "corr-1");
        // No setNull calls expected on the all-present transition.
        assertThat(rec.invocations.stream().filter(i -> i.method.equals("setNull")))
                .as("no setNull invocations on full transition")
                .isEmpty();
    }

    @Test
    void bindInsertParamsHandlesEmptyOptionalsAsNulls() throws Exception {
        FsmTransition<S, E> seed = new FsmTransition<>(
                "OWNER",
                LocalDate.of(2026, 4, 30),
                "TEST",
                "fsm-1",
                1,
                Optional.empty(),
                S.A,
                E.GO,
                Instant.parse("2026-04-30T14:30:00Z"),
                Optional.empty(),
                Optional.empty());
        RecordingHandler rec = new RecordingHandler();
        PreparedStatement ps = (PreparedStatement)
                Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, rec);

        FsmTransitionRepository.bindInsertParams(ps, seed);

        // setNull calls on indices 6 (from_state), 10 (payload_json), 11 (correlation_id).
        assertThat(rec.setNullIndices()).containsExactlyInAnyOrder(6, 10, 11);
        rec.assertSetString(7, "A");
        rec.assertSetString(8, "GO");
    }

    @Test
    void mapRowReadsAllColumns() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("id", 42L);
        data.put("tenant_id", "OWNER");
        data.put("session_date", Date.valueOf(LocalDate.of(2026, 4, 30)));
        data.put("fsm_kind", "TRADE");
        data.put("fsm_id", "trade-uuid");
        data.put("fsm_version", 1);
        data.put("from_state", "PROPOSED");
        data.put("to_state", "ENTERED");
        data.put("event", "RISK_APPROVED");
        Timestamp ts = Timestamp.from(Instant.parse("2026-04-30T14:30:00Z"));
        data.put("occurred_at", ts);
        data.put("payload_json", null);
        data.put("correlation_id", "corr-1");
        ResultSet rs = stubResultSet(data);

        FsmTransitionRow row = FsmTransitionRepository.mapRow(rs);

        assertThat(row.id()).isEqualTo(42L);
        assertThat(row.tenantId()).isEqualTo("OWNER");
        assertThat(row.sessionDate()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(row.fsmKind()).isEqualTo("TRADE");
        assertThat(row.fsmId()).isEqualTo("trade-uuid");
        assertThat(row.fsmVersion()).isEqualTo(1);
        assertThat(row.fromState()).contains("PROPOSED");
        assertThat(row.toState()).isEqualTo("ENTERED");
        assertThat(row.event()).isEqualTo("RISK_APPROVED");
        assertThat(row.occurredAt()).isEqualTo(Instant.parse("2026-04-30T14:30:00Z"));
        assertThat(row.payloadJson()).isEmpty();
        assertThat(row.correlationId()).contains("corr-1");
    }

    @Test
    void mapRowHandlesNullFromStateAndOptionalsAsEmpty() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("id", 1L);
        data.put("tenant_id", "OWNER");
        data.put("session_date", Date.valueOf(LocalDate.of(2026, 4, 30)));
        data.put("fsm_kind", "SESSION");
        data.put("fsm_id", "2026-04-30");
        data.put("fsm_version", 1);
        data.put("from_state", null);
        data.put("to_state", "PRE_MARKET");
        data.put("event", "LEVELS_READY");
        data.put("occurred_at", Timestamp.from(Instant.EPOCH));
        data.put("payload_json", null);
        data.put("correlation_id", null);
        ResultSet rs = stubResultSet(data);

        FsmTransitionRow row = FsmTransitionRepository.mapRow(rs);

        assertThat(row.fromState()).isEmpty();
        assertThat(row.payloadJson()).isEmpty();
        assertThat(row.correlationId()).isEmpty();
    }

    @Test
    void mapRowReturnsEpochWhenOccurredAtIsNull() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("id", 1L);
        data.put("tenant_id", "OWNER");
        data.put("session_date", Date.valueOf(LocalDate.of(2026, 4, 30)));
        data.put("fsm_kind", "SESSION");
        data.put("fsm_id", "2026-04-30");
        data.put("fsm_version", 1);
        data.put("from_state", null);
        data.put("to_state", "PRE_MARKET");
        data.put("event", "LEVELS_READY");
        data.put("occurred_at", null);
        data.put("payload_json", null);
        data.put("correlation_id", null);
        ResultSet rs = stubResultSet(data);

        FsmTransitionRow row = FsmTransitionRepository.mapRow(rs);

        assertThat(row.occurredAt()).isEqualTo(Instant.EPOCH);
    }

    private static ResultSet stubResultSet(Map<String, Object> values) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (args != null && args.length == 1 && args[0] instanceof String columnLabel) {
                return switch (name) {
                    case "getString" -> values.get(columnLabel);
                    case "getLong" -> values.getOrDefault(columnLabel, 0L);
                    case "getInt" -> values.getOrDefault(columnLabel, 0);
                    case "getDate" -> values.get(columnLabel);
                    case "getTimestamp" -> values.get(columnLabel);
                    default -> defaultReturn(method);
                };
            }
            return defaultReturn(method);
        };
        return (ResultSet) Proxy.newProxyInstance(
                FsmTransitionRepositoryTest.class.getClassLoader(), new Class<?>[] {ResultSet.class}, h);
    }

    private static Object defaultReturn(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt == void.class) return null;
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    /**
     * Records every typed binding call against a {@link PreparedStatement} proxy.
     * Each call is captured as ({@code methodName}, {@code parameterIndex},
     * {@code value}) so the test can assert positional binding without needing
     * Mockito.
     */
    private static final class RecordingHandler implements InvocationHandler {

        record Invocation(String method, int index, Object value) {}

        final List<Invocation> invocations = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (args != null && args.length >= 2 && args[0] instanceof Integer idx) {
                invocations.add(new Invocation(name, idx, args[1]));
            }
            return defaultReturn(method);
        }

        void assertSetString(int index, String expected) {
            assertSet("setString", index, expected);
        }

        void assertSet(String method, int index, Object expected) {
            Optional<Invocation> match = invocations.stream()
                    .filter(i -> i.method.equals(method) && i.index == index)
                    .findFirst();
            assertThat(match)
                    .as("expected %s(%d, %s) — recorded: %s", method, index, expected, invocations)
                    .isPresent();
            assertThat(match.get().value)
                    .as("%s argument at index %d", method, index)
                    .isEqualTo(expected);
        }

        List<Integer> setNullIndices() {
            return invocations.stream()
                    .filter(i -> i.method.equals("setNull"))
                    .map(i -> i.index)
                    .sorted()
                    .toList();
        }
    }
}
