package com.levelsweep.aiagent;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI Agent Service entry point.
 *
 * <p>Phase 0 placeholder. Hosts four roles (architecture-spec §4.3):
 *
 * <ul>
 *   <li>Pre-Trade Sentinel (Claude Haiku 4.5) — advisory veto, confidence ≥ 0.85</li>
 *   <li>Trade Narrator (Claude Sonnet 4.6) — post-trade narratives</li>
 *   <li>Conversational Assistant (Claude Sonnet 4.6) — read-only chat</li>
 *   <li>Daily Reviewer (Claude Opus 4.7) — 16:30 ET daily review</li>
 * </ul>
 *
 * <p>HARD RULE: the AI cannot place orders. The only write into the trade saga is the
 * Sentinel veto channel.
 */
@QuarkusMain
public class Application implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        Quarkus.run(Application.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info("ai-agent-service starting (Phase 0 hello-world)");
        Quarkus.waitForExit();
        return 0;
    }
}
