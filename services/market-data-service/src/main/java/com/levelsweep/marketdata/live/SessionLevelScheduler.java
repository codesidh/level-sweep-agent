package com.levelsweep.marketdata.live;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.levels.LevelCalculator;
import com.levelsweep.marketdata.levels.SessionWindows;
import com.levelsweep.marketdata.persistence.LevelsRepository;
import com.levelsweep.marketdata.persistence.MongoBarRepository;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session-boundary scheduler that, once per trading day at 09:29:30 ET, reads the prior
 * RTH and overnight 1-min bars from Mongo, computes the four reference levels (PDH /
 * PDL / PMH / PML), and upserts the row into MS SQL via {@link LevelsRepository}.
 *
 * <p>Cron is fired by Quarkus' built-in scheduler (Quartz under the hood for cron
 * expressions). The job is safe to run when either datastore is unreachable: failures
 * are caught + logged, the scheduler keeps firing, and the next session's run picks
 * up. Weekends and holidays are detected by an empty bar-window — the job logs a
 * warning and returns.
 *
 * <p>The cron uses {@code timeZone = "America/New_York"} so DST transitions are
 * handled correctly. Quarkus 3.x supports Quartz-style 6-field expressions with
 * seconds — {@code "30 29 9 * * ?"} fires at 09:29:30 every day of every month.
 */
@ApplicationScoped
public class SessionLevelScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SessionLevelScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final MongoBarRepository bars;
    private final LevelsRepository levels;
    private final AlpacaConfig cfg;
    private final Clock clock;
    private final String tenantId;

    @Inject
    public SessionLevelScheduler(
            MongoBarRepository bars,
            LevelsRepository levels,
            AlpacaConfig cfg,
            Clock clock,
            @ConfigProperty(name = "tenant.id", defaultValue = "OWNER") String tenantId) {
        this.bars = Objects.requireNonNull(bars, "bars");
        this.levels = Objects.requireNonNull(levels, "levels");
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    }

    /**
     * Cron handler — fires at 09:29:30 ET daily (any day of week / month). The handler
     * itself short-circuits on weekends / holidays via the empty-bar check, so we don't
     * need the cron to know about market calendars.
     */
    @Scheduled(cron = "30 29 9 * * ?", timeZone = "America/New_York", identity = "compute-daily-levels")
    void computeAndPersist() {
        runOnce();
    }

    /**
     * Test-visible body. Idempotent on re-run for a given session date — {@link
     * LevelsRepository#upsert(Levels)} merges by {@code (tenantId, sessionDate)}.
     */
    void runOnce() {
        LocalDate today = LocalDate.now(clock.withZone(ET));
        String symbol = cfg.symbols().isEmpty() ? "SPY" : cfg.symbols().get(0);
        try {
            // RTH window for "today" is 09:30 ET — but at 09:29:30 ET we are computing
            // PDH/PDL from the *prior* trading day's RTH. Use sessionDate.minusDays(1)
            // for the RTH window; SessionWindows handles DST.
            SessionWindows.Window rth = SessionWindows.rth(today.minusDays(1));
            SessionWindows.Window overnight = SessionWindows.overnight(today);

            List<Bar> rthBars =
                    bars.findBarsByWindow(tenantId, symbol, Timeframe.ONE_MIN, rth.start(), rth.endExclusive());
            List<Bar> overnightBars = bars.findBarsByWindow(
                    tenantId, symbol, Timeframe.ONE_MIN, overnight.start(), overnight.endExclusive());

            if (rthBars.isEmpty() || overnightBars.isEmpty()) {
                LOG.warn(
                        "level computation skipped tenant={} session={} rth={} overnight={} bars (likely weekend/holiday or cold-start)",
                        tenantId,
                        today,
                        rthBars.size(),
                        overnightBars.size());
                return;
            }

            Levels lv = LevelCalculator.compute(tenantId, symbol, today, rthBars, overnightBars);
            levels.upsert(lv);
            LOG.info(
                    "levels persisted tenant={} session={} symbol={} pdh={} pdl={} pmh={} pml={}",
                    tenantId,
                    today,
                    symbol,
                    lv.pdh(),
                    lv.pdl(),
                    lv.pmh(),
                    lv.pml());
        } catch (RuntimeException e) {
            // Guardrail: this is a daily cron — a transient DB outage must not propagate
            // into the scheduler thread (which would suppress future fires).
            LOG.error("level computation failed tenant={} session={}: {}", tenantId, today, e.toString(), e);
        }
    }
}
