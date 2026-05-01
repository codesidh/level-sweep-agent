package com.levelsweep.decision.replay;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Deterministic synthetic-session generator for the Decision Engine replay
 * harness. Mirrors the
 * {@link com.levelsweep.marketdata.replay.SyntheticSessionGenerator} pattern in
 * market-data-service but emits 2-minute bars + per-bar
 * {@link IndicatorSnapshot}s ready to feed straight into
 * {@link com.levelsweep.decision.signal.SignalEvaluator} — no aggregator or
 * indicator-engine wiring required for these tests.
 *
 * <p>Why fixture-supplied indicators rather than computing on the fly: the
 * Signal Engine expects a fully-warmed snapshot at every bar. A 195-bar synthetic
 * session can't naturally bootstrap an EMA-200 from a random walk, and we want
 * each scenario (PDH SHORT, PDL LONG, no-setup) to land on a deterministic EMA
 * stack regardless of the wick gymnastics happening on the bars themselves.
 * Fixing both prices AND indicator state independently keeps the fixture readable
 * and the parity contract trivially byte-equal across runs.
 *
 * <h3>Scenario builders</h3>
 *
 * <p>Use {@link #builder} to start, set levels + duration + initial bias, optionally
 * call {@link Builder#injectSweep} for one or more wick incidents, then
 * {@link Builder#build}. Output is a {@link Session} record carrying everything
 * the harness needs.
 *
 * <h3>Determinism</h3>
 *
 * <p>All randomness flows through a seeded {@link Random}. Same seed → byte-equal
 * bars + indicators. The {@code SyntheticSessionGenerator} pattern in
 * market-data-service uses the same seeded approach.
 */
public final class SyntheticSessionFixtures {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    /** RTH = 09:30..16:00 ET = 390 minutes = 195 × 2-min bars. */
    private static final int RTH_TWO_MIN_BARS = 195;

    /** Fixture invariant — every snapshot carries this ATR(14) so buffer math is dollars. */
    private static final BigDecimal FIXTURE_ATR = new BigDecimal("1.00");

    private SyntheticSessionFixtures() {}

    /** Snapshot bias applied to indicator emission for the entire session. */
    public enum StackBias {
        BULLISH,
        BEARISH
    }

    /** Which level a sweep injection targets. */
    public enum SweepKind {
        PDH_SHORT,
        PDL_LONG,
        PMH_SHORT,
        PML_LONG
    }

    /**
     * The full materialized session — date, levels, 2-min bars, and one
     * indicator snapshot per bar in lock-step.
     */
    public record Session(
            String name,
            LocalDate date,
            Levels levels,
            List<Bar> bars2m,
            List<IndicatorSnapshot> indicators) {
        public Session {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(levels, "levels");
            Objects.requireNonNull(bars2m, "bars2m");
            Objects.requireNonNull(indicators, "indicators");
            if (bars2m.size() != indicators.size()) {
                throw new IllegalArgumentException(
                        "bars2m size " + bars2m.size() + " != indicators size " + indicators.size());
            }
            bars2m = List.copyOf(bars2m);
            indicators = List.copyOf(indicators);
        }
    }

    /** Pending sweep injection — replaces the natural bar at {@link #atIndex}. */
    private record SweepInjection(int atIndex, SweepKind kind, BigDecimal beyondAmount) {}

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private long seed = 42L;
        private LocalDate date = LocalDate.of(2026, 4, 30);
        private double openPrice = 595.0;
        private BigDecimal pdh = new BigDecimal("600.00");
        private BigDecimal pdl = new BigDecimal("590.00");
        private BigDecimal pmh = new BigDecimal("598.00");
        private BigDecimal pml = new BigDecimal("592.00");
        private int barCount = RTH_TWO_MIN_BARS;
        private StackBias bias = StackBias.BULLISH;
        private final List<SweepInjection> injections = new ArrayList<>();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder fromSeed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder onDate(LocalDate date) {
            this.date = Objects.requireNonNull(date, "date");
            return this;
        }

        public Builder openPrice(double openPrice) {
            this.openPrice = openPrice;
            return this;
        }

        public Builder withLevels(BigDecimal pdh, BigDecimal pdl, BigDecimal pmh, BigDecimal pml) {
            this.pdh = Objects.requireNonNull(pdh, "pdh");
            this.pdl = Objects.requireNonNull(pdl, "pdl");
            this.pmh = Objects.requireNonNull(pmh, "pmh");
            this.pml = Objects.requireNonNull(pml, "pml");
            return this;
        }

        public Builder barCount(int barCount) {
            if (barCount <= 0 || barCount > RTH_TWO_MIN_BARS) {
                throw new IllegalArgumentException("barCount must be in (0," + RTH_TWO_MIN_BARS + "]; got " + barCount);
            }
            this.barCount = barCount;
            return this;
        }

        public Builder withStackBias(StackBias bias) {
            this.bias = Objects.requireNonNull(bias, "bias");
            return this;
        }

        /**
         * Replace the bar at {@code atIndex} with a sweep of the given kind. The
         * synthetic bar is rebuilt so its OHLC pierces the level by
         * {@code beyondAmount} and closes back through; remaining natural bars
         * continue from the close of the injected bar.
         */
        public Builder injectSweep(int atIndex, SweepKind kind, BigDecimal beyondAmount) {
            if (atIndex < 0 || atIndex >= barCount) {
                throw new IllegalArgumentException("atIndex out of range: " + atIndex);
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(beyondAmount, "beyondAmount");
            if (beyondAmount.signum() <= 0) {
                throw new IllegalArgumentException("beyondAmount must be > 0; got " + beyondAmount);
            }
            injections.add(new SweepInjection(atIndex, kind, beyondAmount));
            return this;
        }

        public Session build() {
            Levels levels = new Levels("OWNER", "SPY", date, pdh, pdl, pmh, pml);
            ZonedDateTime open = LocalDateTime.of(date, LocalTime.of(9, 30)).atZone(NY);
            Random rng = new Random(seed);

            List<Bar> bars = new ArrayList<>(barCount);
            List<IndicatorSnapshot> snaps = new ArrayList<>(barCount);

            double price = openPrice;
            for (int i = 0; i < barCount; i++) {
                Instant openTime = open.plus(Timeframe.TWO_MIN.duration().multipliedBy(i)).toInstant();
                Instant closeTime = openTime.plus(Timeframe.TWO_MIN.duration());

                SweepInjection inj = findInjection(i);
                Bar bar;
                if (inj != null) {
                    bar = buildSweepBar(openTime, closeTime, inj, price);
                    price = round2(bar.close().doubleValue());
                } else {
                    bar = buildRandomWalkBar(openTime, closeTime, price, rng);
                    price = round2(bar.close().doubleValue());
                }
                bars.add(bar);

                IndicatorSnapshot snap = snapshotFor(closeTime, bias);
                snaps.add(snap);
            }

            return new Session(name, date, levels, bars, snaps);
        }

        private SweepInjection findInjection(int idx) {
            for (SweepInjection inj : injections) {
                if (inj.atIndex == idx) {
                    return inj;
                }
            }
            return null;
        }

        private Bar buildSweepBar(Instant openTime, Instant closeTime, SweepInjection inj, double prevClose) {
            BigDecimal levelPrice =
                    switch (inj.kind) {
                        case PDH_SHORT -> pdh;
                        case PDL_LONG -> pdl;
                        case PMH_SHORT -> pmh;
                        case PML_LONG -> pml;
                    };

            // For SHORT setups: high pierces above level by beyondAmount, close lands ~0.20
            //   back below the level (within near-level cap of 0.50 × atr=1.00).
            // For LONG setups: low pierces below level by beyondAmount, close lands ~0.20
            //   back above the level. open sits near the level so the wick has somewhere to go.
            BigDecimal levelB = levelPrice;
            BigDecimal cents20 = new BigDecimal("0.20");
            BigDecimal cents10 = new BigDecimal("0.10");
            BigDecimal o;
            BigDecimal h;
            BigDecimal l;
            BigDecimal c;
            switch (inj.kind) {
                case PDH_SHORT, PMH_SHORT -> {
                    // open above level, push high above (level + beyondAmount), close below level
                    o = levelB.add(cents10);
                    h = levelB.add(inj.beyondAmount);
                    c = levelB.subtract(cents20); // 0.20 below level → near (≤0.50)
                    BigDecimal lowFloor = c.subtract(cents10);
                    l = lowFloor.compareTo(o) < 0 ? lowFloor : o.subtract(cents10);
                }
                case PDL_LONG, PML_LONG -> {
                    o = levelB.subtract(cents10);
                    l = levelB.subtract(inj.beyondAmount);
                    c = levelB.add(cents20);
                    BigDecimal highCeil = c.add(cents10);
                    h = highCeil.compareTo(o) > 0 ? highCeil : o.add(cents10);
                }
                default -> throw new IllegalStateException("unreachable");
            }
            return new Bar("SPY", Timeframe.TWO_MIN, openTime, closeTime, o, h, l, c, 1_000L, 30L);
        }

        private Bar buildRandomWalkBar(Instant openTime, Instant closeTime, double prevClose, Random rng) {
            // Tight intra-bar movement ~5 cents; mean-revert toward openPrice by 0.1%.
            double step = (rng.nextDouble() - 0.5) * 0.10;
            double pull = (openPrice - prevClose) * 0.001;
            double close = prevClose + step + pull;
            // Bound jitter so wicks stay inside levels (±0.10 around close)
            double high = Math.max(prevClose, close) + Math.abs(rng.nextGaussian()) * 0.05;
            double low = Math.min(prevClose, close) - Math.abs(rng.nextGaussian()) * 0.05;
            BigDecimal o = roundDec(prevClose);
            BigDecimal c = roundDec(close);
            BigDecimal h = roundDec(high).max(o).max(c);
            BigDecimal l = roundDec(low).min(o).min(c);
            return new Bar("SPY", Timeframe.TWO_MIN, openTime, closeTime, o, h, l, c, 1_000L, 30L);
        }

        private static IndicatorSnapshot snapshotFor(Instant closeTime, StackBias bias) {
            // Stack centered around 595 with 1.00-dollar gaps (>= 0.30 × ATR=1.00 floor).
            BigDecimal e13;
            BigDecimal e48;
            BigDecimal e200;
            switch (bias) {
                case BULLISH -> {
                    e13 = new BigDecimal("596.00");
                    e48 = new BigDecimal("595.00");
                    e200 = new BigDecimal("594.00");
                }
                case BEARISH -> {
                    e13 = new BigDecimal("594.00");
                    e48 = new BigDecimal("595.00");
                    e200 = new BigDecimal("596.00");
                }
                default -> throw new IllegalStateException("unreachable");
            }
            return new IndicatorSnapshot("SPY", closeTime, e13, e48, e200, FIXTURE_ATR);
        }
    }

    private static BigDecimal roundDec(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Convenience overload — builders may need this from outside. */
    public static Optional<Bar> firstBarOf(Session s) {
        return s.bars2m().isEmpty() ? Optional.empty() : Optional.of(s.bars2m().get(0));
    }
}
