package com.levelsweep.shared.fsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Validates the {@link FsmTransition} contract. The record's compact constructor is
 * the gatekeeper for blank/null fields and version sanity, so we test those gates
 * here rather than relying on each FSM author to re-validate at every call site.
 */
class FsmTransitionTest {

    private enum S {
        A,
        B
    }

    private enum E {
        GO
    }

    @Test
    void buildsAndExposesAllFields() {
        Instant t = Instant.parse("2026-04-30T13:30:00Z");
        FsmTransition<S, E> tr = new FsmTransition<>(
                "OWNER",
                LocalDate.of(2026, 4, 30),
                "TEST",
                "fsm-1",
                1,
                Optional.of(S.A),
                S.B,
                E.GO,
                t,
                Optional.empty(),
                Optional.of("corr-1"));

        assertThat(tr.tenantId()).isEqualTo("OWNER");
        assertThat(tr.fsmKind()).isEqualTo("TEST");
        assertThat(tr.fromState()).contains(S.A);
        assertThat(tr.toState()).isEqualTo(S.B);
        assertThat(tr.event()).isEqualTo(E.GO);
        assertThat(tr.fsmVersion()).isEqualTo(1);
        assertThat(tr.correlationId()).contains("corr-1");
        assertThat(tr.payloadJson()).isEmpty();
        assertThat(tr.occurredAt()).isEqualTo(t);
    }

    @Test
    void seedTransitionAllowsEmptyFromState() {
        FsmTransition<S, E> seed = new FsmTransition<>(
                "OWNER",
                LocalDate.of(2026, 4, 30),
                "TEST",
                "fsm-1",
                1,
                Optional.empty(),
                S.A,
                E.GO,
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty());

        assertThat(seed.fromState()).isEmpty();
    }

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> new FsmTransition<>(
                        "  ",
                        LocalDate.of(2026, 4, 30),
                        "TEST",
                        "fsm-1",
                        1,
                        Optional.empty(),
                        S.A,
                        E.GO,
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankFsmId() {
        assertThatThrownBy(() -> new FsmTransition<>(
                        "OWNER",
                        LocalDate.of(2026, 4, 30),
                        "TEST",
                        "",
                        1,
                        Optional.empty(),
                        S.A,
                        E.GO,
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroFsmVersion() {
        assertThatThrownBy(() -> new FsmTransition<>(
                        "OWNER",
                        LocalDate.of(2026, 4, 30),
                        "TEST",
                        "fsm-1",
                        0,
                        Optional.empty(),
                        S.A,
                        E.GO,
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fsmVersion");
    }

    @Test
    void rejectsNullToState() {
        assertThatThrownBy(() -> new FsmTransition<S, E>(
                        "OWNER",
                        LocalDate.of(2026, 4, 30),
                        "TEST",
                        "fsm-1",
                        1,
                        Optional.empty(),
                        null,
                        E.GO,
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }
}
