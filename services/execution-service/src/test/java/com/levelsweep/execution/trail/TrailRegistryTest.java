package com.levelsweep.execution.trail;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TrailRegistryTest {

    private static TrailState state(String tradeId) {
        return new TrailState("OWNER", tradeId, "SPY260430C00595000", new BigDecimal("1.20"), 1, "corr-" + tradeId);
    }

    @Test
    void registerThenDeregister() {
        TrailRegistry reg = new TrailRegistry();
        reg.register(state("t1"));
        assertThat(reg.size()).isEqualTo(1);
        reg.deregister("t1");
        assertThat(reg.size()).isZero();
    }

    @Test
    void duplicateRegisterReplaces() {
        TrailRegistry reg = new TrailRegistry();
        reg.register(state("t1"));
        reg.register(state("t1"));
        assertThat(reg.size()).isEqualTo(1);
    }

    @Test
    void deregisterUnknownIsNoOp() {
        TrailRegistry reg = new TrailRegistry();
        reg.deregister("ghost"); // must not throw
        assertThat(reg.size()).isZero();
    }

    @Test
    void snapshotIsImmutable() {
        TrailRegistry reg = new TrailRegistry();
        reg.register(state("t1"));
        var snap = reg.snapshot();
        org.assertj.core.api.Assertions.assertThatThrownBy(snap::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
