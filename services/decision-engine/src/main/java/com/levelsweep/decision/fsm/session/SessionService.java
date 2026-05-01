package com.levelsweep.decision.fsm.session;

import com.levelsweep.decision.fsm.persistence.FsmTransitionRepository;
import com.levelsweep.shared.fsm.FsmTransition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service-level coordinator for the {@link SessionFsm}. Holds the per-tenant current
 * state in memory, calls the pure FSM to compute the next state, and persists every
 * accepted transition through {@link FsmTransitionRepository}. {@link Clock} is
 * injected so tests can step time deterministically.
 *
 * <p>Blackout resume: {@link SessionEvent#NEWS_BLACKOUT_END} is not handled by the
 * pure FSM (resume target is ambiguous from state alone). The service tracks the
 * pre-blackout state in a side map and applies it when the blackout clears, then
 * persists the transition with the {@code NEWS_BLACKOUT_END} event so the audit row
 * is preserved.
 */
@ApplicationScoped
public class SessionService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);

    /** ET zone — session timeline is wall-clock-anchored per requirements.md §15. */
    public static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    private final SessionFsm fsm;
    private final FsmTransitionRepository repository;
    private final Clock clock;

    private final Map<String, SessionState> currentState = new HashMap<>();
    private final Map<String, SessionState> preBlackoutState = new HashMap<>();

    @Inject
    public SessionService(SessionFsm fsm, FsmTransitionRepository repository, Clock clock) {
        this.fsm = Objects.requireNonNull(fsm, "fsm");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns the current state for the tenant, defaulting to PRE_MARKET. */
    public SessionState currentState(String tenantId) {
        return currentState.getOrDefault(tenantId, SessionState.PRE_MARKET);
    }

    /**
     * Apply an event to the tenant's session FSM. Returns the new state if the event
     * was legal, or {@link Optional#empty()} otherwise. Persists every accepted
     * transition.
     */
    public Optional<SessionState> apply(String tenantId, SessionEvent event, Optional<String> correlationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(event, "event");
        SessionState before = currentState(tenantId);
        SessionState after;
        if (event == SessionEvent.NEWS_BLACKOUT_END) {
            // Service-owned resume — the pure FSM declines this event.
            SessionState resume = preBlackoutState.remove(tenantId);
            if (before != SessionState.BLACKOUT || resume == null) {
                LOG.debug(
                        "blackout-end ignored tenantId={} currentState={} hasResume={}",
                        tenantId,
                        before,
                        resume != null);
                return Optional.empty();
            }
            after = resume;
        } else {
            Optional<SessionState> next = fsm.next(before, event);
            if (next.isEmpty()) {
                LOG.debug("invalid transition tenantId={} state={} event={}", tenantId, before, event);
                return Optional.empty();
            }
            after = next.get();
            if (event == SessionEvent.NEWS_BLACKOUT_START) {
                preBlackoutState.put(tenantId, before);
            }
        }
        currentState.put(tenantId, after);
        persist(tenantId, before, after, event, correlationId);
        return Optional.of(after);
    }

    private void persist(
            String tenantId, SessionState from, SessionState to, SessionEvent event, Optional<String> correlationId) {
        Instant now = clock.instant();
        LocalDate sessionDate = now.atZone(ET_ZONE).toLocalDate();
        FsmTransition<SessionState, SessionEvent> transition = new FsmTransition<>(
                tenantId,
                sessionDate,
                fsm.fsmKind(),
                sessionDate.toString(),
                fsm.fsmVersion(),
                Optional.of(from),
                to,
                event,
                now,
                Optional.empty(),
                correlationId);
        repository.record(transition);
    }
}
