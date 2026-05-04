package com.levelsweep.aiagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantConversationTest {

    private static final Instant NOW = Instant.parse("2026-05-04T13:30:00Z");

    @Test
    void recordRequiresAllFields() {
        assertThatThrownBy(() -> new AssistantConversation(null, "id", NOW, NOW, List.of(), BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssistantConversation("OWNER", null, NOW, NOW, List.of(), BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssistantConversation("OWNER", "id", null, NOW, List.of(), BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssistantConversation("OWNER", "id", NOW, null, List.of(), BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssistantConversation("OWNER", "id", NOW, NOW, null, BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssistantConversation("OWNER", "id", NOW, NOW, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankIdsAndNegativeCost() {
        assertThatThrownBy(() -> new AssistantConversation("", "id", NOW, NOW, List.of(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantConversation("OWNER", "  ", NOW, NOW, List.of(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantConversation("OWNER", "id", NOW, NOW, List.of(), new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void turnsListIsImmutableCopy() {
        List<AssistantTurn> mutable = new ArrayList<>();
        mutable.add(AssistantTurn.user("hi", NOW));
        AssistantConversation conv = new AssistantConversation("OWNER", "id", NOW, NOW, mutable, BigDecimal.ZERO);
        // Mutate the source list — the record's view must not change.
        mutable.add(AssistantTurn.assistant("hello", NOW, BigDecimal.ZERO));
        assertThat(conv.turns()).hasSize(1);
        assertThatThrownBy(() -> conv.turns().add(AssistantTurn.user("x", NOW)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void turnRoleIsConstrainedToUserOrAssistant() {
        assertThatThrownBy(() -> new AssistantTurn("system", "x", NOW, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantTurn("USER", "x", NOW, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AssistantTurn.user("hi", NOW).role()).isEqualTo("user");
        assertThat(AssistantTurn.assistant("hello", NOW, BigDecimal.ZERO).role())
                .isEqualTo("assistant");
    }

    @Test
    void turnContentMustNotBeBlank() {
        assertThatThrownBy(() -> new AssistantTurn("user", "", NOW, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantTurn("user", "  ", NOW, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void turnCostMustBeNonNegative() {
        assertThatThrownBy(() -> new AssistantTurn("assistant", "hi", NOW, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
