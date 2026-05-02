package com.levelsweep.shared.domain.trade;

import java.time.Instant;
import java.util.Objects;

/**
 * Outcome of submitting an {@link OrderRequest} to the broker. Sealed sum type
 * — the entry-order saga step pattern-matches and never sees an exception for
 * an expected reject. Three permitted shapes:
 *
 * <ul>
 *   <li>{@link Submitted} — broker accepted; {@code alpacaOrderId} is the
 *       broker's server-side order id, {@code clientOrderId} round-trips the
 *       caller-supplied idempotency key, {@code status} is the broker's
 *       lifecycle status (e.g. {@code accepted}, {@code accepted_for_bidding}),
 *       and {@code submittedAt} is the wall-clock instant of acceptance.
 *   <li>{@link Rejected} — broker reachable but refused (4xx / 5xx). Carries
 *       the HTTP status and a human-readable {@code reason} (typically the
 *       broker's response body, redacted of credentials).
 *   <li>{@link FailedWithError} — transport / parse failure (IOException,
 *       malformed 2xx body). Carries the exception message; the underlying
 *       throwable is logged at the call site.
 * </ul>
 *
 * <p>{@code clientOrderId} is on every variant so a downstream observer can
 * correlate the outcome back to the request without keeping a side-table.
 *
 * <p>This sum type replaces the older "throw on failure" contract — broker
 * rejection is data, not error, and the sealed shape makes the entry-order
 * saga's three failure paths explicit and exhaustive at compile time.
 */
public sealed interface OrderSubmission
        permits OrderSubmission.Submitted, OrderSubmission.Rejected, OrderSubmission.FailedWithError {

    /**
     * Caller-supplied idempotency key (typically {@code "<tenantId>:<tradeId>"}
     * for entries or {@code "eod:<tenantId>:<tradeId>"} for EOD flattens).
     * Round-tripped on every variant so observers can correlate without a
     * side-table.
     */
    String clientOrderId();

    /** Broker accepted the order. */
    record Submitted(String alpacaOrderId, String clientOrderId, String status, Instant submittedAt)
            implements OrderSubmission {
        public Submitted {
            Objects.requireNonNull(alpacaOrderId, "alpacaOrderId");
            Objects.requireNonNull(clientOrderId, "clientOrderId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(submittedAt, "submittedAt");
            if (alpacaOrderId.isBlank()) {
                throw new IllegalArgumentException("alpacaOrderId must not be blank");
            }
            if (clientOrderId.isBlank()) {
                throw new IllegalArgumentException("clientOrderId must not be blank");
            }
        }
    }

    /** Broker reachable but refused the request (4xx / 5xx). */
    record Rejected(String clientOrderId, int httpStatus, String reason) implements OrderSubmission {
        public Rejected {
            Objects.requireNonNull(clientOrderId, "clientOrderId");
            Objects.requireNonNull(reason, "reason");
            if (clientOrderId.isBlank()) {
                throw new IllegalArgumentException("clientOrderId must not be blank");
            }
        }
    }

    /** Transport / parse failure — no broker response we can trust. */
    record FailedWithError(String clientOrderId, String exceptionMessage) implements OrderSubmission {
        public FailedWithError {
            Objects.requireNonNull(clientOrderId, "clientOrderId");
            Objects.requireNonNull(exceptionMessage, "exceptionMessage");
            if (clientOrderId.isBlank()) {
                throw new IllegalArgumentException("clientOrderId must not be blank");
            }
        }
    }
}
