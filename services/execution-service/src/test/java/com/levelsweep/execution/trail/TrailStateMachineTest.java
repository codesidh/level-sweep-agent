package com.levelsweep.execution.trail;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pure FSM tests for {@link TrailStateMachine}, including the
 * {@code requirements.md §10.4} worked example reproduced byte-for-byte:
 *
 * <blockquote>Entry premium: $1000. At $1300 (+30%) → trail = $1250 (+25%).
 * If price advances to $1400 (+40%) → trail = $1350 (+35%). If price
 * retraces and crosses $1350 → exit booked at +35%.</blockquote>
 *
 * <p>The state machine uses contract premium (not dollar-equivalent) for
 * its math — the test scales {@code 1000 → 10.00} per contract (one
 * 100-share contract, $10.00 premium = $1,000 underlying) so the
 * percentages and floors remain identical to the §10.4 narrative.
 *
 * <p>Sustainment-3 default: each transition needs 3 consecutive snapshots
 * past the threshold. A single isolated snapshot never advances the FSM.
 */
class TrailStateMachineTest {

    private static final TrailConfig DEFAULT_CFG = TrailConfig.of(3, new BigDecimal("0.30"), new BigDecimal("0.05"));
    private static final BigDecimal ENTRY = new BigDecimal("10.00"); // $10.00/contract = $1000 in §10.4

    private static TrailState fresh() {
        return new TrailState("OWNER", "t1", "SPY260430C00595000", ENTRY, 1, "corr-1");
    }

    private static Instant ts(int n) {
        return Instant.parse("2026-04-30T15:00:00Z").plusSeconds(n);
    }

    // =========================== §10.4 worked example ===========================

    @Test
    void replaysSection10_4WorkedExampleByteForByte() {
        TrailState s = fresh();

        // Stage 1: NBBO mid jumps to 13.00 (+30%). Sustainment-3: 3 snapshots required.
        BigDecimal mid130 = new BigDecimal("13.00");
        TrailStateMachine.Decision d1 = TrailStateMachine.advance(s, mid130, ts(0), DEFAULT_CFG);
        assertThat(d1).isInstanceOf(TrailStateMachine.Decision.Inactive.class);
        TrailStateMachine.Decision d2 = TrailStateMachine.advance(s, mid130, ts(1), DEFAULT_CFG);
        assertThat(d2).isInstanceOf(TrailStateMachine.Decision.Inactive.class);
        TrailStateMachine.Decision d3 = TrailStateMachine.advance(s, mid130, ts(2), DEFAULT_CFG);
        assertThat(d3).isInstanceOf(TrailStateMachine.Decision.Armed.class);
        TrailStateMachine.Decision.Armed armed = (TrailStateMachine.Decision.Armed) d3;
        // Floor armed at +25% (activation 30% - ratchet 5%).
        assertThat(armed.newFloor()).isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(s.armed()).isTrue();
        assertThat(s.floor()).isEqualByComparingTo(new BigDecimal("0.25"));

        // Stage 2: NBBO mid advances to 13.50 (+35%). Need 3 snapshots → ratchet to floor=+30%.
        BigDecimal mid135 = new BigDecimal("13.50");
        TrailStateMachine.advance(s, mid135, ts(3), DEFAULT_CFG);
        TrailStateMachine.advance(s, mid135, ts(4), DEFAULT_CFG);
        TrailStateMachine.Decision d6 = TrailStateMachine.advance(s, mid135, ts(5), DEFAULT_CFG);
        assertThat(d6).isInstanceOf(TrailStateMachine.Decision.Ratcheted.class);
        TrailStateMachine.Decision.Ratcheted r1 = (TrailStateMachine.Decision.Ratcheted) d6;
        assertThat(r1.newFloor()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(s.floor()).isEqualByComparingTo(new BigDecimal("0.30"));

        // Stage 3: NBBO mid jumps to 14.00 (+40%). Three sustained → ratchet to floor=+35%.
        BigDecimal mid140 = new BigDecimal("14.00");
        TrailStateMachine.advance(s, mid140, ts(6), DEFAULT_CFG);
        TrailStateMachine.advance(s, mid140, ts(7), DEFAULT_CFG);
        TrailStateMachine.Decision d9 = TrailStateMachine.advance(s, mid140, ts(8), DEFAULT_CFG);
        assertThat(d9).isInstanceOf(TrailStateMachine.Decision.Ratcheted.class);
        TrailStateMachine.Decision.Ratcheted r2 = (TrailStateMachine.Decision.Ratcheted) d9;
        assertThat(r2.newFloor()).isEqualByComparingTo(new BigDecimal("0.35"));
        assertThat(s.floor()).isEqualByComparingTo(new BigDecimal("0.35"));

        // Stage 4: NBBO retraces to 13.50 (+35%) — at floor — three sustained → exit.
        BigDecimal mid135Retrace = new BigDecimal("13.50");
        TrailStateMachine.Decision d10 = TrailStateMachine.advance(s, mid135Retrace, ts(9), DEFAULT_CFG);
        assertThat(d10).isInstanceOf(TrailStateMachine.Decision.Holding.class);
        TrailStateMachine.advance(s, mid135Retrace, ts(10), DEFAULT_CFG);
        TrailStateMachine.Decision d12 = TrailStateMachine.advance(s, mid135Retrace, ts(11), DEFAULT_CFG);
        assertThat(d12).isInstanceOf(TrailStateMachine.Decision.ExitTriggered.class);
        TrailStateMachine.Decision.ExitTriggered ex = (TrailStateMachine.Decision.ExitTriggered) d12;
        // §10.4 expectation: "exit booked at +35%".
        assertThat(ex.exitFloor()).isEqualByComparingTo(new BigDecimal("0.35"));
    }

    // =========================== Sustainment-3 rule ===========================

    @Test
    void singleSnapshotAtActivationNeverArms() {
        TrailState s = fresh();
        TrailStateMachine.Decision d1 = TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(0), DEFAULT_CFG);
        assertThat(d1).isInstanceOf(TrailStateMachine.Decision.Inactive.class);
        // Retrace immediately resets the counter — next high will need 3 again.
        TrailStateMachine.advance(s, new BigDecimal("12.00"), ts(1), DEFAULT_CFG);
        TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(2), DEFAULT_CFG);
        TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(3), DEFAULT_CFG);
        // Only 2 consecutive — still inactive.
        assertThat(s.armed()).isFalse();
    }

    @Test
    void singleSnapshotAtRetraceFloorNeverExits() {
        TrailState s = fresh();
        // Arm at +25%.
        for (int i = 0; i < 3; i++) {
            TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(i), DEFAULT_CFG);
        }
        assertThat(s.armed()).isTrue();
        // One retrace tick to floor — does NOT exit.
        TrailStateMachine.Decision d = TrailStateMachine.advance(s, new BigDecimal("12.50"), ts(10), DEFAULT_CFG);
        assertThat(d).isInstanceOf(TrailStateMachine.Decision.Holding.class);
        // Recover above floor — counter resets.
        TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(11), DEFAULT_CFG);
        // Two retraces back-to-back — still not enough (was reset by the
        // recover above).
        TrailStateMachine.advance(s, new BigDecimal("12.50"), ts(12), DEFAULT_CFG);
        TrailStateMachine.Decision d2 = TrailStateMachine.advance(s, new BigDecimal("12.50"), ts(13), DEFAULT_CFG);
        assertThat(d2).isInstanceOf(TrailStateMachine.Decision.Holding.class);
    }

    // =========================== Floor monotonicity ===========================

    @Test
    void floorNeverDecreasesOnRetracementAboveFloor() {
        TrailState s = fresh();
        // Arm at +25% via 3 snapshots at 13.00.
        for (int i = 0; i < 3; i++) {
            TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(i), DEFAULT_CFG);
        }
        // Ratchet to +30% via 3 snapshots at 13.50.
        for (int i = 3; i < 6; i++) {
            TrailStateMachine.advance(s, new BigDecimal("13.50"), ts(i), DEFAULT_CFG);
        }
        BigDecimal floorAfterRatchet = s.floor();
        assertThat(floorAfterRatchet).isEqualByComparingTo(new BigDecimal("0.30"));

        // Retrace to +28% — between floor and old activation. Floor must NOT decrease.
        for (int i = 6; i < 12; i++) {
            TrailStateMachine.advance(s, new BigDecimal("12.80"), ts(i), DEFAULT_CFG);
        }
        assertThat(s.floor()).isEqualByComparingTo(new BigDecimal("0.30"));
    }

    // =========================== Tunable sustainment ===========================

    @Test
    void sustainmentOneArmsImmediately() {
        TrailConfig cfg = TrailConfig.of(1, new BigDecimal("0.30"), new BigDecimal("0.05"));
        TrailState s = fresh();
        TrailStateMachine.Decision d = TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(0), cfg);
        assertThat(d).isInstanceOf(TrailStateMachine.Decision.Armed.class);
    }

    @Test
    void sustainmentFiveRequiresFiveConsecutive() {
        TrailConfig cfg = TrailConfig.of(5, new BigDecimal("0.30"), new BigDecimal("0.05"));
        TrailState s = fresh();
        for (int i = 0; i < 4; i++) {
            assertThat(TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(i), cfg))
                    .isInstanceOf(TrailStateMachine.Decision.Inactive.class);
        }
        TrailStateMachine.Decision d = TrailStateMachine.advance(s, new BigDecimal("13.00"), ts(4), cfg);
        assertThat(d).isInstanceOf(TrailStateMachine.Decision.Armed.class);
    }

    // =========================== Sanity: UPL helper ===========================

    @Test
    void uplPctMath() {
        // 13/10 - 1 = 0.30; 4-decimal scale.
        assertThat(TrailStateMachine.uplPct(new BigDecimal("10.00"), new BigDecimal("13.00")))
                .isEqualByComparingTo(new BigDecimal("0.30"));
        // 12.50/10 - 1 = 0.25.
        assertThat(TrailStateMachine.uplPct(new BigDecimal("10.00"), new BigDecimal("12.50")))
                .isEqualByComparingTo(new BigDecimal("0.25"));
    }
}
