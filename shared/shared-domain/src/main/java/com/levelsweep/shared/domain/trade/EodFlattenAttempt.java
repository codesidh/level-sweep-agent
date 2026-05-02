package com.levelsweep.shared.domain.trade;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Audit row for a single in-flight trade processed by the EOD flatten saga
 * (Phase 3 Step 6). The saga writes one of these per (tenantId, tradeId,
 * sessionDate) tuple regardless of outcome: {@code FLATTENED} when the broker
 * accepted the market-sell exit, {@code NO_OP} when the cache was empty by
 * the time the row was constructed (race against an in-flight stop), and
 * {@code FAILED} when the broker call threw — the saga catches the exception
 * so a single failure does not stop later trades in the same fire from being
 * flattened.
 *
 * <p>Persisted to the {@code eod_flatten_attempts} table (Flyway V201 in
 * execution-service). Lives in shared-domain so both the saga and the
 * repository can share the record; the repository binds the optionals to
 * NULLABLE columns ({@code alpaca_order_id}, {@code failure_reason}).
 *
 * <p>{@code outcome} is a String rather than an enum to keep the record
 * schema-aligned and to give operators the freedom to introduce new outcomes
 * (e.g. {@code "PARTIAL"}) without a shared-domain release. Allowed values
 * are exposed as constants on the {@link Outcome} class for callers.
 */
public record EodFlattenAttempt(
        String tenantId,
        LocalDate sessionDate,
        Instant attemptedAt,
        String tradeId,
        String contractSymbol,
        String outcome,
        Optional<String> alpacaOrderId,
        Optional<String> failureReason) {

    /**
     * Canonical outcome strings. Held as constants (not an enum) because the
     * record persists the raw string and we want callers to share the same
     * spelling across the codebase. The repository's {@code outcome} column is
     * {@code VARCHAR(16)} — keep new values short.
     */
    public static final class Outcome {
        public static final String FLATTENED = "FLATTENED";
        public static final String NO_OP = "NO_OP";
        public static final String FAILED = "FAILED";

        private Outcome() {}
    }

    public EodFlattenAttempt {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(attemptedAt, "attemptedAt");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(contractSymbol, "contractSymbol");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
        Objects.requireNonNull(failureReason, "failureReason");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId must not be blank");
        }
        if (contractSymbol.isBlank()) {
            throw new IllegalArgumentException("contractSymbol must not be blank");
        }
        if (outcome.isBlank()) {
            throw new IllegalArgumentException("outcome must not be blank");
        }
    }
}
