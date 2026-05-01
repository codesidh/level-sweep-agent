package com.levelsweep.decision.fsm.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.decision.fsm.persistence.FsmTransitionRepository;
import com.levelsweep.shared.fsm.FsmTransition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies the service-level coordinator drives the pure FSM correctly and persists
 * each accepted transition (and only those). Uses a hand-rolled recording repository
 * — Mockito is not on this module's test classpath.
 */
class SessionServiceTest {

    private static final String TENANT = "OWNER";

    private final SessionFsm fsm = new SessionFsm();
    private final RecordingRepo repo = new RecordingRepo();
    private final Clock fixed = Clock.fixed(Instant.parse("2026-04-30T13:30:00Z"), ZoneOffset.UTC);
    private final SessionService service = new SessionService(fsm, repo, fixed);

    @Test
    void newTenantStartsInPreMarket() {
        assertThat(service.currentState(TENANT)).isEqualTo(SessionState.PRE_MARKET);
    }

    @Test
    void happyPathAcrossTheSessionPersistsEveryTransition() {
        assertThat(service.apply(TENANT, SessionEvent.LEVELS_READY, Optional.empty())).contains(SessionState.ARMED);
        assertThat(service.apply(TENANT, SessionEvent.MARKET_OPEN, Optional.empty())).contains(SessionState.TRADING);
        assertThat(service.apply(TENANT, SessionEvent.EOD_TRIGGER, Optional.empty()))
                .contains(SessionState.FLATTENING);
        assertThat(service.apply(TENANT, SessionEvent.MARKET_CLOSE, Optional.empty())).contains(SessionState.CLOSED);

        assertThat(repo.records).hasSize(4);
    }

    @Test
    void invalidEventDoesNotPersist() {
        // Cannot fire MARKET_OPEN from PRE_MARKET — LEVELS_READY must come first.
        assertThat(service.apply(TENANT, SessionEvent.MARKET_OPEN, Optional.empty())).isEmpty();
        assertThat(repo.records).isEmpty();
    }

    @Test
    void blackoutResumesPreviousState() {
        service.apply(TENANT, SessionEvent.LEVELS_READY, Optional.empty());
        service.apply(TENANT, SessionEvent.MARKET_OPEN, Optional.empty());
        // Now in TRADING.

        Optional<SessionState> intoBlackout = service.apply(TENANT, SessionEvent.NEWS_BLACKOUT_START, Optional.empty());
        assertThat(intoBlackout).contains(SessionState.BLACKOUT);

        Optional<SessionState> resume = service.apply(TENANT, SessionEvent.NEWS_BLACKOUT_END, Optional.empty());
        assertThat(resume).contains(SessionState.TRADING);
        assertThat(service.currentState(TENANT)).isEqualTo(SessionState.TRADING);
    }

    @Test
    void blackoutEndIgnoredWhenNotInBlackout() {
        service.apply(TENANT, SessionEvent.LEVELS_READY, Optional.empty());
        // Currently ARMED — no blackout to clear.
        Optional<SessionState> result = service.apply(TENANT, SessionEvent.NEWS_BLACKOUT_END, Optional.empty());
        assertThat(result).isEmpty();
        assertThat(service.currentState(TENANT)).isEqualTo(SessionState.ARMED);
    }

    @Test
    void persistedTransitionCarriesKindAndVersionAndFsmId() {
        Optional<String> correlationId = Optional.of("corr-42");
        service.apply(TENANT, SessionEvent.LEVELS_READY, correlationId);

        assertThat(repo.records).hasSize(1);
        FsmTransition<?, ?> tr = repo.records.get(0);
        assertThat(tr.tenantId()).isEqualTo(TENANT);
        assertThat(tr.fsmKind()).isEqualTo("SESSION");
        assertThat(tr.fsmVersion()).isEqualTo(1);
        assertThat(tr.fsmId()).isEqualTo("2026-04-30");
        assertThat(tr.fromState()).contains(SessionState.PRE_MARKET);
        assertThat(tr.toState()).isEqualTo(SessionState.ARMED);
        assertThat(tr.event()).isEqualTo(SessionEvent.LEVELS_READY);
        assertThat(tr.correlationId()).contains("corr-42");
    }

    @Test
    void perTenantStateIsIsolated() {
        service.apply("TENANT_A", SessionEvent.LEVELS_READY, Optional.empty());
        assertThat(service.currentState("TENANT_A")).isEqualTo(SessionState.ARMED);
        assertThat(service.currentState("TENANT_B")).isEqualTo(SessionState.PRE_MARKET);
    }

    /**
     * Recording {@link FsmTransitionRepository}. We extend the real class with a
     * {@code null} {@link DataSource}; the {@code record} override never touches
     * the datasource so this is safe.
     */
    static final class RecordingRepo extends FsmTransitionRepository {
        final List<FsmTransition<?, ?>> records = new ArrayList<>();

        RecordingRepo() {
            super((DataSource) null);
        }

        @Override
        public void record(FsmTransition<?, ?> transition) {
            records.add(transition);
        }
    }
}
