package com.levelsweep.decision.strike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.levelsweep.shared.domain.options.OptionSide;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OccSymbolParserTest {

    @Test
    void parsesCallContract() {
        OccSymbolParser.Parsed p = OccSymbolParser.parse("SPY250130C00600000");

        assertThat(p.underlying()).isEqualTo("SPY");
        assertThat(p.expiry()).isEqualTo(LocalDate.of(2025, 1, 30));
        assertThat(p.side()).isEqualTo(OptionSide.CALL);
        assertThat(p.strike()).isEqualByComparingTo(new BigDecimal("600.000"));
    }

    @Test
    void parsesPutContract() {
        OccSymbolParser.Parsed p = OccSymbolParser.parse("SPY260101P00450500");

        assertThat(p.underlying()).isEqualTo("SPY");
        assertThat(p.expiry()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(p.side()).isEqualTo(OptionSide.PUT);
        assertThat(p.strike()).isEqualByComparingTo(new BigDecimal("450.500"));
    }

    @Test
    void parsesNonSpyUnderlyingsToo() {
        OccSymbolParser.Parsed p = OccSymbolParser.parse("QQQ250630C00500000");

        assertThat(p.underlying()).isEqualTo("QQQ");
        assertThat(p.expiry()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(p.side()).isEqualTo(OptionSide.CALL);
        assertThat(p.strike()).isEqualByComparingTo(new BigDecimal("500.000"));
    }

    @Test
    void rejectsMalformedSymbol() {
        assertThatThrownBy(() -> OccSymbolParser.parse("not-an-occ-symbol"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OccSymbolParser.parse("SPY250130C0060000")) // 7-digit strike
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OccSymbolParser.parse("SPY250130X00600000")) // bad side
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullSymbol() {
        assertThatThrownBy(() -> OccSymbolParser.parse(null))
                .isInstanceOf(NullPointerException.class);
    }
}
