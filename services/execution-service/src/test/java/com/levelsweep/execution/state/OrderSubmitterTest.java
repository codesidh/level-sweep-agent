package com.levelsweep.execution.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.trade.OrderRequest;
import com.levelsweep.shared.domain.trade.OrderSubmission;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link OrderSubmitter} interface contract. The interface
 * itself is a single-method SPI; we exercise it via a no-op stub
 * implementation to verify the wiring contract: the EOD saga (S6) injects
 * {@code Instance<OrderSubmitter>} and pattern-matches on the
 * {@link OrderSubmission} sealed sum type, so we cover that the stub can be
 * constructed, the configured outcome round-trips, and constructor validation
 * behaves as expected.
 */
class OrderSubmitterTest {

    /** Test stub: returns a configured {@link OrderSubmission} verbatim. */
    static final class FixedOutcomeSubmitter implements OrderSubmitter {
        private final OrderSubmission outcome;

        FixedOutcomeSubmitter(OrderSubmission outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
        }

        @Override
        public OrderSubmission submit(OrderRequest request) {
            Objects.requireNonNull(request, "request");
            return outcome;
        }
    }

    private static OrderRequest sampleRequest() {
        return new OrderRequest(
                "OWNER",
                "trade-1",
                "SPY260430C00595000",
                1,
                OrderRequest.SIDE_BUY,
                OrderRequest.TYPE_LIMIT,
                Optional.of(new BigDecimal("1.50")),
                OrderRequest.TIF_DAY,
                OrderRequest.idempotencyKey("OWNER", "trade-1"));
    }

    @Test
    void stubReturnsConfiguredSubmittedOutcome() {
        OrderSubmission expected = new OrderSubmission.Submitted(
                "alp-ord-1", "OWNER:trade-1", "accepted", Instant.parse("2026-04-30T13:30:00Z"));
        OrderSubmitter submitter = new FixedOutcomeSubmitter(expected);

        OrderSubmission actual = submitter.submit(sampleRequest());

        assertThat(actual).isSameAs(expected);
        assertThat(actual).isInstanceOf(OrderSubmission.Submitted.class);
    }

    @Test
    void stubReturnsConfiguredRejectedOutcome() {
        OrderSubmission expected = new OrderSubmission.Rejected("OWNER:trade-1", 422, "duplicate client_order_id");
        OrderSubmitter submitter = new FixedOutcomeSubmitter(expected);

        OrderSubmission actual = submitter.submit(sampleRequest());

        assertThat(actual).isSameAs(expected);
        assertThat(actual).isInstanceOf(OrderSubmission.Rejected.class);
    }

    @Test
    void stubReturnsConfiguredFailedWithErrorOutcome() {
        OrderSubmission expected = new OrderSubmission.FailedWithError("OWNER:trade-1", "connect timed out");
        OrderSubmitter submitter = new FixedOutcomeSubmitter(expected);

        OrderSubmission actual = submitter.submit(sampleRequest());

        assertThat(actual).isSameAs(expected);
        assertThat(actual).isInstanceOf(OrderSubmission.FailedWithError.class);
    }

    @Test
    void stubConstructorRejectsNullOutcome() {
        assertThatThrownBy(() -> new FixedOutcomeSubmitter(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void submitRejectsNullRequest() {
        OrderSubmitter submitter =
                new FixedOutcomeSubmitter(new OrderSubmission.FailedWithError("OWNER:trade-1", "n/a"));

        assertThatThrownBy(() -> submitter.submit(null)).isInstanceOf(NullPointerException.class);
    }
}
