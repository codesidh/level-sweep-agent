package com.levelsweep.shared.fsm;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * The append-only record persisted to the {@code fsm_transitions} table per
 * architecture-spec §13.1. Carries both the audit metadata (who, when, which FSM
 * instance) and the {@link #fsmVersion} so replay-from-history can refuse to drive an
 * older table through a newer FSM (or vice versa) instead of silently diverging.
 *
 * <p>{@code fromState} is empty for the seed transition (initial state install). All
 * other fields are mandatory.
 *
 * @param <S> state type
 * @param <E> event type
 */
public record FsmTransition<S, E>(
        String tenantId,
        LocalDate sessionDate,
        String fsmKind,
        String fsmId,
        int fsmVersion,
        Optional<S> fromState,
        S toState,
        E event,
        Instant occurredAt,
        Optional<String> payloadJson,
        Optional<String> correlationId) {

    public FsmTransition {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(fsmKind, "fsmKind");
        Objects.requireNonNull(fsmId, "fsmId");
        Objects.requireNonNull(fromState, "fromState");
        Objects.requireNonNull(toState, "toState");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(correlationId, "correlationId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId blank");
        }
        if (fsmKind.isBlank()) {
            throw new IllegalArgumentException("fsmKind blank");
        }
        if (fsmId.isBlank()) {
            throw new IllegalArgumentException("fsmId blank");
        }
        if (fsmVersion < 1) {
            throw new IllegalArgumentException("fsmVersion must be >= 1, got " + fsmVersion);
        }
    }
}
