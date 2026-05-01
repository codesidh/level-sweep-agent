package com.levelsweep.execution.ingest;

import com.levelsweep.shared.domain.trade.TradeProposed;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link TradeRouter} for Phase 3 Step 1 — counts every TradeProposed
 * received and logs a single confirmation line every {@value #LOG_EVERY} events
 * at INFO so we can verify in production that trades are flowing without
 * flooding the log.
 *
 * <p>Marked {@link DefaultBean} so any of the Phase 3 follow-on modules (Alpaca
 * order placement, fill listener, stop watcher, EOD flatten) can override by
 * producing a different {@code @ApplicationScoped} {@link TradeRouter} bean —
 * Quarkus's ArC container deselects the {@code @DefaultBean} when an alternative
 * is present without needing {@code @Alternative} or veto wiring.
 *
 * <p>Determinism: this class holds no wall-clock time and is safe to drive from
 * the Replay Harness in Step 7. Mirrors decision-engine's {@code NoOpBarRouter}.
 */
@ApplicationScoped
@DefaultBean
public class NoOpTradeRouter implements TradeRouter {

    private static final Logger LOG = LoggerFactory.getLogger(NoOpTradeRouter.class);

    /**
     * Emit a confirmation log line every Nth event. Trade volume is far lower
     * than bar volume (≤ 5 trades/day per tenant per requirements §11), so this
     * threshold is intentionally low — every event still produces a DEBUG line
     * via {@code TradeProposedConsumer}; this INFO line is operational
     * confirmation only.
     */
    static final long LOG_EVERY = 1L;

    private final AtomicLong counter = new AtomicLong();

    @Override
    public void onTradeProposed(TradeProposed event) {
        long n = counter.incrementAndGet();
        if (n % LOG_EVERY == 0L) {
            LOG.info(
                    "execution-service trade pipeline alive count={} latest tenant={} tradeId={} symbol={} side={} contract={} correlationId={}",
                    n,
                    event.tenantId(),
                    event.tradeId(),
                    event.underlying(),
                    event.side(),
                    event.contractSymbol(),
                    event.correlationId());
        }
    }

    /** Test seam — exposes the running count without leaking the AtomicLong. */
    long count() {
        return counter.get();
    }
}
