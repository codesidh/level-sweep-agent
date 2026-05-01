package com.levelsweep.decision.fsm.trade;

import static org.assertj.core.api.Assertions.assertThat;

import com.levelsweep.decision.fsm.persistence.FsmTransitionRepository;
import com.levelsweep.shared.fsm.FsmTransition;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies the service-level coordinator drives the pure FSM correctly and persists
 * every accepted transition. Uses a hand-rolled recording repository — Mockito is
 * not on this module's test classpath.
 *
 * <p>{@code propose} does not persist a seed row; the first persisted row is the
 * PROPOSED → ENTERED edge.
 */
class TradeServiceTest {

    private static final String TENANT = "OWNER";
    private static final LocalDate SESSION = LocalDate.of(2026, 4, 30);

    private final TradeFsm fsm = new TradeFsm();
    private final RecordingRepo repo = new RecordingRepo();
    private final Clock fixed = Clock.fixed(Instant.parse("2026-04-30T14:30:00Z"), ZoneOffset.UTC);
    private final TradeService service = new TradeService(fsm, repo, fixed);

    @Test
    void proposeMintsUuidAndDoesNotPersist() {
        TradeFsmInstance trade = service.propose(TENANT, SESSION);

        assertThat(trade.tenantId()).isEqualTo(TENANT);
        assertThat(trade.sessionDate()).isEqualTo(SESSION);
        assertThat(trade.tradeId()).isNotBlank();
        assertThat(trade.state()).isEqualTo(TradeState.PROPOSED);
        assertThat(trade.proposedAt()).isPresent();
        assertThat(repo.records).isEmpty();
    }

    @Test
    void happyPathAcrossTheTradePersistsFourTransitions() {
        TradeFsmInstance trade = service.propose(TENANT, SESSION);
        String id = trade.tradeId();

        assertThat(service.apply(id, TradeEvent.RISK_APPROVED, Optional.empty()).map(TradeFsmInstance::state))
                .contains(TradeState.ENTERED);
        assertThat(service.apply(id, TradeEvent.FILL_CONFIRMED, Optional.empty())
                        .map(TradeFsmInstance::state))
                .contains(TradeState.ACTIVE);
        assertThat(service.apply(id, TradeEvent.PROFIT_TARGET_HIT, Optional.empty())
                        .map(TradeFsmInstance::state))
                .contains(TradeState.EXITING);
        assertThat(service.apply(id, TradeEvent.EXIT_FILL_CONFIRMED, Optional.empty())
                        .map(TradeFsmInstance::state))
                .contains(TradeState.CLOSED);

        assertThat(repo.records).hasSize(4);
    }

    @Test
    void invalidEventOnUnknownTradeIsEmpty() {
        Optional<TradeFsmInstance> result = service.apply("nonexistent", TradeEvent.RISK_APPROVED, Optional.empty());
        assertThat(result).isEmpty();
        assertThat(repo.records).isEmpty();
    }

    @Test
    void invalidEventOnExistingTradeDoesNotPersist() {
        TradeFsmInstance trade = service.propose(TENANT, SESSION);
        // STOP_HIT is not legal from PROPOSED.
        Optional<TradeFsmInstance> result = service.apply(trade.tradeId(), TradeEvent.STOP_HIT, Optional.empty());
        assertThat(result).isEmpty();
        assertThat(repo.records).isEmpty();
    }

    @Test
    void errorEdgeFromActiveLandsInFailedAndPersists() {
        TradeFsmInstance trade = service.propose(TENANT, SESSION);
        String id = trade.tradeId();
        service.apply(id, TradeEvent.RISK_APPROVED, Optional.empty());
        service.apply(id, TradeEvent.FILL_CONFIRMED, Optional.empty());

        Optional<TradeFsmInstance> failed = service.apply(id, TradeEvent.ERROR, Optional.empty());
        assertThat(failed.map(TradeFsmInstance::state)).contains(TradeState.FAILED);
    }

    @Test
    void persistedTransitionCarriesKindAndVersionAndTradeId() {
        TradeFsmInstance trade = service.propose(TENANT, SESSION);
        String id = trade.tradeId();
        service.apply(id, TradeEvent.RISK_APPROVED, Optional.of("corr-99"));

        assertThat(repo.records).hasSize(1);
        FsmTransition<?, ?> tr = repo.records.get(0);
        assertThat(tr.tenantId()).isEqualTo(TENANT);
        assertThat(tr.fsmKind()).isEqualTo("TRADE");
        assertThat(tr.fsmVersion()).isEqualTo(1);
        assertThat(tr.fsmId()).isEqualTo(id);
        assertThat(tr.fromState()).contains(TradeState.PROPOSED);
        assertThat(tr.toState()).isEqualTo(TradeState.ENTERED);
        assertThat(tr.event()).isEqualTo(TradeEvent.RISK_APPROVED);
        assertThat(tr.correlationId()).contains("corr-99");
    }

    @Test
    void findReturnsCurrentSnapshot() {
        TradeFsmInstance trade = service.propose(TENANT, SESSION);
        String id = trade.tradeId();
        service.apply(id, TradeEvent.RISK_APPROVED, Optional.empty());

        Optional<TradeFsmInstance> found = service.find(id);
        assertThat(found).isPresent();
        assertThat(found.get().state()).isEqualTo(TradeState.ENTERED);
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
