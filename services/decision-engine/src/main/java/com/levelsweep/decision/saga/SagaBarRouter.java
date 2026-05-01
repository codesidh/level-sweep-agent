package com.levelsweep.decision.saga;

import com.levelsweep.decision.ingest.BarRouter;
import com.levelsweep.decision.ingest.NoOpBarRouter;
import com.levelsweep.decision.signal.IndicatorSnapshotHolder;
import com.levelsweep.decision.signal.LevelsHolder;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 2 Step 6 {@link BarRouter} — supersedes the {@link NoOpBarRouter} default
 * and the prior {@code SignalBarRouter} (whose responsibilities have folded
 * into {@link TradeSaga}). For every {@link Timeframe#TWO_MIN} bar it pulls the
 * latest {@link IndicatorSnapshot} and {@link Levels} from their holders and
 * runs the full saga (session gate, signal evaluation, risk gate, strike
 * selection, trade FSM, event emission).
 *
 * <p>Bars on other timeframes are ignored at the router. The signal-driving
 * timeframe per {@code requirements.md} §5–§6 is 2-min; 1-min and daily are
 * not yet wired (they feed indicator computation in a follow-up), and 15-min
 * is the entry-matrix frame (Phase 2 follow-up).
 *
 * <p>Bean wiring: plain {@code @ApplicationScoped} (no {@code @DefaultBean},
 * no {@code @Alternative}). Quarkus's ArC container deselects the
 * {@link NoOpBarRouter} default the moment a non-default bean of the same type
 * is present, so {@link com.levelsweep.decision.ingest.BarConsumer} picks this
 * implementation up automatically. There is no other non-default
 * {@link BarRouter} on the classpath after this PR.
 *
 * <p>Until the indicator refresher and levels computer land (Phase 2 follow-up
 * tickets), the holders will be empty for every bar; this router skips with a
 * DEBUG log and does not invoke the saga. That keeps the bar pipeline healthy
 * without flooding INFO during the bring-up window.
 */
@ApplicationScoped
public class SagaBarRouter implements BarRouter {

    private static final Logger LOG = LoggerFactory.getLogger(SagaBarRouter.class);

    private final TradeSaga saga;
    private final IndicatorSnapshotHolder snapshotHolder;
    private final LevelsHolder levelsHolder;

    public SagaBarRouter(TradeSaga saga, IndicatorSnapshotHolder snapshotHolder, LevelsHolder levelsHolder) {
        this.saga = Objects.requireNonNull(saga, "saga");
        this.snapshotHolder = Objects.requireNonNull(snapshotHolder, "snapshotHolder");
        this.levelsHolder = Objects.requireNonNull(levelsHolder, "levelsHolder");
    }

    @Override
    public void onBar(Bar bar) {
        Objects.requireNonNull(bar, "bar");

        // Only the 2-min timeframe drives the EMA-stack signal evaluation per
        // requirements.md §5–§6. Other frames pass through silently — TRACE log
        // for ops who want to confirm the consumer is alive without raising the
        // overall log level.
        if (bar.timeframe() != Timeframe.TWO_MIN) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("saga_skip_non_2m symbol={} timeframe={}", bar.symbol(), bar.timeframe());
            }
            return;
        }

        Optional<IndicatorSnapshot> maybeSnapshot = snapshotHolder.latest();
        Optional<Levels> maybeLevels = levelsHolder.latest();

        if (maybeSnapshot.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("saga_skip reason=no_indicators symbol={} closeTime={}", bar.symbol(), bar.closeTime());
            }
            return;
        }
        if (maybeLevels.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("saga_skip reason=no_levels symbol={} closeTime={}", bar.symbol(), bar.closeTime());
            }
            return;
        }

        TradeSaga.Result result = saga.run(bar, maybeSnapshot.get(), maybeLevels.get());
        if (LOG.isDebugEnabled()) {
            switch (result) {
                case TradeSaga.Result.Proposed p -> LOG.debug(
                        "saga_proposed tradeId={} symbol={} side={} contract={} correlationId={}",
                        p.event().tradeId(),
                        p.event().underlying(),
                        p.event().side(),
                        p.event().contractSymbol(),
                        p.event().correlationId());
                case TradeSaga.Result.Skipped s -> LOG.debug(
                        "saga_skipped stage={} reasons={} correlationId={}",
                        s.event().stage(),
                        s.event().reasons(),
                        s.event().correlationId());
            }
        }
    }
}
