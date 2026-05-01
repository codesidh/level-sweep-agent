package com.levelsweep.decision.fsm.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.decision.fsm.persistence.FsmTransitionRepository;
import com.levelsweep.shared.fsm.FsmTransition;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Drives the {@link SessionDriver} with a fixed clock at various ET wall-clock
 * snapshots and asserts the right boundary events fire (and don't refire on
 * subsequent ticks). Uses a stub repository — Mockito is not on this module's test
 * classpath.
 */
class SessionDriverTest {

    private static final String TENANT = "OWNER";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate SESSION = LocalDate.of(2026, 4, 30);

    private static FsmTransitionRepository stubRepo() {
        return new FsmTransitionRepository((DataSource) null) {
            @Override
            public void record(FsmTransition<?, ?> transition) {
                /* no-op */
            }
        };
    }

    private SessionService serviceWithClock(Clock clock) {
        return new SessionService(new SessionFsm(), stubRepo(), clock);
    }

    private static Instant et(LocalTime t) {
        return ZonedDateTime.of(SESSION, t, ET).toInstant();
    }

    @Test
    void preMarketTickFiresNoBoundaries() {
        Clock c = Clock.fixed(et(LocalTime.of(8, 0)), ET);
        SessionService service = serviceWithClock(c);
        SessionDriver driver = new SessionDriver(service, c);

        assertThat(driver.tick(TENANT)).isEqualTo(SessionState.PRE_MARKET);
        assertThat(service.currentState(TENANT)).isEqualTo(SessionState.PRE_MARKET);
    }

    @Test
    void at09_29_30FiresLevelsReadyOnly() {
        Clock c = Clock.fixed(et(LocalTime.of(9, 29, 30)), ET);
        SessionService service = serviceWithClock(c);
        SessionDriver driver = new SessionDriver(service, c);

        assertThat(driver.tick(TENANT)).isEqualTo(SessionState.ARMED);
    }

    @Test
    void at09_30FiresLevelsReadyAndMarketOpenOnFirstTick() {
        Clock c = Clock.fixed(et(LocalTime.of(9, 30, 0)), ET);
        SessionService service = serviceWithClock(c);
        SessionDriver driver = new SessionDriver(service, c);

        assertThat(driver.tick(TENANT)).isEqualTo(SessionState.TRADING);
    }

    @Test
    void midRthTickIsIdempotent() {
        Clock c = Clock.fixed(et(LocalTime.of(12, 0)), ET);
        SessionService service = serviceWithClock(c);
        SessionDriver driver = new SessionDriver(service, c);

        SessionState first = driver.tick(TENANT);
        SessionState second = driver.tick(TENANT);

        assertThat(first).isEqualTo(SessionState.TRADING);
        assertThat(second).isEqualTo(SessionState.TRADING);
    }

    @Test
    void at15_55FiresEodTriggerOnTopOfTradingBoundaries() {
        Clock c = Clock.fixed(et(LocalTime.of(15, 55, 0)), ET);
        SessionService service = serviceWithClock(c);
        SessionDriver driver = new SessionDriver(service, c);

        assertThat(driver.tick(TENANT)).isEqualTo(SessionState.FLATTENING);
    }

    @Test
    void at16_00FiresAllFourBoundariesAndClosesSession() {
        Clock c = Clock.fixed(et(LocalTime.of(16, 0, 0)), ET);
        SessionService service = serviceWithClock(c);
        SessionDriver driver = new SessionDriver(service, c);

        assertThat(driver.tick(TENANT)).isEqualTo(SessionState.CLOSED);
    }

    @Test
    void boundariesFireAtMostOncePerSession() {
        Instant t0 = et(LocalTime.of(9, 30, 0));
        MutableClock clock = new MutableClock(t0);
        // RecordingRepo lets us count how many transitions were persisted across ticks.
        RecordingRepo repo = new RecordingRepo();
        SessionService service = new SessionService(new SessionFsm(), repo, clock);
        SessionDriver driver = new SessionDriver(service, clock);

        // 09:30 -> LEVELS_READY + MARKET_OPEN fire.
        driver.tick(TENANT);
        int afterFirstTick = repo.records.size();

        // Tick again at 09:31 — no new boundary should fire.
        clock.advance(60);
        SessionState mid = driver.tick(TENANT);

        assertThat(mid).isEqualTo(SessionState.TRADING);
        assertThat(repo.records).hasSize(afterFirstTick);
    }

    /** Small mutable Clock for stepping through wall-clock progression in one test. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(long seconds) {
            this.now = this.now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ET;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

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
