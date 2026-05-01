package com.levelsweep.decision.strike;

import com.levelsweep.shared.domain.options.OptionContract;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.options.StrikeSelectionResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Wires {@link AlpacaOptionsClient} → {@link StrikeSelector}: fetches the
 * 0DTE chain on demand and feeds it into the pure selector. The Trade Saga
 * (S6) calls {@link #selectFor} synchronously when a signal fires — this
 * service does not own a schedule or a Kafka consumer in Phase 2.
 */
@ApplicationScoped
public class StrikeSelectorService {

    private final AlpacaOptionsClient client;
    private final StrikeSelector selector;

    @Inject
    public StrikeSelectorService(AlpacaOptionsClient client, StrikeSelector selector) {
        this.client = Objects.requireNonNull(client, "client");
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    /**
     * Pull the chain for {@code underlying} and run the selector against it.
     * Returns the sealed selector result; the caller is responsible for
     * surfacing {@code NoCandidates} as a "no_trade_today" decision.
     */
    public StrikeSelectionResult selectFor(
            String underlying, BigDecimal spot, OptionSide side, LocalDate today) {
        Objects.requireNonNull(underlying, "underlying");
        Objects.requireNonNull(spot, "spot");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(today, "today");
        List<OptionContract> chain = client.fetchChain(underlying);
        return selector.select(spot, side, chain, today);
    }
}
