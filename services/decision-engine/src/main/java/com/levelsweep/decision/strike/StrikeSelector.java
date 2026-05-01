package com.levelsweep.decision.strike;

import com.levelsweep.shared.domain.options.OptionContract;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.options.StrikeSelection;
import com.levelsweep.shared.domain.options.StrikeSelectionResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure 0DTE strike selector. Given a SPY spot price, an option side, an
 * Alpaca chain snapshot, and the trading-session date, picks the contract
 * closest to the money that passes the configured liquidity gate.
 *
 * <p>Algorithm (mirrors {@code requirements.md} §13 and the Phase-2 build
 * brief):
 *
 * <ol>
 *   <li>Filter the chain to {@code expiry == today} and matching {@link OptionSide}
 *       (0DTE-only, no calendar bleed).
 *   <li>Restrict to strikes within {@code ±atmBandDollars} of {@code spot}.
 *   <li>Reject candidates whose NBBO width fails the threshold:
 *       <ul>
 *         <li>{@code spreadPct() <= maxSpreadPct} AND
 *         <li>{@code spreadAbs() <= maxSpreadAbsDollars}.
 *       </ul>
 *       The §13 spec lists "≤ $0.10 absolute or 5% of mid" as a sensible
 *       default; we apply BOTH gates with the configurable defaults below
 *       (10% spread, $0.10 absolute). Either failure rejects the candidate.
 *   <li>Reject candidates whose {@code openInterest.orElse(0) < minOpenInterest}.
 *   <li>Sort survivors by distance to spot (asc), then spread% (asc).
 *   <li>Pick the head; on no survivors return
 *       {@link StrikeSelectionResult.NoCandidates}.
 * </ol>
 *
 * <p>This bean is stateless and reentrant; the only injected state is the
 * {@code @ConfigProperty} thresholds resolved at startup. {@code Instant.now()}
 * is intentionally not used — the caller passes {@code today} so replays and
 * unit tests are deterministic.
 */
@ApplicationScoped
public class StrikeSelector {

    private static final Logger LOG = LoggerFactory.getLogger(StrikeSelector.class);

    private final BigDecimal maxSpreadPct;
    private final BigDecimal maxSpreadAbsDollars;
    private final int minOpenInterest;
    private final BigDecimal atmBandDollars;

    @Inject
    public StrikeSelector(
            @ConfigProperty(name = "decision.strike.max-spread-pct", defaultValue = "10.0") BigDecimal maxSpreadPct,
            @ConfigProperty(name = "decision.strike.max-spread-abs", defaultValue = "0.10")
                    BigDecimal maxSpreadAbsDollars,
            @ConfigProperty(name = "decision.strike.min-open-interest", defaultValue = "100") int minOpenInterest,
            @ConfigProperty(name = "decision.strike.atm-band-dollars", defaultValue = "2.0")
                    BigDecimal atmBandDollars) {
        this.maxSpreadPct = Objects.requireNonNull(maxSpreadPct, "maxSpreadPct");
        this.maxSpreadAbsDollars = Objects.requireNonNull(maxSpreadAbsDollars, "maxSpreadAbsDollars");
        this.minOpenInterest = minOpenInterest;
        this.atmBandDollars = Objects.requireNonNull(atmBandDollars, "atmBandDollars");
    }

    /**
     * Choose a 0DTE contract from {@code chain} given {@code spot} and
     * {@code side}. Returns a sealed result; never {@code null}, never throws
     * on legitimate empty/illiquid inputs.
     */
    public StrikeSelectionResult select(BigDecimal spot, OptionSide side, List<OptionContract> chain, LocalDate today) {
        Objects.requireNonNull(spot, "spot");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(today, "today");

        if (chain.isEmpty()) {
            return new StrikeSelectionResult.NoCandidates("empty_chain");
        }

        // Step 1+2: same-day, same-side, in ATM band.
        List<OptionContract> banded = new ArrayList<>();
        for (OptionContract c : chain) {
            if (!c.expiry().equals(today)) continue;
            if (c.side() != side) continue;
            BigDecimal distance = c.strike().subtract(spot).abs();
            if (distance.compareTo(atmBandDollars) > 0) continue;
            banded.add(c);
        }
        if (banded.isEmpty()) {
            return new StrikeSelectionResult.NoCandidates("no_atm_band_match");
        }

        // Step 3+4: liquidity filter.
        List<OptionContract> survivors = new ArrayList<>();
        List<OptionContract> rejected = new ArrayList<>();
        for (OptionContract c : banded) {
            if (passesLiquidity(c)) {
                survivors.add(c);
            } else {
                rejected.add(c);
            }
        }
        if (survivors.isEmpty()) {
            LOG.debug("no strike passed liquidity: spot={} side={} considered={}", spot, side, banded.size());
            return new StrikeSelectionResult.NoCandidates("no_strike_passed_liquidity");
        }

        // Step 5: sort by closeness-to-spot, then by tighter spread.
        Comparator<OptionContract> byDistance =
                Comparator.comparing(c -> c.strike().subtract(spot).abs());
        Comparator<OptionContract> bySpreadPct = Comparator.comparing(OptionContract::spreadPct);
        survivors.sort(byDistance.thenComparing(bySpreadPct));

        OptionContract chosen = survivors.get(0);
        String reason = buildReason(chosen, spot);
        LOG.debug(
                "strike selected: symbol={} strike={} spot={} side={} survivors={} rejected={}",
                chosen.symbol(),
                chosen.strike(),
                spot,
                side,
                survivors.size(),
                rejected.size());
        return new StrikeSelectionResult.Selected(
                new StrikeSelection(chosen, reason, Collections.unmodifiableList(rejected)));
    }

    private boolean passesLiquidity(OptionContract c) {
        if (c.spreadPct().compareTo(maxSpreadPct) > 0) return false;
        if (c.spreadAbs().compareTo(maxSpreadAbsDollars) > 0) return false;
        int oi = c.openInterest().orElse(0);
        return oi >= minOpenInterest;
    }

    private static String buildReason(OptionContract chosen, BigDecimal spot) {
        BigDecimal distance = chosen.strike().subtract(spot).abs();
        return "atm_band_distance="
                + distance.toPlainString()
                + ";spread_pct="
                + chosen.spreadPct().toPlainString();
    }
}
