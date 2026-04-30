package com.levelsweep.decision;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decision Engine entry point.
 *
 * <p>Phase 0 placeholder. Phase 2 wires Indicator Engine + Signal Engine + Risk Manager
 * + Strike Selector + Trade Saga (architecture-spec §9.2 / §11). All FSMs (Session, Risk,
 * Trade, Order, Position) live here.
 *
 * <p>The AI Sentinel veto channel (architecture-spec §4.3.1) is the only inbound
 * AI write; everything else is deterministic.
 */
@QuarkusMain
public class Application implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        Quarkus.run(Application.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info("decision-engine starting (Phase 0 hello-world)");
        Quarkus.waitForExit();
        return 0;
    }
}
