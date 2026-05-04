package com.levelsweep.aiagent.sentinel;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Sealed outcome of a Pre-Trade Sentinel evaluation (ADR-0007 §1 + §3).
 *
 * <p>Three variants:
 *
 * <ul>
 *   <li>{@link Allow} — the saga proceeds to StrikeSelector. Carries a
 *       {@link DecisionPath} so the audit log distinguishes between an
 *       explicit ALLOW from the model, a low-confidence VETO that was
 *       overridden by the 0.85 threshold, and a fail-OPEN fallback.</li>
 *   <li>{@link Veto} — the saga compensates with reason {@code sentinel_veto}
 *       and the trade never reaches the broker. Confidence MUST be
 *       {@code >= 0.85} (ADR-0007 §2); the parser/orchestrator demote
 *       lower-confidence vetoes to {@link Allow} with
 *       {@link DecisionPath#LOW_CONFIDENCE_VETO_OVERRIDDEN}.</li>
 *   <li>{@link Fallback} — fail-OPEN catch-all (transport, rate limit, cost
 *       cap, parse error, timeout, CB open). The saga interprets this as
 *       ALLOW per ADR-0007 §3; this is the deliberate "do not silently halt
 *       the system on AI outage" posture.</li>
 * </ul>
 *
 * <p>The exhaustive {@code switch} on the variant in the saga forces the
 * caller to handle every documented outcome — no hidden default.
 */
public sealed interface SentinelDecisionResponse
        permits SentinelDecisionResponse.Allow, SentinelDecisionResponse.Veto, SentinelDecisionResponse.Fallback {

    /** Stable per-call id for the audit log + observability join key. */
    String clientRequestId();

    /** Round-trip latency observed by the orchestrator (cap-pre-flight + HTTP + parse). */
    long latencyMs();

    /** ADR-0007 §1 Veto threshold. Lower-confidence vetoes are demoted to Allow. */
    BigDecimal VETO_CONFIDENCE_THRESHOLD = new BigDecimal("0.85");

    /** Cap on the audit-only {@code reason_text} field (ADR-0007 §1). */
    int REASON_TEXT_MAX_LENGTH = 280;

    /** Why the model said what it said. Closed set — anything else is OTHER. */
    enum ReasonCode {
        STRUCTURE_MATCH,
        STRUCTURE_DIVERGENCE,
        REGIME_MISALIGNED,
        RECENT_LOSSES,
        LOW_LIQUIDITY_PROFILE,
        OTHER
    }

    /**
     * Audit-log differentiator on {@link Allow}. {@code EXPLICIT_ALLOW} for
     * a model-issued ALLOW; {@code LOW_CONFIDENCE_VETO_OVERRIDDEN} for a
     * VETO with confidence below the threshold; {@code FALLBACK_ALLOW} for
     * a fail-OPEN substitute (only seen on {@link Fallback} when the saga
     * coerces it).
     */
    enum DecisionPath {
        EXPLICIT_ALLOW,
        LOW_CONFIDENCE_VETO_OVERRIDDEN,
        FALLBACK_ALLOW
    }

    /** Closed set of fail-OPEN reasons — matches the ADR-0007 §3 table. */
    enum FallbackReason {
        TRANSPORT,
        RATE_LIMIT,
        COST_CAP,
        PARSE,
        TIMEOUT,
        CB_OPEN
    }

    /**
     * Saga proceeds to StrikeSelector. The {@link DecisionPath} discriminates
     * the three audit-log variants: explicit model ALLOW, low-confidence VETO
     * that was overridden, or a fail-OPEN coerced ALLOW.
     */
    record Allow(
            String clientRequestId,
            BigDecimal confidence,
            ReasonCode reasonCode,
            String reasonText,
            long latencyMs,
            DecisionPath decisionPath)
            implements SentinelDecisionResponse {
        public Allow {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(reasonText, "reasonText");
            Objects.requireNonNull(decisionPath, "decisionPath");
            if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("confidence must be in [0, 1]: " + confidence);
            }
            if (reasonText.length() > REASON_TEXT_MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "reasonText exceeds " + REASON_TEXT_MAX_LENGTH + " chars: " + reasonText.length());
            }
        }
    }

    /**
     * Saga compensates with reason {@code sentinel_veto}. The compact
     * constructor enforces {@code confidence >= 0.85} — lower-confidence
     * vetoes are not representable here and must be demoted to {@link Allow}
     * with {@link DecisionPath#LOW_CONFIDENCE_VETO_OVERRIDDEN} by the parser.
     */
    record Veto(String clientRequestId, BigDecimal confidence, ReasonCode reasonCode, String reasonText, long latencyMs)
            implements SentinelDecisionResponse {
        public Veto {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(reasonText, "reasonText");
            if (confidence.compareTo(VETO_CONFIDENCE_THRESHOLD) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "Veto confidence must be in [" + VETO_CONFIDENCE_THRESHOLD + ", 1]: " + confidence);
            }
            if (reasonText.length() > REASON_TEXT_MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "reasonText exceeds " + REASON_TEXT_MAX_LENGTH + " chars: " + reasonText.length());
            }
        }
    }

    /**
     * Fail-OPEN — the saga interprets this as ALLOW (ADR-0007 §3). Carries
     * just the fallback reason for the metrics counter
     * {@code ai.sentinel.fallback{reason="..."}} and the audit row.
     */
    record Fallback(String clientRequestId, FallbackReason reason, long latencyMs) implements SentinelDecisionResponse {
        public Fallback {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
