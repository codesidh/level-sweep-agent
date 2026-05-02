package com.levelsweep.aiagent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Validation tests for {@link ConfigProposal}. Phase A: proposals are
 * advisory; the persisted reports always carry an empty list, but the type
 * is exercised so the Phase B unlock is a wiring change rather than a record
 * change.
 */
class ConfigProposalTest {

    @Test
    void buildsWithAllFieldsPresent() {
        ConfigProposal p = new ConfigProposal(
                "raise EMA48 stop tolerance from 0.10 to 0.15 ATR",
                "two days of false stops in the prior 5",
                ConfigProposal.Urgency.LOW);
        assertThat(p.changeSpec()).isEqualTo("raise EMA48 stop tolerance from 0.10 to 0.15 ATR");
        assertThat(p.rationale()).isEqualTo("two days of false stops in the prior 5");
        assertThat(p.urgency()).isEqualTo(ConfigProposal.Urgency.LOW);
    }

    @Test
    void rejectsBlankChangeSpec() {
        assertThatThrownBy(() -> new ConfigProposal("", "rationale", ConfigProposal.Urgency.LOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changeSpec");
    }

    @Test
    void rejectsBlankRationale() {
        assertThatThrownBy(() -> new ConfigProposal("change", "   ", ConfigProposal.Urgency.LOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rationale");
    }

    @Test
    void rejectsNullUrgency() {
        assertThatThrownBy(() -> new ConfigProposal("change", "rationale", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("urgency");
    }

    @Test
    void allUrgencyValuesAccepted() {
        for (ConfigProposal.Urgency u : ConfigProposal.Urgency.values()) {
            ConfigProposal p = new ConfigProposal("change", "rationale", u);
            assertThat(p.urgency()).isEqualTo(u);
        }
    }
}
