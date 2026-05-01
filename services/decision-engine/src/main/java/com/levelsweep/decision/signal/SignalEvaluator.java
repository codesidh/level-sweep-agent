package com.levelsweep.decision.signal;

import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.levelsweep.shared.domain.signal.OptionSide;
import com.levelsweep.shared.domain.signal.SignalAction;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import com.levelsweep.shared.domain.signal.SweptLevel;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Pure-function evaluator that turns
 * {@code (Bar, IndicatorSnapshot, Levels)} into a {@link SignalEvaluation}
 * describing whether a trade setup fires (and which) or why it was skipped.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li><b>Pre-checks</b> (any → SKIP):
 *       <ul>
 *         <li>{@code bar.timeframe()} must be {@link Timeframe#TWO_MIN} —
 *             EMA stack lives on 2-min per {@code requirements.md} §5
 *         <li>{@code snapshot.emasReady()} must be true (warm-up gate)
 *         <li>{@code snapshot.atr14()} must be non-null (warm-up gate)
 *       </ul>
 *   <li><b>Sweep detection</b> — for each of (PDH, PDL, PMH, PML), check if
 *       the bar's wick pierced the level by at least
 *       {@code atr14 * sweepBufferMultiplier} and closed back through. Multiple
 *       sweeps → pick the largest wick distance (tie-broken by iteration order).
 *       <ul>
 *         <li>PDH / PMH: high &gt; level + buffer AND close &lt; level → SHORT setup (PUT)
 *         <li>PDL / PML: low &lt; level - buffer AND close &gt; level → LONG setup (CALL)
 *       </ul>
 *   <li><b>EMA stack confluence</b> per spec §6.1 — must align with the
 *       sweep's directional bias:
 *       <ul>
 *         <li>LONG: {@code ema13 > ema48 > ema200} AND
 *             {@code (ema13 - ema48) >= emaGapMultiplier * atr14} AND
 *             {@code (ema48 - ema200) >= emaGapMultiplier * atr14}
 *         <li>SHORT: {@code ema13 < ema48 < ema200} AND
 *             {@code (ema48 - ema13) >= emaGapMultiplier * atr14} AND
 *             {@code (ema200 - ema48) >= emaGapMultiplier * atr14}
 *       </ul>
 *   <li><b>Near-level proximity</b> — {@code |close - level| <= atr14 * nearLevelFactor};
 *       prevents firing on a price that has already run far past the swept level.
 *   <li>All four pass → ENTER with {@code reasons} listing the evidence; any
 *       fail → SKIP with a reasons list naming the failed gates.
 * </ol>
 *
 * <p>Determinism: no IO, no clock, no randomness, no mutable state. Same inputs
 * always produce the same output, including stable {@code reasons} list ordering
 * (required for replay parity per {@code requirements.md} §18 criterion 8).
 *
 * <p>Buffer thresholds are externalized as {@link ConfigProperty} values so a
 * future operator can sweep them without recompiling. Defaults match
 * {@code requirements.md} §6.1 (gap = 0.3·ATR) and §7 (sweep buffer = 0.2·ATR);
 * the {@code near-level-factor} is a Signal-Engine-local heuristic with no
 * direct §-reference.
 */
@ApplicationScoped
public class SignalEvaluator {

    /**
     * Minimum wick beyond a level (as a multiple of ATR(14)) to count as a
     * sweep. Per {@code requirements.md} §7 — {@code BUFFER = 0.2 * ATR(14)}.
     */
    private final BigDecimal sweepBufferMultiplier;

    /**
     * Minimum EMA-stack gap (top and bottom) as a multiple of ATR(14). Per
     * {@code requirements.md} §6.1 — both {@code gap_top} and {@code gap_bottom}
     * must be ≥ 0.3·ATR for the stack to qualify as a valid trend.
     */
    private final BigDecimal emaGapMultiplier;

    /**
     * Maximum distance from the swept level (as a multiple of ATR(14)) for the
     * bar's close to still count as "near". Above this the price has run too
     * far past the level for a sweep-reversal entry to be safe. Engine-local
     * heuristic; not directly specified in {@code requirements.md}.
     */
    private final BigDecimal nearLevelFactor;

    public SignalEvaluator(
            @ConfigProperty(name = "decision.signal.atr-buffer-multiplier", defaultValue = "0.20")
                    BigDecimal sweepBufferMultiplier,
            @ConfigProperty(name = "decision.signal.ema-gap-multiplier", defaultValue = "0.30")
                    BigDecimal emaGapMultiplier,
            @ConfigProperty(name = "decision.signal.near-level-factor", defaultValue = "0.50")
                    BigDecimal nearLevelFactor) {
        this.sweepBufferMultiplier = Objects.requireNonNull(sweepBufferMultiplier, "sweepBufferMultiplier");
        this.emaGapMultiplier = Objects.requireNonNull(emaGapMultiplier, "emaGapMultiplier");
        this.nearLevelFactor = Objects.requireNonNull(nearLevelFactor, "nearLevelFactor");
    }

    /**
     * Evaluate a single bar against an indicator snapshot and the session's
     * reference levels. See class-level javadoc for the full algorithm.
     */
    public SignalEvaluation evaluate(Bar bar, IndicatorSnapshot snapshot, Levels levels) {
        Objects.requireNonNull(bar, "bar");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(levels, "levels");

        // ---- Pre-checks (any failure → SKIP, short-circuit) -----------------
        if (bar.timeframe() != Timeframe.TWO_MIN) {
            return SignalEvaluation.skip(
                    levels.tenantId(),
                    bar.symbol(),
                    bar.closeTime(),
                    List.of("wrong_timeframe:" + bar.timeframe()));
        }
        if (!snapshot.emasReady()) {
            return SignalEvaluation.skip(
                    levels.tenantId(), bar.symbol(), bar.closeTime(), List.of("emas_warming_up"));
        }
        if (snapshot.atr14() == null) {
            return SignalEvaluation.skip(
                    levels.tenantId(), bar.symbol(), bar.closeTime(), List.of("atr_warming_up"));
        }

        BigDecimal atr = snapshot.atr14();
        BigDecimal buffer = atr.multiply(sweepBufferMultiplier);

        // ---- Sweep detection — pick the largest wick if multiple match -----
        SweepCandidate best = pickBestSweep(bar, levels, buffer);
        if (best == null) {
            return SignalEvaluation.skip(
                    levels.tenantId(), bar.symbol(), bar.closeTime(), List.of("sweep:none"));
        }

        // ---- EMA stack confluence (§6.1) ------------------------------------
        BigDecimal gapTop = snapshot.ema13().subtract(snapshot.ema48()); // signed
        BigDecimal gapBottom = snapshot.ema48().subtract(snapshot.ema200());
        BigDecimal minGap = atr.multiply(emaGapMultiplier);

        boolean bullishStack = gapTop.signum() > 0
                && gapBottom.signum() > 0
                && gapTop.compareTo(minGap) >= 0
                && gapBottom.compareTo(minGap) >= 0;
        boolean bearishStack = gapTop.signum() < 0
                && gapBottom.signum() < 0
                && gapTop.abs().compareTo(minGap) >= 0
                && gapBottom.abs().compareTo(minGap) >= 0;

        boolean stackOk = (best.action == SignalAction.ENTER_LONG && bullishStack)
                || (best.action == SignalAction.ENTER_SHORT && bearishStack);

        if (!stackOk) {
            String stackString = stackString(snapshot.ema13(), snapshot.ema48(), snapshot.ema200());
            String setup = best.action == SignalAction.ENTER_LONG ? "LONG_setup" : "SHORT_setup";
            return SignalEvaluation.skip(
                    levels.tenantId(),
                    bar.symbol(),
                    bar.closeTime(),
                    List.of("sweep:" + best.level, "ema_stack_mismatch:" + setup + "_but_" + stackString));
        }

        // ---- Near-level proximity ------------------------------------------
        BigDecimal distance = bar.close().subtract(best.levelPrice).abs();
        BigDecimal nearThreshold = atr.multiply(nearLevelFactor);
        if (distance.compareTo(nearThreshold) > 0) {
            return SignalEvaluation.skip(
                    levels.tenantId(),
                    bar.symbol(),
                    bar.closeTime(),
                    List.of(
                            "sweep:" + best.level,
                            "ema_stack:" + (bullishStack ? "LONG_OK" : "SHORT_OK"),
                            "far_from_level:" + ratio(distance, atr) + "*atr"));
        }

        // ---- All gates passed → ENTER --------------------------------------
        OptionSide side = best.action == SignalAction.ENTER_LONG ? OptionSide.CALL : OptionSide.PUT;
        List<String> reasons = new ArrayList<>(4);
        reasons.add("sweep:" + best.level);
        reasons.add("ema_stack:"
                + (bullishStack ? "LONG_OK" : "SHORT_OK")
                + "[" + stackString(snapshot.ema13(), snapshot.ema48(), snapshot.ema200()) + "]");
        reasons.add("atr_buffer:" + ratio(best.wickDistance, atr) + ">" + emaGapMultiplier + "*atr");
        reasons.add("near_level:" + ratio(distance, atr) + "*atr");
        return SignalEvaluation.enter(
                levels.tenantId(),
                bar.symbol(),
                bar.closeTime(),
                best.action,
                best.level,
                side,
                best.levelPrice,
                reasons);
    }

    /**
     * Walk all four levels and return the candidate with the largest wick
     * distance, or {@code null} if none qualified. Order of iteration is
     * fixed (PDH, PMH, PDL, PML) so the choice is deterministic when multiple
     * candidates have an identical wick distance — a corner case in live data
     * but a non-issue for replay because we break ties with a stable order.
     */
    private SweepCandidate pickBestSweep(Bar bar, Levels levels, BigDecimal buffer) {
        SweepCandidate best = null;

        // PDH / PMH — high pierces above + close back below → SHORT
        best = considerShortSweep(best, bar, SweptLevel.PDH, levels.pdh(), buffer);
        best = considerShortSweep(best, bar, SweptLevel.PMH, levels.pmh(), buffer);

        // PDL / PML — low pierces below + close back above → LONG
        best = considerLongSweep(best, bar, SweptLevel.PDL, levels.pdl(), buffer);
        best = considerLongSweep(best, bar, SweptLevel.PML, levels.pml(), buffer);

        return best;
    }

    private SweepCandidate considerShortSweep(
            SweepCandidate currentBest, Bar bar, SweptLevel which, BigDecimal levelPrice, BigDecimal buffer) {
        BigDecimal threshold = levelPrice.add(buffer);
        if (bar.high().compareTo(threshold) > 0 && bar.close().compareTo(levelPrice) < 0) {
            BigDecimal wick = bar.high().subtract(levelPrice);
            return chooseLargerWick(
                    currentBest, new SweepCandidate(which, SignalAction.ENTER_SHORT, levelPrice, wick));
        }
        return currentBest;
    }

    private SweepCandidate considerLongSweep(
            SweepCandidate currentBest, Bar bar, SweptLevel which, BigDecimal levelPrice, BigDecimal buffer) {
        BigDecimal threshold = levelPrice.subtract(buffer);
        if (bar.low().compareTo(threshold) < 0 && bar.close().compareTo(levelPrice) > 0) {
            BigDecimal wick = levelPrice.subtract(bar.low());
            return chooseLargerWick(
                    currentBest, new SweepCandidate(which, SignalAction.ENTER_LONG, levelPrice, wick));
        }
        return currentBest;
    }

    private static SweepCandidate chooseLargerWick(SweepCandidate a, SweepCandidate b) {
        if (a == null) {
            return b;
        }
        return a.wickDistance.compareTo(b.wickDistance) >= 0 ? a : b;
    }

    /** Render the EMA ordering compactly for audit reasons, e.g. {@code 13>48>200} or {@code 13<48<200}. */
    private static String stackString(BigDecimal ema13, BigDecimal ema48, BigDecimal ema200) {
        String topToMid = ema13.compareTo(ema48) > 0 ? ">" : (ema13.compareTo(ema48) < 0 ? "<" : "=");
        String midToBot = ema48.compareTo(ema200) > 0 ? ">" : (ema48.compareTo(ema200) < 0 ? "<" : "=");
        return "13" + topToMid + "48" + midToBot + "200";
    }

    /**
     * Format {@code numerator / atr} as a 2-decimal-place string for audit
     * trail entries. Uses {@link MathContext#DECIMAL64} so very small ATR values
     * don't blow up the divide; the result is then rounded to 2 dp for stable
     * stringification across replay runs.
     */
    private static String ratio(BigDecimal numerator, BigDecimal atr) {
        if (atr.signum() == 0) {
            return "n/a";
        }
        BigDecimal r = numerator.divide(atr, MathContext.DECIMAL64);
        return r.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Internal carrier — chosen sweep candidate before the EMA / proximity
     * gates run. Package-private (not exposed) so we can iterate it without
     * leaking implementation detail through the public API.
     */
    private record SweepCandidate(
            SweptLevel level, SignalAction action, BigDecimal levelPrice, BigDecimal wickDistance) {}
}
