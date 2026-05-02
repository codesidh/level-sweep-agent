package com.levelsweep.execution.replay;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Sealed family of fake post-{@code TradeProposed} events the replay harness
 * dispatches to drive a scenario through the (eventually-complete) execution
 * pipeline. Each scenario in {@link ExecutionScenarios} is a {@link TradeProposed}
 * input plus an ordered list of {@link SimulatedEvent}s.
 *
 * <p>Today only the proposed event is observed end-to-end; the rest of the
 * pipeline lands in parallel sub-agents (S2 Alpaca client, S3 fill listener,
 * S5 trail manager, S6 EOD flatten). When those land, this harness's dispatch
 * logic in {@link ExecutionReplayPipeline} fleshes out the per-event handlers.
 *
 * <h3>Determinism</h3>
 *
 * Every variant is a record (structural equality) and carries no clock data
 * beyond what the fixture supplies — no {@link Instant#now()} anywhere. The
 * harness's two-run byte-equality assert depends on this.
 */
public sealed interface SimulatedEvent
        permits SimulatedEvent.OrderSubmissionResponse,
                SimulatedEvent.FillFrame,
                SimulatedEvent.StopBreach,
                SimulatedEvent.EodTrigger {

    /**
     * Synthetic Alpaca order-submission outcome. {@code acceptFillPrice} is
     * present for the happy path (the order placement was accepted with a
     * confirming fill price); {@code rejectStatus} is present for failure
     * paths (e.g. 422 / Rejected). Exactly one of the two fields is non-null.
     *
     * <p>Once S2's {@code OrderPlacingTradeRouter} lands, the pipeline will
     * consume this fixture instead of producing it — for now it's a value type
     * the harness uses to drive its own captures.
     */
    record OrderSubmissionResponse(BigDecimal acceptFillPrice, Integer rejectStatus) implements SimulatedEvent {
        public OrderSubmissionResponse {
            // Exactly one of the two must be populated.
            if ((acceptFillPrice == null) == (rejectStatus == null)) {
                throw new IllegalArgumentException("exactly one of acceptFillPrice or rejectStatus must be set: accept="
                        + acceptFillPrice + " reject=" + rejectStatus);
            }
            if (acceptFillPrice != null && acceptFillPrice.signum() < 0) {
                throw new IllegalArgumentException("acceptFillPrice must be non-negative: " + acceptFillPrice);
            }
            if (rejectStatus != null && (rejectStatus < 400 || rejectStatus >= 600)) {
                throw new IllegalArgumentException("rejectStatus must be a 4xx/5xx HTTP status: " + rejectStatus);
            }
        }

        public static OrderSubmissionResponse accepted(BigDecimal fillPrice) {
            return new OrderSubmissionResponse(Objects.requireNonNull(fillPrice, "fillPrice"), null);
        }

        public static OrderSubmissionResponse rejected(int status) {
            return new OrderSubmissionResponse(null, status);
        }

        public boolean isAccepted() {
            return acceptFillPrice != null;
        }
    }

    /**
     * Synthetic Alpaca trade-update frame representing a fill. The S3 fill
     * listener will translate the on-wire JSON to this shape — for replay we
     * synthesize it directly.
     */
    record FillFrame(BigDecimal filledAvgPrice, int filledQty, String alpacaEvent) implements SimulatedEvent {
        public FillFrame {
            Objects.requireNonNull(filledAvgPrice, "filledAvgPrice");
            Objects.requireNonNull(alpacaEvent, "alpacaEvent");
            if (filledAvgPrice.signum() < 0) {
                throw new IllegalArgumentException("filledAvgPrice must be non-negative: " + filledAvgPrice);
            }
            if (filledQty <= 0) {
                throw new IllegalArgumentException("filledQty must be positive: " + filledQty);
            }
            if (alpacaEvent.isBlank()) {
                throw new IllegalArgumentException("alpacaEvent must not be blank");
            }
        }
    }

    /**
     * Synthetic underlying-price stop trigger. Drives the S5 trail manager;
     * for now the harness records the breach event without acting on it.
     */
    record StopBreach(BigDecimal currentPrice, Instant when) implements SimulatedEvent {
        public StopBreach {
            Objects.requireNonNull(currentPrice, "currentPrice");
            Objects.requireNonNull(when, "when");
            if (currentPrice.signum() < 0) {
                throw new IllegalArgumentException("currentPrice must be non-negative: " + currentPrice);
            }
        }
    }

    /**
     * Synthetic 15:55 ET cron firing — the S6 EOD flatten saga's input. The
     * harness records EOD attempts so the test can assert deterministic
     * end-of-day handling once S6 lands.
     */
    record EodTrigger(Instant when) implements SimulatedEvent {
        public EodTrigger {
            Objects.requireNonNull(when, "when");
        }
    }
}
