package com.levelsweep.decision.sentinel;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Sealed outcome returned by {@link SentinelClient#evaluate}. Mirrors the
 * shape of the ai-agent-service {@code SentinelDecisionResponse} but stays
 * decoupled — no Maven dep on ai-agent-service from decision-engine
 * (ADR-0007 §5).
 *
 * <p>Three variants:
 *
 * <ul>
 *   <li>{@link Allow} — saga proceeds to StrikeSelector. Carries the
 *       {@link DecisionPath} so the saga's audit log distinguishes between
 *       an explicit ALLOW from the model, a low-confidence VETO that was
 *       overridden, or a fail-OPEN substitute.</li>
 *   <li>{@link Veto} — saga compensates with reason {@code sentinel_veto}.
 *       Confidence MUST be {@code >= 0.85} (ADR-0007 §2).</li>
 *   <li>{@link Fallback} — fail-OPEN catch-all. Saga proceeds (treated as
 *       ALLOW) per ADR-0007 §3.</li>
 * </ul>
 */
public sealed interface SentinelClientResult
        permits SentinelClientResult.Allow, SentinelClientResult.Veto, SentinelClientResult.Fallback {

    /** Stable per-call id from the remote service. May be empty for client-side fallbacks. */
    String clientRequestId();

    /** Round-trip latency observed by the client (HTTP send + remote eval + parse). */
    long latencyMs();

    /** ADR-0007 §1 Veto confidence threshold. */
    BigDecimal VETO_CONFIDENCE_THRESHOLD = new BigDecimal("0.85");

    /** Closed reason set the model produces. */
    enum ReasonCode {
        STRUCTURE_MATCH,
        STRUCTURE_DIVERGENCE,
        REGIME_MISALIGNED,
        RECENT_LOSSES,
        LOW_LIQUIDITY_PROFILE,
        OTHER
    }

    /** Audit-log differentiator on Allow. */
    enum DecisionPath {
        EXPLICIT_ALLOW,
        LOW_CONFIDENCE_VETO_OVERRIDDEN,
        FALLBACK_ALLOW
    }

    /** Closed set of fail-OPEN reasons (ADR-0007 §3). */
    enum FallbackReason {
        TRANSPORT,
        TIMEOUT,
        PARSE,
        RATE_LIMIT,
        COST_CAP,
        CB_OPEN
    }

    record Allow(
            String clientRequestId,
            BigDecimal confidence,
            ReasonCode reasonCode,
            String reasonText,
            long latencyMs,
            DecisionPath decisionPath)
            implements SentinelClientResult {
        public Allow {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(reasonText, "reasonText");
            Objects.requireNonNull(decisionPath, "decisionPath");
            if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("confidence must be in [0, 1]: " + confidence);
            }
        }
    }

    record Veto(String clientRequestId, BigDecimal confidence, ReasonCode reasonCode, String reasonText, long latencyMs)
            implements SentinelClientResult {
        public Veto {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(reasonText, "reasonText");
            if (confidence.compareTo(VETO_CONFIDENCE_THRESHOLD) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "Veto confidence must be in [" + VETO_CONFIDENCE_THRESHOLD + ", 1]: " + confidence);
            }
        }
    }

    record Fallback(String clientRequestId, FallbackReason reason, long latencyMs) implements SentinelClientResult {
        public Fallback {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
