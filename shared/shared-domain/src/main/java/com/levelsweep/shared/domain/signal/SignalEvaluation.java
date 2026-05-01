package com.levelsweep.shared.domain.signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Output of the Signal Engine — a fully-self-describing record of a single
 * {@code (Bar, IndicatorSnapshot, Levels)} evaluation. Either the bar fired a
 * trade setup ({@link SignalAction#ENTER_LONG} / {@link SignalAction#ENTER_SHORT})
 * or it was skipped ({@link SignalAction#SKIP}); in either case the
 * {@link #reasons} list is the audit trail describing why.
 *
 * <p>This record is the contract between the Signal Engine (Phase 2 Step 2)
 * and the Trade Saga (Phase 2 Step 3 / S6). It is deliberately self-contained —
 * it carries the level price at evaluation time so downstream consumers do not
 * have to re-look-up the {@code Levels} record. Per {@code requirements.md} §6
 * and §8, the audit trail is required for both the trade journal (§18 acceptance
 * criterion 7) and replay parity (criterion 8).
 *
 * <p>Invariants enforced in the canonical constructor:
 *
 * <ul>
 *   <li>Non-{@code SKIP} actions must carry {@link #level}, {@link #optionSide},
 *       and {@link #levelPrice} (all three present)
 *   <li>{@code ENTER_LONG} → {@code CALL}; {@code ENTER_SHORT} → {@code PUT}
 *   <li>{@link #reasons} is defensively copied via {@link List#copyOf(java.util.Collection)}
 *       and therefore unmodifiable; ordering is preserved (deterministic for replay)
 * </ul>
 *
 * <p>Use the static factories — {@link #skip} and {@link #enter} — rather than
 * the canonical constructor for clarity at call sites.
 */
public record SignalEvaluation(
        String tenantId,
        String symbol,
        Instant evaluatedAt,
        SignalAction action,
        Optional<SweptLevel> level,
        Optional<OptionSide> optionSide,
        Optional<BigDecimal> levelPrice,
        List<String> reasons) {

    public SignalEvaluation {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(optionSide, "optionSide");
        Objects.requireNonNull(levelPrice, "levelPrice");
        Objects.requireNonNull(reasons, "reasons");

        // Defensive copy — preserves ordering (deterministic) and produces an
        // unmodifiable list so callers cannot mutate the audit trail.
        reasons = List.copyOf(reasons);

        if (action == SignalAction.SKIP) {
            if (level.isPresent() || optionSide.isPresent() || levelPrice.isPresent()) {
                throw new IllegalArgumentException(
                        "SKIP must have empty level/optionSide/levelPrice; got level="
                                + level + " side=" + optionSide + " price=" + levelPrice);
            }
        } else {
            if (level.isEmpty() || optionSide.isEmpty() || levelPrice.isEmpty()) {
                throw new IllegalArgumentException(
                        "non-SKIP action " + action + " requires level, optionSide, and levelPrice");
            }
            OptionSide expected = action == SignalAction.ENTER_LONG ? OptionSide.CALL : OptionSide.PUT;
            if (optionSide.get() != expected) {
                throw new IllegalArgumentException(
                        "action " + action + " requires optionSide=" + expected + " but got " + optionSide.get());
            }
        }
    }

    /**
     * Construct a skip evaluation. The {@code reasons} list must be non-empty —
     * a skip without a recorded reason violates the audit-trail contract.
     */
    public static SignalEvaluation skip(
            String tenantId, String symbol, Instant evaluatedAt, List<String> reasons) {
        Objects.requireNonNull(reasons, "reasons");
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("skip must carry at least one reason");
        }
        return new SignalEvaluation(
                tenantId,
                symbol,
                evaluatedAt,
                SignalAction.SKIP,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                reasons);
    }

    /**
     * Construct a fire-the-trade evaluation. {@code action} must be
     * {@link SignalAction#ENTER_LONG} or {@link SignalAction#ENTER_SHORT}; pass
     * {@code SKIP} and you'll trip the canonical constructor's invariant check.
     */
    public static SignalEvaluation enter(
            String tenantId,
            String symbol,
            Instant evaluatedAt,
            SignalAction action,
            SweptLevel level,
            OptionSide optionSide,
            BigDecimal levelPrice,
            List<String> reasons) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(optionSide, "optionSide");
        Objects.requireNonNull(levelPrice, "levelPrice");
        return new SignalEvaluation(
                tenantId,
                symbol,
                evaluatedAt,
                action,
                Optional.of(level),
                Optional.of(optionSide),
                Optional.of(levelPrice),
                reasons);
    }
}
