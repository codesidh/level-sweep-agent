package com.levelsweep.decision.replay;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.levelsweep.shared.domain.indicators.IndicatorSnapshot;
import com.levelsweep.shared.domain.marketdata.Bar;
import com.levelsweep.shared.domain.marketdata.Levels;
import com.levelsweep.shared.domain.marketdata.Timeframe;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * JSON marshalling helper for the replay fixtures. Uses Jackson with the
 * JavaTime + Jdk8 modules so {@link Instant} / {@link LocalDate} /
 * {@link java.util.Optional} round-trip cleanly.
 *
 * <p>Read paths are class-loader-based (resources/replay/&lt;name&gt;/...);
 * write paths target the repo's source tree at
 * {@code services/decision-engine/src/test/resources/replay/&lt;name&gt;/}
 * and are only used by the {@code RegenerateFixtures} task.
 *
 * <p>Field ordering is alphabetic so two fixtures generated from the same
 * {@link SyntheticSessionFixtures.Session} produce byte-identical files —
 * a precondition for the parity contract.
 */
final class FixtureJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new Jdk8Module())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    private static final ObjectWriter WRITER;

    static {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        printer.indentObjectsWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        WRITER = MAPPER.writer(printer);
    }

    private FixtureJson() {}

    static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * JSON shape of {@link Bar}. Records' canonical constructors run on
     * deserialization, so invariants stay enforced after a round-trip.
     */
    record BarDto(
            String symbol,
            Timeframe timeframe,
            Instant openTime,
            Instant closeTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume,
            long ticks) {
        @JsonCreator
        BarDto(
                @JsonProperty("symbol") String symbol,
                @JsonProperty("timeframe") Timeframe timeframe,
                @JsonProperty("openTime") Instant openTime,
                @JsonProperty("closeTime") Instant closeTime,
                @JsonProperty("open") BigDecimal open,
                @JsonProperty("high") BigDecimal high,
                @JsonProperty("low") BigDecimal low,
                @JsonProperty("close") BigDecimal close,
                @JsonProperty("volume") long volume,
                @JsonProperty("ticks") long ticks) {
            this.symbol = Objects.requireNonNull(symbol, "symbol");
            this.timeframe = Objects.requireNonNull(timeframe, "timeframe");
            this.openTime = Objects.requireNonNull(openTime, "openTime");
            this.closeTime = Objects.requireNonNull(closeTime, "closeTime");
            this.open = Objects.requireNonNull(open, "open");
            this.high = Objects.requireNonNull(high, "high");
            this.low = Objects.requireNonNull(low, "low");
            this.close = Objects.requireNonNull(close, "close");
            this.volume = volume;
            this.ticks = ticks;
        }

        static BarDto fromBar(Bar b) {
            return new BarDto(
                    b.symbol(),
                    b.timeframe(),
                    b.openTime(),
                    b.closeTime(),
                    b.open(),
                    b.high(),
                    b.low(),
                    b.close(),
                    b.volume(),
                    b.ticks());
        }

        Bar toBar() {
            return new Bar(symbol, timeframe, openTime, closeTime, open, high, low, close, volume, ticks);
        }
    }

    /** JSON shape of {@link IndicatorSnapshot} — nullable EMA/ATR fields. */
    record IndicatorSnapshotDto(
            String symbol, Instant timestamp, BigDecimal ema13, BigDecimal ema48, BigDecimal ema200, BigDecimal atr14) {
        @JsonCreator
        IndicatorSnapshotDto(
                @JsonProperty("symbol") String symbol,
                @JsonProperty("timestamp") Instant timestamp,
                @JsonProperty("ema13") BigDecimal ema13,
                @JsonProperty("ema48") BigDecimal ema48,
                @JsonProperty("ema200") BigDecimal ema200,
                @JsonProperty("atr14") BigDecimal atr14) {
            this.symbol = Objects.requireNonNull(symbol, "symbol");
            this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
            this.ema13 = ema13;
            this.ema48 = ema48;
            this.ema200 = ema200;
            this.atr14 = atr14;
        }

        static IndicatorSnapshotDto fromSnapshot(IndicatorSnapshot s) {
            return new IndicatorSnapshotDto(s.symbol(), s.timestamp(), s.ema13(), s.ema48(), s.ema200(), s.atr14());
        }

        IndicatorSnapshot toSnapshot() {
            return new IndicatorSnapshot(symbol, timestamp, ema13, ema48, ema200, atr14);
        }
    }

    /** JSON shape of {@link Levels}. */
    record LevelsDto(
            String tenantId,
            String symbol,
            LocalDate sessionDate,
            BigDecimal pdh,
            BigDecimal pdl,
            BigDecimal pmh,
            BigDecimal pml) {
        @JsonCreator
        LevelsDto(
                @JsonProperty("tenantId") String tenantId,
                @JsonProperty("symbol") String symbol,
                @JsonProperty("sessionDate") LocalDate sessionDate,
                @JsonProperty("pdh") BigDecimal pdh,
                @JsonProperty("pdl") BigDecimal pdl,
                @JsonProperty("pmh") BigDecimal pmh,
                @JsonProperty("pml") BigDecimal pml) {
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
            this.symbol = Objects.requireNonNull(symbol, "symbol");
            this.sessionDate = Objects.requireNonNull(sessionDate, "sessionDate");
            this.pdh = Objects.requireNonNull(pdh, "pdh");
            this.pdl = Objects.requireNonNull(pdl, "pdl");
            this.pmh = Objects.requireNonNull(pmh, "pmh");
            this.pml = Objects.requireNonNull(pml, "pml");
        }

        static LevelsDto fromLevels(Levels l) {
            return new LevelsDto(l.tenantId(), l.symbol(), l.sessionDate(), l.pdh(), l.pdl(), l.pmh(), l.pml());
        }

        Levels toLevels() {
            return new Levels(tenantId, symbol, sessionDate, pdh, pdl, pmh, pml);
        }
    }

    static List<Bar> readBars(String sessionName) {
        try (InputStream is = open(sessionName, "bars-2m.json")) {
            BarDto[] dtos = MAPPER.readValue(is, BarDto[].class);
            return java.util.Arrays.stream(dtos).map(BarDto::toBar).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("read bars-2m.json for " + sessionName, e);
        }
    }

    static List<IndicatorSnapshot> readIndicators(String sessionName) {
        try (InputStream is = open(sessionName, "indicators.json")) {
            IndicatorSnapshotDto[] dtos = MAPPER.readValue(is, IndicatorSnapshotDto[].class);
            return java.util.Arrays.stream(dtos)
                    .map(IndicatorSnapshotDto::toSnapshot)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("read indicators.json for " + sessionName, e);
        }
    }

    static Levels readLevels(String sessionName) {
        try (InputStream is = open(sessionName, "levels.json")) {
            return MAPPER.readValue(is, LevelsDto.class).toLevels();
        } catch (IOException e) {
            throw new UncheckedIOException("read levels.json for " + sessionName, e);
        }
    }

    static ExpectedOutcome readExpected(String sessionName) {
        try (InputStream is = open(sessionName, "expected.json")) {
            return MAPPER.readValue(is, ExpectedOutcome.class);
        } catch (IOException e) {
            throw new UncheckedIOException("read expected.json for " + sessionName, e);
        }
    }

    /**
     * Write all four files for a session into the source-tree resources path.
     * Used only by the regeneration utility (operator command); production tests
     * never call this.
     */
    static void writeSession(Path resourcesRoot, SyntheticSessionFixtures.Session session, ExpectedOutcome expected) {
        Path dir = resourcesRoot.resolve("replay").resolve(session.name());
        try {
            Files.createDirectories(dir);
            List<BarDto> barDtos =
                    session.bars2m().stream().map(BarDto::fromBar).toList();
            List<IndicatorSnapshotDto> snapDtos = session.indicators().stream()
                    .map(IndicatorSnapshotDto::fromSnapshot)
                    .toList();
            LevelsDto levelsDto = LevelsDto.fromLevels(session.levels());
            Files.writeString(dir.resolve("bars-2m.json"), WRITER.writeValueAsString(barDtos) + "\n");
            Files.writeString(dir.resolve("indicators.json"), WRITER.writeValueAsString(snapDtos) + "\n");
            Files.writeString(dir.resolve("levels.json"), WRITER.writeValueAsString(levelsDto) + "\n");
            Files.writeString(dir.resolve("expected.json"), WRITER.writeValueAsString(expected) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("write session " + session.name(), e);
        }
    }

    private static InputStream open(String sessionName, String fileName) {
        String path = "replay/" + sessionName + "/" + fileName;
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (is == null) {
            throw new IllegalStateException("missing fixture resource: " + path);
        }
        return is;
    }
}
