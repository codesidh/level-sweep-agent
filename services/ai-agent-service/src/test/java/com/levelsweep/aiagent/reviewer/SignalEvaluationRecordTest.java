package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Validation tests for {@link SignalEvaluationRecord}. Phase 4 ships the type
 * with no live producer; these tests guarantee the contract for the
 * decision-engine producer wiring in Phase 5/6.
 */
class SignalEvaluationRecordTest {

    private static final Instant T = Instant.parse("2026-05-02T13:32:00Z");
    private static final LocalDate SESSION = LocalDate.of(2026, 5, 2);

    @Test
    void buildsWithAllFieldsPresent() {
        SignalEvaluationRecord r = new SignalEvaluationRecord(
                "OWNER",
                SESSION,
                "SIG_001",
                T,
                SignalEvaluationRecord.Side.CALL,
                SignalEvaluationRecord.LevelType.PDH,
                SignalEvaluationRecord.Outcome.TAKEN,
                "ema48_above",
                "corr-001");
        assertThat(r.tenantId()).isEqualTo("OWNER");
        assertThat(r.signalId()).isEqualTo("SIG_001");
        assertThat(r.side()).isEqualTo(SignalEvaluationRecord.Side.CALL);
        assertThat(r.levelType()).isEqualTo(SignalEvaluationRecord.LevelType.PDH);
        assertThat(r.outcome()).isEqualTo(SignalEvaluationRecord.Outcome.TAKEN);
    }

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> new SignalEvaluationRecord(
                        "",
                        SESSION,
                        "SIG_001",
                        T,
                        SignalEvaluationRecord.Side.CALL,
                        SignalEvaluationRecord.LevelType.PDH,
                        SignalEvaluationRecord.Outcome.TAKEN,
                        "ema48_above",
                        "corr-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsBlankSignalId() {
        assertThatThrownBy(() -> new SignalEvaluationRecord(
                        "OWNER",
                        SESSION,
                        "  ",
                        T,
                        SignalEvaluationRecord.Side.CALL,
                        SignalEvaluationRecord.LevelType.PDH,
                        SignalEvaluationRecord.Outcome.TAKEN,
                        "ema48_above",
                        "corr-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signalId");
    }

    @Test
    void allOutcomeValuesAccepted() {
        for (SignalEvaluationRecord.Outcome o : SignalEvaluationRecord.Outcome.values()) {
            SignalEvaluationRecord r = new SignalEvaluationRecord(
                    "OWNER",
                    SESSION,
                    "SIG_001",
                    T,
                    SignalEvaluationRecord.Side.CALL,
                    SignalEvaluationRecord.LevelType.PDH,
                    o,
                    "rc",
                    "corr-001");
            assertThat(r.outcome()).isEqualTo(o);
        }
    }

    @Test
    void allLevelTypesAccepted() {
        for (SignalEvaluationRecord.LevelType l : SignalEvaluationRecord.LevelType.values()) {
            SignalEvaluationRecord r = new SignalEvaluationRecord(
                    "OWNER",
                    SESSION,
                    "SIG_001",
                    T,
                    SignalEvaluationRecord.Side.CALL,
                    l,
                    SignalEvaluationRecord.Outcome.TAKEN,
                    "rc",
                    "corr-001");
            assertThat(r.levelType()).isEqualTo(l);
        }
    }
}
