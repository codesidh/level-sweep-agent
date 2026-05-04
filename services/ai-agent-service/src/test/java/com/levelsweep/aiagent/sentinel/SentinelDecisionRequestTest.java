package com.levelsweep.aiagent.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Bar;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Direction;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.IndicatorSnapshot;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.LevelSwept;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Outcome;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.RecentTrade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation contract for {@link SentinelDecisionRequest}. The compact
 * constructor is the deterministic gate — bad inputs from the saga must fail
 * here, not silently degrade into a malformed prompt.
 */
class SentinelDecisionRequestTest {

    private static final Instant T = Instant.parse("2026-05-02T15:00:00Z");

    @Test
    void happyPath_buildsDefensivelyCopiedRequest() {
        SentinelDecisionRequest req = sample();
        assertThat(req.tenantId()).isEqualTo("OWNER");
        assertThat(req.direction()).isEqualTo(Direction.LONG_CALL);
        assertThat(req.levelSwept()).isEqualTo(LevelSwept.PDH);
        // Defensive copy — request list must be unmodifiable.
        assertThatThrownBy(() -> req.recentTradesWindow().add(new RecentTrade("X", Outcome.WIN, BigDecimal.ONE, T)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> req.indicatorSnapshot().recentBars().add(new Bar(T, BigDecimal.ONE, 0L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullTenantId() {
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        null,
                        "TR_001",
                        "SIG_001",
                        Direction.LONG_CALL,
                        LevelSwept.PDH,
                        sampleIndicators(),
                        List.of(),
                        new BigDecimal("14.50"),
                        T))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        "  ",
                        "TR_001",
                        "SIG_001",
                        Direction.LONG_CALL,
                        LevelSwept.PDH,
                        sampleIndicators(),
                        List.of(),
                        new BigDecimal("14.50"),
                        T))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsBlankTradeId() {
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        "OWNER",
                        "",
                        "SIG_001",
                        Direction.LONG_CALL,
                        LevelSwept.PDH,
                        sampleIndicators(),
                        List.of(),
                        new BigDecimal("14.50"),
                        T))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tradeId");
    }

    @Test
    void rejectsBlankSignalId() {
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        "OWNER",
                        "TR_001",
                        " ",
                        Direction.LONG_CALL,
                        LevelSwept.PDH,
                        sampleIndicators(),
                        List.of(),
                        new BigDecimal("14.50"),
                        T))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signalId");
    }

    @Test
    void rejectsNullDirection() {
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        "OWNER",
                        "TR_001",
                        "SIG_001",
                        null,
                        LevelSwept.PDH,
                        sampleIndicators(),
                        List.of(),
                        new BigDecimal("14.50"),
                        T))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("direction");
    }

    @Test
    void rejectsNullLevelSwept() {
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        "OWNER",
                        "TR_001",
                        "SIG_001",
                        Direction.LONG_PUT,
                        null,
                        sampleIndicators(),
                        List.of(),
                        new BigDecimal("14.50"),
                        T))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("levelSwept");
    }

    @Test
    void rejectsNullVixOrNowUtc() {
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        "OWNER",
                        "TR_001",
                        "SIG_001",
                        Direction.LONG_CALL,
                        LevelSwept.PDH,
                        sampleIndicators(),
                        List.of(),
                        null,
                        T))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("vixClosePrev");
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        "OWNER",
                        "TR_001",
                        "SIG_001",
                        Direction.LONG_CALL,
                        LevelSwept.PDH,
                        sampleIndicators(),
                        List.of(),
                        new BigDecimal("14.50"),
                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nowUtc");
    }

    @Test
    void enforcesMaxFiveRecentTrades() {
        List<RecentTrade> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new RecentTrade("TR_" + i, Outcome.WIN, BigDecimal.ONE, T));
        }
        assertThatThrownBy(() -> new SentinelDecisionRequest(
                        "OWNER",
                        "TR_001",
                        "SIG_001",
                        Direction.LONG_CALL,
                        LevelSwept.PDH,
                        sampleIndicators(),
                        six,
                        new BigDecimal("14.50"),
                        T))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recentTradesWindow");
    }

    @Test
    void acceptsExactlyFiveRecentTrades() {
        List<RecentTrade> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(new RecentTrade("TR_" + i, Outcome.WIN, BigDecimal.ONE, T));
        }
        SentinelDecisionRequest req = new SentinelDecisionRequest(
                "OWNER",
                "TR_001",
                "SIG_001",
                Direction.LONG_CALL,
                LevelSwept.PDH,
                sampleIndicators(),
                five,
                new BigDecimal("14.50"),
                T);
        assertThat(req.recentTradesWindow()).hasSize(5);
    }

    @Test
    void indicatorSnapshotBlocksThirteenBarsAtConstruction() {
        List<Bar> thirteen = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            thirteen.add(new Bar(T.plusSeconds(i * 120L), new BigDecimal("500.00"), 1000L));
        }
        assertThatThrownBy(() -> new IndicatorSnapshot(
                        new BigDecimal("500.00"),
                        new BigDecimal("499.00"),
                        new BigDecimal("498.00"),
                        new BigDecimal("1.50"),
                        new BigDecimal("60.00"),
                        "BULL",
                        thirteen))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recentBars");
    }

    @Test
    void indicatorSnapshotAcceptsExactlyTwelveBars() {
        List<Bar> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(new Bar(T.plusSeconds(i * 120L), new BigDecimal("500.00"), 1000L));
        }
        IndicatorSnapshot snap = new IndicatorSnapshot(
                new BigDecimal("500.00"),
                new BigDecimal("499.00"),
                new BigDecimal("498.00"),
                new BigDecimal("1.50"),
                new BigDecimal("60.00"),
                "BULL",
                twelve);
        assertThat(snap.recentBars()).hasSize(12);
    }

    @Test
    void barRejectsNegativeVolume() {
        assertThatThrownBy(() -> new Bar(T, new BigDecimal("500.00"), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("volume");
    }

    @Test
    void recentTradeRejectsBlankTradeId() {
        assertThatThrownBy(() -> new RecentTrade(" ", Outcome.WIN, BigDecimal.ONE, T))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tradeId");
    }

    @Test
    void indicatorSnapshotRejectsBlankRegime() {
        assertThatThrownBy(() -> new IndicatorSnapshot(
                        new BigDecimal("500.00"),
                        new BigDecimal("499.00"),
                        new BigDecimal("498.00"),
                        new BigDecimal("1.50"),
                        new BigDecimal("60.00"),
                        "  ",
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regime");
    }

    private static SentinelDecisionRequest sample() {
        return new SentinelDecisionRequest(
                "OWNER",
                "TR_001",
                "SIG_001",
                Direction.LONG_CALL,
                LevelSwept.PDH,
                sampleIndicators(),
                List.of(new RecentTrade("TR_PRIOR_1", Outcome.WIN, new BigDecimal("1.5"), T.minusSeconds(3600))),
                new BigDecimal("14.50"),
                T);
    }

    private static IndicatorSnapshot sampleIndicators() {
        return new IndicatorSnapshot(
                new BigDecimal("500.00"),
                new BigDecimal("499.00"),
                new BigDecimal("498.00"),
                new BigDecimal("1.50"),
                new BigDecimal("60.00"),
                "BULL",
                List.of(
                        new Bar(T.minusSeconds(240), new BigDecimal("499.50"), 12000L),
                        new Bar(T.minusSeconds(120), new BigDecimal("499.75"), 13000L),
                        new Bar(T, new BigDecimal("500.00"), 14000L)));
    }
}
