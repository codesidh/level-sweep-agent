package com.levelsweep.marketdata.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.levelsweep.marketdata.alpaca.AlpacaConfig;
import com.levelsweep.marketdata.persistence.LevelsRepository;
import com.levelsweep.marketdata.persistence.MongoBarRepository;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plain-JUnit tests for {@link SessionLevelScheduler}. Exercises {@code runOnce()}
 * directly so the cron scheduling subsystem isn't required to validate the body.
 */
class SessionLevelSchedulerTest {

    // 09:29:30 ET on 2026-04-30 (a Thursday) — same instant the cron would fire.
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-30T13:29:30Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 30);

    @Test
    void computesAndUpsertsLevelsWhenBothWindowsHaveBars() {
        MongoBarRepository bars = mock(MongoBarRepository.class);
        LevelsRepository levels = mock(LevelsRepository.class);
        AlpacaConfig cfg = stubCfg();

        // Prior RTH (2026-04-29) bars — high 595.00, low 593.00.
        List<Bar> rthBars = List.of(
                bar("SPY", "2026-04-29T13:30:00Z", "594.00", "595.00", "593.50", "594.50"),
                bar("SPY", "2026-04-29T19:55:00Z", "594.50", "594.80", "593.00", "593.20"));
        // Overnight (2026-04-29 16:01 ET → 2026-04-30 09:29 ET) — high 596.50, low 593.20.
        List<Bar> overnightBars = List.of(
                bar("SPY", "2026-04-29T20:05:00Z", "593.30", "594.00", "593.20", "593.80"),
                bar("SPY", "2026-04-30T13:00:00Z", "595.00", "596.50", "594.80", "596.20"));

        when(bars.findBarsByWindow(eq("OWNER"), eq("SPY"), eq(Timeframe.ONE_MIN), any(), any()))
                .thenReturn(rthBars, overnightBars);

        SessionLevelScheduler scheduler = new SessionLevelScheduler(bars, levels, cfg, FIXED_CLOCK, "OWNER");
        scheduler.runOnce();

        ArgumentCaptor<Levels> capt = ArgumentCaptor.forClass(Levels.class);
        verify(levels).upsert(capt.capture());
        Levels persisted = capt.getValue();
        assertThat(persisted.tenantId()).isEqualTo("OWNER");
        assertThat(persisted.symbol()).isEqualTo("SPY");
        assertThat(persisted.sessionDate()).isEqualTo(TODAY);
        assertThat(persisted.pdh()).isEqualByComparingTo(new BigDecimal("595.00"));
        assertThat(persisted.pdl()).isEqualByComparingTo(new BigDecimal("593.00"));
        assertThat(persisted.pmh()).isEqualByComparingTo(new BigDecimal("596.50"));
        assertThat(persisted.pml()).isEqualByComparingTo(new BigDecimal("593.20"));
    }

    @Test
    void skipsWhenRthBarsAreEmptyAndDoesNotUpsert() {
        MongoBarRepository bars = mock(MongoBarRepository.class);
        LevelsRepository levels = mock(LevelsRepository.class);
        AlpacaConfig cfg = stubCfg();

        when(bars.findBarsByWindow(eq("OWNER"), eq("SPY"), eq(Timeframe.ONE_MIN), any(), any()))
                .thenReturn(Collections.emptyList());

        SessionLevelScheduler scheduler = new SessionLevelScheduler(bars, levels, cfg, FIXED_CLOCK, "OWNER");
        scheduler.runOnce();

        verifyNoInteractions(levels);
    }

    @Test
    void skipsWhenOvernightBarsAreEmptyAndDoesNotUpsert() {
        MongoBarRepository bars = mock(MongoBarRepository.class);
        LevelsRepository levels = mock(LevelsRepository.class);
        AlpacaConfig cfg = stubCfg();

        List<Bar> rthBars = List.of(bar("SPY", "2026-04-29T13:30:00Z", "594.00", "595.00", "593.50", "594.50"));
        when(bars.findBarsByWindow(eq("OWNER"), eq("SPY"), eq(Timeframe.ONE_MIN), any(), any()))
                .thenReturn(rthBars, Collections.emptyList());

        SessionLevelScheduler scheduler = new SessionLevelScheduler(bars, levels, cfg, FIXED_CLOCK, "OWNER");
        scheduler.runOnce();

        verifyNoInteractions(levels);
    }

    @Test
    void doesNotPropagateRepositoryUpsertException() {
        MongoBarRepository bars = mock(MongoBarRepository.class);
        LevelsRepository levels = mock(LevelsRepository.class);
        AlpacaConfig cfg = stubCfg();

        List<Bar> rthBars = List.of(bar("SPY", "2026-04-29T13:30:00Z", "594.00", "595.00", "593.50", "594.50"));
        List<Bar> overnightBars = List.of(bar("SPY", "2026-04-30T13:00:00Z", "595.00", "596.50", "594.80", "596.20"));
        when(bars.findBarsByWindow(eq("OWNER"), eq("SPY"), eq(Timeframe.ONE_MIN), any(), any()))
                .thenReturn(rthBars, overnightBars);
        doThrow(new RuntimeException("mssql down")).when(levels).upsert(any(Levels.class));

        SessionLevelScheduler scheduler = new SessionLevelScheduler(bars, levels, cfg, FIXED_CLOCK, "OWNER");

        // Must NOT propagate — otherwise the cron thread would suppress future fires.
        scheduler.runOnce();

        verify(levels).upsert(any(Levels.class));
    }

    @Test
    void usesProvidedTenantIdNotHardcoded() {
        MongoBarRepository bars = mock(MongoBarRepository.class);
        LevelsRepository levels = mock(LevelsRepository.class);
        AlpacaConfig cfg = stubCfg();

        List<Bar> rthBars = List.of(bar("SPY", "2026-04-29T13:30:00Z", "594.00", "595.00", "593.50", "594.50"));
        List<Bar> overnightBars = List.of(bar("SPY", "2026-04-30T13:00:00Z", "595.00", "596.50", "594.80", "596.20"));
        when(bars.findBarsByWindow(eq("tenant-xyz"), eq("SPY"), eq(Timeframe.ONE_MIN), any(), any()))
                .thenReturn(rthBars, overnightBars);

        SessionLevelScheduler scheduler = new SessionLevelScheduler(bars, levels, cfg, FIXED_CLOCK, "tenant-xyz");
        scheduler.runOnce();

        ArgumentCaptor<Levels> capt = ArgumentCaptor.forClass(Levels.class);
        verify(levels).upsert(capt.capture());
        assertThat(capt.getValue().tenantId()).isEqualTo("tenant-xyz");
    }

    private static Bar bar(String symbol, String openIso, String o, String h, String l, String c) {
        Instant open = Instant.parse(openIso);
        return new Bar(
                symbol,
                Timeframe.ONE_MIN,
                open,
                open.plusSeconds(60),
                new BigDecimal(o),
                new BigDecimal(h),
                new BigDecimal(l),
                new BigDecimal(c),
                100L,
                10L);
    }

    private static AlpacaConfig stubCfg() {
        return new AlpacaConfig() {
            @Override
            public String wsBaseUrl() {
                return "wss://stream.data.alpaca.markets";
            }

            @Override
            public String feed() {
                return "sip";
            }

            @Override
            public String tradingUrl() {
                return "https://paper-api.alpaca.markets";
            }

            @Override
            public String dataUrl() {
                return "https://data.alpaca.markets";
            }

            @Override
            public String apiKey() {
                return "";
            }

            @Override
            public String secretKey() {
                return "";
            }

            @Override
            public List<String> symbols() {
                return List.of("SPY");
            }

            @Override
            public Duration reconnectInitialBackoff() {
                return Duration.ofMillis(200);
            }

            @Override
            public Duration reconnectMaxBackoff() {
                return Duration.ofSeconds(30);
            }

            @Override
            public Duration reconnectJitter() {
                return Duration.ofMillis(100);
            }

            @Override
            public int ringBufferCapacity() {
                return 1000;
            }
        };
    }
}
