package com.levelsweep.decision.ingest;

import com.levelsweep.shared.domain.marketdata.Bar;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link BarRouter} for Phase 2 Step 1 — counts every bar received and
 * logs a single confirmation line every {@value #LOG_EVERY} bars at INFO so we
 * can verify in production that bars are flowing without flooding the log.
 *
 * <p>Marked {@link DefaultBean} so any of the Phase 2 follow-on modules (Signal
 * Engine, Risk Manager, Trade Saga) can override by producing a different
 * {@code @ApplicationScoped} {@link BarRouter} bean — Quarkus's ArC container
 * deselects the {@code @DefaultBean} when an alternative is present without
 * needing {@code @Alternative} or veto wiring.
 *
 * <p>The counter is monotonic across all four timeframes (1m + 2m + 15m + daily)
 * — the rate-limited log line is operational confirmation only, not a metric.
 * Per-timeframe counts arrive in Step 2 via Micrometer.
 *
 * <p>Determinism: this class holds no wall-clock time and is safe to drive from
 * the Replay Harness in Step 7.
 */
@ApplicationScoped
@DefaultBean
public class NoOpBarRouter implements BarRouter {

    private static final Logger LOG = LoggerFactory.getLogger(NoOpBarRouter.class);

    /** Emit a confirmation log line every Nth bar; balances visibility vs. noise. */
    static final long LOG_EVERY = 60L;

    private final AtomicLong counter = new AtomicLong();

    @Override
    public void onBar(Bar bar) {
        long n = counter.incrementAndGet();
        if (n % LOG_EVERY == 0L) {
            LOG.info(
                    "decision-engine bar pipeline alive count={} latest symbol={} timeframe={} closeTime={}",
                    n,
                    bar.symbol(),
                    bar.timeframe(),
                    bar.closeTime());
        }
    }

    /** Test seam — exposes the running count without leaking the AtomicLong. */
    long count() {
        return counter.get();
    }
}
