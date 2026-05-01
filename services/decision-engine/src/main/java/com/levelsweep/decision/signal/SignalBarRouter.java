package com.levelsweep.decision.signal;

import com.levelsweep.decision.ingest.BarRouter;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import com.levelsweep.shared.domain.signal.SignalEvaluation;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 2 Step 2 {@link BarRouter} — replaces the {@code NoOpBarRouter} default.
 * For every {@link Timeframe#TWO_MIN} bar it pulls the latest
 * {@link IndicatorSnapshot} and {@link Levels} from their holders, runs the
 * {@link SignalEvaluator}, and logs the verdict.
 *
 * <p>Bars on other timeframes pass through (no-op): 1-min and daily are not
 * the trigger frame for this evaluator, and 15-min bars drive entry-matrix
 * decisions in the Trade Saga (S6) — both routes will get their own routers
 * or directly inject {@link SignalEvaluator}.
 *
 * <p>Wiring: this bean is plain {@code @ApplicationScoped} (no
 * {@code @DefaultBean}, no {@code @Alternative}). Quarkus's ArC container
 * deselects {@code NoOpBarRouter} (annotated {@code @DefaultBean}) when a
 * non-default bean of the same type is present, so {@link com.levelsweep.decision.ingest.BarConsumer}
 * picks this implementation up automatically without any change to S1's code.
 *
 * <p>Until the indicator refresher and levels computer land (Phase 2 Step 2
 * follow-up tickets), the holders will be empty for every bar; this router
 * skips with reason {@code no_indicators} or {@code no_levels} and logs at
 * DEBUG. That keeps the bar pipeline healthy without flooding INFO with
 * noise during the bring-up window.
 */
@ApplicationScoped
public class SignalBarRouter implements BarRouter {

    private static final Logger LOG = LoggerFactory.getLogger(SignalBarRouter.class);

    private final SignalEvaluator evaluator;
    private final IndicatorSnapshotHolder snapshotHolder;
    private final LevelsHolder levelsHolder;

    public SignalBarRouter(
            SignalEvaluator evaluator, IndicatorSnapshotHolder snapshotHolder, LevelsHolder levelsHolder) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.snapshotHolder = Objects.requireNonNull(snapshotHolder, "snapshotHolder");
        this.levelsHolder = Objects.requireNonNull(levelsHolder, "levelsHolder");
    }

    @Override
    public void onBar(Bar bar) {
        Objects.requireNonNull(bar, "bar");

        // Only the 2-min timeframe drives EMA-stack signal evaluation per
        // requirements.md §5–§6. Other frames are silently ignored here — the
        // 15-min entry matrix lives in S6 (Trade Saga).
        if (bar.timeframe() != Timeframe.TWO_MIN) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("signal_skip_non_2m symbol={} timeframe={}", bar.symbol(), bar.timeframe());
            }
            return;
        }

        Optional<IndicatorSnapshot> maybeSnapshot = snapshotHolder.latest();
        Optional<Levels> maybeLevels = levelsHolder.latest();

        if (maybeSnapshot.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("signal_skip reason=no_indicators symbol={} closeTime={}", bar.symbol(), bar.closeTime());
            }
            return;
        }
        if (maybeLevels.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("signal_skip reason=no_levels symbol={} closeTime={}", bar.symbol(), bar.closeTime());
            }
            return;
        }

        SignalEvaluation eval = evaluator.evaluate(bar, maybeSnapshot.get(), maybeLevels.get());
        LOG.info(
                "signal_evaluated tenant={} symbol={} closeTime={} action={} level={} reasons={}",
                eval.tenantId(),
                eval.symbol(),
                eval.evaluatedAt(),
                eval.action(),
                eval.level().map(Enum::name).orElse("-"),
                eval.reasons());
    }
}
