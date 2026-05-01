package com.levelsweep.decision.strike;

import com.levelsweep.shared.domain.options.OptionSide;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Options Clearing Corp (OCC) 21-character contract symbols into
 * their underlying / expiry / side / strike components.
 *
 * <p>OCC format (variable-length root, fixed-width tail):
 *
 * <pre>
 *   ROOT (1-6 alpha)  YYMMDD (6 digits)  C|P (1 char)  STRIKE (8 digits, 1000×$)
 *   SPY               250130             C             00600000
 * </pre>
 *
 * <p>The strike encodes price × 1000 zero-padded to 8 digits, so
 * {@code 00600000} decodes to $600.000. Two-digit year is interpreted as
 * 2000-2099; OCC has not announced a 2100 schema and Phase 1 trades 0DTE
 * SPY for which this is moot.
 */
public final class OccSymbolParser {

    private static final Pattern OCC =
            Pattern.compile("^(?<root>[A-Z]{1,6})(?<yy>\\d{2})(?<mm>\\d{2})(?<dd>\\d{2})(?<cp>[CP])(?<strike>\\d{8})$");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private OccSymbolParser() {}

    /**
     * Parse an OCC contract symbol. Throws {@link IllegalArgumentException}
     * if the input is null, blank, or doesn't match the OCC grammar — the
     * Alpaca chain endpoint maps strike entries by these symbols so a
     * malformed key indicates malformed JSON, which the caller handles by
     * skipping the entry.
     */
    public static Parsed parse(String symbol) {
        Objects.requireNonNull(symbol, "symbol");
        Matcher m = OCC.matcher(symbol);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not an OCC contract symbol: " + symbol);
        }
        String root = m.group("root");
        // OCC two-digit year is post-2000 by industry convention.
        int year = 2000 + Integer.parseInt(m.group("yy"));
        int month = Integer.parseInt(m.group("mm"));
        int day = Integer.parseInt(m.group("dd"));
        LocalDate expiry = LocalDate.parse(
                String.format("%04d%02d%02d", year, month, day), YYMMDD);
        OptionSide side = m.group("cp").equals("C") ? OptionSide.CALL : OptionSide.PUT;
        // Strike is integer × 1000; 1000 has only 2/5 prime factors, so the
        // division is exact at scale 3. Pin the scale explicitly so the
        // result formats as e.g. "600.000" — easier to spot in logs.
        BigDecimal strike = new BigDecimal(m.group("strike")).divide(THOUSAND, 3, java.math.RoundingMode.UNNECESSARY);
        return new Parsed(root, expiry, side, strike);
    }

    /** Result of {@link #parse}. */
    public record Parsed(String underlying, LocalDate expiry, OptionSide side, BigDecimal strike) {
        public Parsed {
            Objects.requireNonNull(underlying, "underlying");
            Objects.requireNonNull(expiry, "expiry");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(strike, "strike");
        }
    }
}
