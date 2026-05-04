package com.levelsweep.aiagent.sentinel;

import com.levelsweep.aiagent.anthropic.AnthropicMessage;
import com.levelsweep.aiagent.anthropic.AnthropicRequest;
import com.levelsweep.aiagent.anthropic.AnthropicRequest.Role;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Bar;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.IndicatorSnapshot;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.RecentTrade;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds an {@link AnthropicRequest} from a {@link SentinelDecisionRequest}.
 *
 * <p><b>Determinism contract</b> (ADR-0007 §5 + CLAUDE.md guardrail #5): same
 * input → byte-identical {@link AnthropicRequest#systemPrompt()} +
 * user-message text. Concretely:
 *
 * <ul>
 *   <li>All numeric values formatted via {@link BigDecimal#setScale(int,
 *       RoundingMode)} with {@link RoundingMode#HALF_EVEN} so banker's-
 *       rounding produces the same digits across JVM/locale variations.</li>
 *   <li>All collection iteration in input order — {@link List#copyOf} on the
 *       request side already locks insertion order.</li>
 *   <li>{@link Instant} serialized at second precision via
 *       {@code .truncatedTo(ChronoUnit.SECONDS).toString()}; sub-second jitter
 *       across replay runs cannot leak into the prompt bytes.</li>
 * </ul>
 *
 * <p>The {@link #renderUserMessage(SentinelDecisionRequest)} method is the
 * package-public test seam — callers go through {@link #build} but tests
 * compare the rendered string directly.
 */
@ApplicationScoped
public class SentinelPromptBuilder {

    /** Cap on the response — small JSON body per ADR-0007 §1. */
    public static final int MAX_TOKENS = 256;

    /**
     * Pre-flight projected cost for the Sentinel call. Haiku 4.5 is the
     * cheapest tier (~ $1/MT input, ~ $5/MT output per ADR-0007 §6); a
     * 500-input + 100-output prompt is ~ $0.001. We carry that constant as
     * the projection — the post-call reconciliation in
     * {@link com.levelsweep.aiagent.cost.DailyCostTracker} replaces this
     * with the actual cost once Anthropic returns usage tokens.
     */
    public static final BigDecimal PROJECTED_COST_USD = new BigDecimal("0.001");

    /** Decimal places for price-like values (close, EMAs, ATR). Two cents. */
    private static final int PRICE_SCALE = 2;

    /** Decimal places for RSI / VIX-style values — two places matches the wire. */
    private static final int INDICATOR_SCALE = 2;

    /** Decimal places for R-multiple — single decimal is enough audit-side. */
    private static final int R_MULTIPLE_SCALE = 1;

    private static final String SYSTEM_PROMPT =
            """
            You are a pre-trade Sentinel for a 0DTE SPY options trader. Your job is to evaluate the proposed trade against indicator + recent-history context and return ALLOW or VETO with confidence (0.00-1.00 to two decimals). Default on uncertainty = ALLOW. Confidence < 0.85 means do NOT veto.

            Output STRICT JSON only — no preamble, no markdown, no commentary:
            {"decision": "ALLOW|VETO", "confidence": 0.NN, "reason_code": "STRUCTURE_MATCH|STRUCTURE_DIVERGENCE|REGIME_MISALIGNED|RECENT_LOSSES|LOW_LIQUIDITY_PROFILE|OTHER", "reason_text": "..."}

            reason_text must be <= 280 chars and audit-only (never re-fed to a downstream prompt). Be concise.""";

    private final String model;

    public SentinelPromptBuilder(
            @ConfigProperty(name = "anthropic.models.sentinel", defaultValue = "claude-haiku-4-5") String model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /** Build the {@link AnthropicRequest} for one Sentinel call. */
    public AnthropicRequest build(SentinelDecisionRequest request) {
        Objects.requireNonNull(request, "request");
        return new AnthropicRequest(
                model,
                SYSTEM_PROMPT,
                List.of(AnthropicMessage.user(renderUserMessage(request))),
                List.of(),
                MAX_TOKENS,
                /* temperature */ 0.0d,
                request.tenantId(),
                Role.SENTINEL,
                PROJECTED_COST_USD);
    }

    /**
     * Render the user-side text. Package-public for test determinism
     * comparison — production callers go through {@link #build}.
     */
    public String renderUserMessage(SentinelDecisionRequest r) {
        Objects.requireNonNull(r, "request");
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Evaluate this proposed 0DTE SPY trade.\n\n");
        sb.append("tenant_id=").append(r.tenantId()).append('\n');
        sb.append("trade_id=").append(r.tradeId()).append('\n');
        sb.append("signal_id=").append(r.signalId()).append('\n');
        sb.append("direction=").append(r.direction().name()).append('\n');
        sb.append("level_swept=").append(r.levelSwept().name()).append('\n');
        sb.append("now_utc=").append(formatInstant(r.nowUtc())).append('\n');
        sb.append("vix_close_prev=")
                .append(formatScale(r.vixClosePrev(), INDICATOR_SCALE))
                .append('\n');
        sb.append('\n');
        sb.append(renderIndicatorSection(r.indicatorSnapshot()));
        sb.append('\n');
        sb.append(renderRecentTradesSection(r.recentTradesWindow()));
        sb.append('\n');
        sb.append("Return strict JSON only. Default on uncertainty = ALLOW. confidence < 0.85 means do NOT veto.");
        return sb.toString();
    }

    private static String renderIndicatorSection(IndicatorSnapshot s) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("indicators:\n");
        sb.append("  ema13=").append(formatScale(s.ema13(), PRICE_SCALE)).append('\n');
        sb.append("  ema48=").append(formatScale(s.ema48(), PRICE_SCALE)).append('\n');
        sb.append("  ema200=").append(formatScale(s.ema200(), PRICE_SCALE)).append('\n');
        sb.append("  atr14=").append(formatScale(s.atr14(), PRICE_SCALE)).append('\n');
        sb.append("  rsi2=").append(formatScale(s.rsi2(), INDICATOR_SCALE)).append('\n');
        sb.append("  regime=").append(s.regime()).append('\n');
        sb.append("recent_bars (oldest→newest, 2-min):\n");
        if (s.recentBars().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (Bar b : s.recentBars()) {
                sb.append("  ts=")
                        .append(formatInstant(b.ts()))
                        .append(" close=")
                        .append(formatScale(b.close(), PRICE_SCALE))
                        .append(" volume=")
                        .append(b.volume())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static String renderRecentTradesSection(List<RecentTrade> trades) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("recent_trades (oldest→newest):\n");
        if (trades.isEmpty()) {
            sb.append("  (none)\n");
            return sb.toString();
        }
        for (RecentTrade t : trades) {
            sb.append("  trade_id=")
                    .append(t.tradeId())
                    .append(" outcome=")
                    .append(t.outcome().name())
                    .append(" r_multiple=")
                    .append(formatScale(t.rMultiple(), R_MULTIPLE_SCALE))
                    .append(" ts=")
                    .append(formatInstant(t.ts()))
                    .append('\n');
        }
        return sb.toString();
    }

    /** Banker's rounding to a fixed scale — locale-independent. */
    private static String formatScale(BigDecimal v, int scale) {
        return v.setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Truncate to seconds so sub-second jitter cannot leak into the prompt bytes. */
    private static String formatInstant(Instant t) {
        return t.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Exposed for tests — verifies the system prompt copy is checked-in verbatim. */
    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }
}
