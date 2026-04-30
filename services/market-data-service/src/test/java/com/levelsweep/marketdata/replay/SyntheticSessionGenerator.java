package com.levelsweep.marketdata.replay;

import com.levelsweep.shared.domain.marketdata.Tick;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic synthetic session generator. Produces tick streams suitable
 * for replay-harness testing. Uses a seeded {@link Random} so the same seed
 * produces byte-identical output across runs.
 *
 * <p>This is the Phase 1 surrogate for real Polygon recordings — once the
 * paid-tier subscription lands (issue #6), real recordings replace these
 * synthetic streams while the test fixtures stay identically structured.
 */
public final class SyntheticSessionGenerator {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private SyntheticSessionGenerator() {}

    /**
     * Generate a normal-day RTH session from 09:30 to 16:00 ET. Random walk
     * around the supplied open price with bounded step size. One tick per
     * supplied {@code tickIntervalSeconds}.
     */
    public static List<Tick> generateRthSession(
            String symbol, LocalDate sessionDate, double openPrice, long seed, int tickIntervalSeconds) {
        ZonedDateTime open = LocalDateTime.of(sessionDate, LocalTime.of(9, 30)).atZone(NY);
        ZonedDateTime close = LocalDateTime.of(sessionDate, LocalTime.of(16, 0)).atZone(NY);
        return generateRandomWalk(symbol, open.toInstant(), close.toInstant(), openPrice, seed, tickIntervalSeconds);
    }

    /**
     * Generate an overnight session (16:01 prior day → 09:29 ET session day).
     * Sparse data: pre-market is light. We use a longer tickInterval (default
     * 30s) and skip Saturday/Sunday gaps for SPY.
     */
    public static List<Tick> generateOvernightSession(
            String symbol, LocalDate sessionDate, double openPrice, long seed, int tickIntervalSeconds) {
        LocalDate priorDay = sessionDate.minusDays(1);
        ZonedDateTime start = LocalDateTime.of(priorDay, LocalTime.of(16, 1)).atZone(NY);
        ZonedDateTime end = LocalDateTime.of(sessionDate, LocalTime.of(9, 29)).atZone(NY);
        return generateRandomWalk(symbol, start.toInstant(), end.toInstant(), openPrice, seed, tickIntervalSeconds);
    }

    /**
     * Generate a custom-window stream — useful for DST testing. Caller
     * supplies the start and end instants explicitly.
     */
    public static List<Tick> generateRandomWalk(
            String symbol,
            Instant start,
            Instant end,
            double openPrice,
            long seed,
            int tickIntervalSeconds) {
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }
        if (tickIntervalSeconds <= 0) {
            throw new IllegalArgumentException("tickIntervalSeconds must be positive");
        }
        Random rng = new Random(seed);
        List<Tick> ticks = new ArrayList<>();
        Instant ts = start;
        double price = openPrice;
        while (ts.isBefore(end)) {
            // Random step with mean reversion toward openPrice
            double step = (rng.nextDouble() - 0.5) * 0.10; // ±5 cents per tick
            double pull = (openPrice - price) * 0.001; // 0.1% pull per tick
            price = price + step + pull;
            // Round to 2 decimals to look like SPY
            BigDecimal p = BigDecimal.valueOf(Math.round(price * 100.0) / 100.0);
            long size = 100L + (rng.nextInt(20)) * 100L; // 100..2000 share lots
            ticks.add(new Tick(symbol, p, size, ts));
            ts = ts.plusSeconds(tickIntervalSeconds);
        }
        return ticks;
    }
}
