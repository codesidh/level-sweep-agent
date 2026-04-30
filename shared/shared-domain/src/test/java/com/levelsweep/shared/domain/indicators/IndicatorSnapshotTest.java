package com.levelsweep.shared.domain.indicators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IndicatorSnapshotTest {

    @Test
    void readinessFlagsReflectNullValues() {
        IndicatorSnapshot all =
                new IndicatorSnapshot(
                        "SPY",
                        Instant.now(),
                        BigDecimal.valueOf(594.0),
                        BigDecimal.valueOf(593.5),
                        BigDecimal.valueOf(593.0),
                        BigDecimal.valueOf(2.5));
        assertThat(all.emasReady()).isTrue();
        assertThat(all.fullyReady()).isTrue();

        IndicatorSnapshot emasOnly =
                new IndicatorSnapshot(
                        "SPY",
                        Instant.now(),
                        BigDecimal.valueOf(594.0),
                        BigDecimal.valueOf(593.5),
                        BigDecimal.valueOf(593.0),
                        null);
        assertThat(emasOnly.emasReady()).isTrue();
        assertThat(emasOnly.fullyReady()).isFalse();

        IndicatorSnapshot none = new IndicatorSnapshot("SPY", Instant.now(), null, null, null, null);
        assertThat(none.emasReady()).isFalse();
        assertThat(none.fullyReady()).isFalse();
    }

    @Test
    void requiresSymbolAndTimestamp() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new IndicatorSnapshot(null, now, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IndicatorSnapshot("SPY", null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
