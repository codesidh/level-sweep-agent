package com.levelsweep.decision.fsm.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 1:1 mapping of the {@code fsm_transitions} table per architecture-spec §13.1.
 * Read path returns these raw rows; the caller (typically a replay harness) chooses
 * which FSM to deserialize against because state/event values are stored as plain
 * strings here, not typed enums.
 */
public record FsmTransitionRow(
        long id,
        String tenantId,
        LocalDate sessionDate,
        String fsmKind,
        String fsmId,
        int fsmVersion,
        Optional<String> fromState,
        String toState,
        String event,
        Instant occurredAt,
        Optional<String> payloadJson,
        Optional<String> correlationId) {}
