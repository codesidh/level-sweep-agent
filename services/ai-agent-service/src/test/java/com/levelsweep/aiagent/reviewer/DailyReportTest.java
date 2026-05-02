package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation tests for {@link DailyReport}. Asserts the record contract that
 * the {@link DailyReviewer} relies on:
 *
 * <ul>
 *   <li>Defensive copy of the {@code anomalies} and {@code proposals} lists
 *       so a downstream mutation cannot corrupt the audit trail.</li>
 *   <li>Negative cost / token rejection.</li>
 *   <li>Blank string rejection on the audit fields.</li>
 * </ul>
 */
class DailyReportTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 5, 2);
    private static final Instant GENERATED_AT = Instant.parse("2026-05-02T20:30:00Z");

    @Test
    void buildsWithAllFieldsPresent() {
        DailyReport r = sampleReport();
        assertThat(r.tenantId()).isEqualTo("OWNER");
        assertThat(r.sessionDate()).isEqualTo(SESSION);
        assertThat(r.outcome()).isEqualTo(DailyReport.Outcome.COMPLETED);
        assertThat(r.totalTokensUsed()).isEqualTo(11_500L);
        assertThat(r.costUsd()).isEqualByComparingTo(new BigDecimal("0.2625"));
    }

    @Test
    void anomaliesListIsDefensivelyCopied() {
        java.util.List<String> mutable = new java.util.ArrayList<>();
        mutable.add("anomaly: VIX rose");
        DailyReport r = new DailyReport(
                "OWNER",
                SESSION,
                "summary",
                mutable,
                List.of(),
                DailyReport.Outcome.COMPLETED,
                GENERATED_AT,
                "claude-opus-4-7",
                "h",
                100L,
                BigDecimal.ZERO);
        // Mutating the original list MUST NOT mutate the record.
        mutable.add("anomaly: tampered");
        assertThat(r.anomalies()).hasSize(1);
        assertThat(r.anomalies().get(0)).isEqualTo("anomaly: VIX rose");
    }

    @Test
    void proposalsListIsDefensivelyCopied() {
        java.util.List<ConfigProposal> mutable = new java.util.ArrayList<>();
        mutable.add(new ConfigProposal("change", "rationale", ConfigProposal.Urgency.LOW));
        DailyReport r = new DailyReport(
                "OWNER",
                SESSION,
                "summary",
                List.of(),
                mutable,
                DailyReport.Outcome.COMPLETED,
                GENERATED_AT,
                "claude-opus-4-7",
                "h",
                100L,
                BigDecimal.ZERO);
        mutable.add(new ConfigProposal("tampered", "rationale", ConfigProposal.Urgency.HIGH));
        assertThat(r.proposals()).hasSize(1);
    }

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> new DailyReport(
                        "",
                        SESSION,
                        "summary",
                        List.of(),
                        List.of(),
                        DailyReport.Outcome.COMPLETED,
                        GENERATED_AT,
                        "claude-opus-4-7",
                        "h",
                        100L,
                        BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsBlankPromptHash() {
        assertThatThrownBy(() -> new DailyReport(
                        "OWNER",
                        SESSION,
                        "summary",
                        List.of(),
                        List.of(),
                        DailyReport.Outcome.COMPLETED,
                        GENERATED_AT,
                        "claude-opus-4-7",
                        "",
                        100L,
                        BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptHash");
    }

    @Test
    void rejectsNegativeCostUsd() {
        assertThatThrownBy(() -> new DailyReport(
                        "OWNER",
                        SESSION,
                        "summary",
                        List.of(),
                        List.of(),
                        DailyReport.Outcome.COMPLETED,
                        GENERATED_AT,
                        "claude-opus-4-7",
                        "h",
                        100L,
                        new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("costUsd");
    }

    @Test
    void rejectsNegativeTotalTokens() {
        assertThatThrownBy(() -> new DailyReport(
                        "OWNER",
                        SESSION,
                        "summary",
                        List.of(),
                        List.of(),
                        DailyReport.Outcome.COMPLETED,
                        GENERATED_AT,
                        "claude-opus-4-7",
                        "h",
                        -1L,
                        BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalTokensUsed");
    }

    @Test
    void allOutcomesAccepted() {
        for (DailyReport.Outcome o : DailyReport.Outcome.values()) {
            DailyReport r = new DailyReport(
                    "OWNER",
                    SESSION,
                    "summary",
                    List.of(),
                    List.of(),
                    o,
                    GENERATED_AT,
                    "claude-opus-4-7",
                    "h",
                    100L,
                    BigDecimal.ZERO);
            assertThat(r.outcome()).isEqualTo(o);
        }
    }

    private static DailyReport sampleReport() {
        return new DailyReport(
                "OWNER",
                SESSION,
                "Today the strategy executed two trades.",
                List.of(),
                List.of(),
                DailyReport.Outcome.COMPLETED,
                GENERATED_AT,
                "claude-opus-4-7",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                11_500L,
                new BigDecimal("0.2625"));
    }
}
