package com.levelsweep.decision.strike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.levelsweep.shared.domain.options.OptionContract;
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.options.StrikeSelectionResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StrikeSelectorServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 30);
    private static final BigDecimal SPOT = new BigDecimal("600.50");

    @Test
    void selectForChainsClientThroughSelector() {
        AlpacaOptionsClient client = mock(AlpacaOptionsClient.class);
        StrikeSelector selector = mock(StrikeSelector.class);

        OptionContract c = sampleCall();
        when(client.fetchChain("SPY")).thenReturn(List.of(c));
        StrikeSelectionResult expected = new StrikeSelectionResult.Selected(
                new com.levelsweep.shared.domain.options.StrikeSelection(c, "test", List.of()));
        when(selector.select(eq(SPOT), eq(OptionSide.CALL), any(), eq(TODAY))).thenReturn(expected);

        StrikeSelectorService service = new StrikeSelectorService(client, selector);

        StrikeSelectionResult result = service.selectFor("SPY", SPOT, OptionSide.CALL, TODAY);

        assertThat(result).isSameAs(expected);
        verify(client).fetchChain("SPY");
        verify(selector).select(eq(SPOT), eq(OptionSide.CALL), eq(List.of(c)), eq(TODAY));
    }

    @Test
    void noCandidatesPropagatesUnchanged() {
        AlpacaOptionsClient client = mock(AlpacaOptionsClient.class);
        StrikeSelector selector = mock(StrikeSelector.class);
        when(client.fetchChain("SPY")).thenReturn(List.of());
        StrikeSelectionResult expected = new StrikeSelectionResult.NoCandidates("empty_chain");
        when(selector.select(any(), any(), any(), any())).thenReturn(expected);

        StrikeSelectorService service = new StrikeSelectorService(client, selector);

        StrikeSelectionResult result = service.selectFor("SPY", SPOT, OptionSide.PUT, TODAY);

        assertThat(result).isSameAs(expected);
    }

    private static OptionContract sampleCall() {
        return new OptionContract(
                "SPY260430C00600000",
                "SPY",
                TODAY,
                new BigDecimal("600"),
                OptionSide.CALL,
                new BigDecimal("1.05"),
                new BigDecimal("1.10"),
                Optional.of(500),
                Optional.of(1000),
                Optional.empty(),
                Optional.empty());
    }
}
