package com.levelsweep.execution.replay;

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
import com.levelsweep.shared.domain.options.OptionSide;
import com.levelsweep.shared.domain.trade.TradeProposed;
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
import java.util.Optional;

/**
 * JSON marshalling helper for the replay-parity execution scenarios. Mirrors
 * decision-engine's {@code FixtureJson} pattern: round-trip {@link TradeProposed}
 * through a hand-written DTO so the on-disk shape is documented and stable
 * across Java/Jackson versions.
 *
 * <p>Read paths are class-loader-based ({@code resources/replay/<scenario>.json});
 * write paths target {@code src/test/resources/replay/<scenario>.json} and are
 * only used by an operator-driven regeneration utility (no production tests
 * call writers).
 *
 * <p>Field ordering is alphabetical so two fixtures generated from the same
 * {@link ExecutionScenarios} build produce byte-identical files — a precondition
 * for the parity contract once the JSON corpus is the source of truth.
 *
 * <p>Today this class is bundled but not depended-on by any test; once Phase 6
 * paper-session soak runs land, the recordings get serialized through this
 * helper and the harness reads back from JSON. The in-Java {@link ExecutionScenarios}
 * builder is sufficient for the Phase 3 PR, so this class is preflight only.
 */
public final class ScenarioJsonFixture {

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

    private ScenarioJsonFixture() {}

    /**
     * Static accessor for the configured mapper — exposed so future helpers
     * can serialize {@link SimulatedEvent} timelines without re-doing the
     * module wiring.
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * JSON shape of {@link TradeProposed}. Records' canonical constructors run
     * on deserialization, so domain invariants stay enforced after a
     * round-trip.
     */
    public record TradeProposedDto(
            String tenantId,
            String tradeId,
            LocalDate sessionDate,
            Instant proposedAt,
            String underlying,
            OptionSide side,
            String contractSymbol,
            BigDecimal entryNbboBid,
            BigDecimal entryNbboAsk,
            BigDecimal entryMid,
            Optional<BigDecimal> impliedVolatility,
            Optional<BigDecimal> delta,
            String correlationId,
            List<String> signalReasons) {
        @JsonCreator
        public TradeProposedDto(
                @JsonProperty("tenantId") String tenantId,
                @JsonProperty("tradeId") String tradeId,
                @JsonProperty("sessionDate") LocalDate sessionDate,
                @JsonProperty("proposedAt") Instant proposedAt,
                @JsonProperty("underlying") String underlying,
                @JsonProperty("side") OptionSide side,
                @JsonProperty("contractSymbol") String contractSymbol,
                @JsonProperty("entryNbboBid") BigDecimal entryNbboBid,
                @JsonProperty("entryNbboAsk") BigDecimal entryNbboAsk,
                @JsonProperty("entryMid") BigDecimal entryMid,
                @JsonProperty("impliedVolatility") Optional<BigDecimal> impliedVolatility,
                @JsonProperty("delta") Optional<BigDecimal> delta,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("signalReasons") List<String> signalReasons) {
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
            this.tradeId = Objects.requireNonNull(tradeId, "tradeId");
            this.sessionDate = Objects.requireNonNull(sessionDate, "sessionDate");
            this.proposedAt = Objects.requireNonNull(proposedAt, "proposedAt");
            this.underlying = Objects.requireNonNull(underlying, "underlying");
            this.side = Objects.requireNonNull(side, "side");
            this.contractSymbol = Objects.requireNonNull(contractSymbol, "contractSymbol");
            this.entryNbboBid = Objects.requireNonNull(entryNbboBid, "entryNbboBid");
            this.entryNbboAsk = Objects.requireNonNull(entryNbboAsk, "entryNbboAsk");
            this.entryMid = Objects.requireNonNull(entryMid, "entryMid");
            this.impliedVolatility = impliedVolatility == null ? Optional.empty() : impliedVolatility;
            this.delta = delta == null ? Optional.empty() : delta;
            this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
            this.signalReasons = signalReasons == null ? List.of() : List.copyOf(signalReasons);
        }

        public static TradeProposedDto fromTradeProposed(TradeProposed t) {
            return new TradeProposedDto(
                    t.tenantId(),
                    t.tradeId(),
                    t.sessionDate(),
                    t.proposedAt(),
                    t.underlying(),
                    t.side(),
                    t.contractSymbol(),
                    t.entryNbboBid(),
                    t.entryNbboAsk(),
                    t.entryMid(),
                    t.impliedVolatility(),
                    t.delta(),
                    t.correlationId(),
                    t.signalReasons());
        }

        public TradeProposed toTradeProposed() {
            return new TradeProposed(
                    tenantId,
                    tradeId,
                    sessionDate,
                    proposedAt,
                    underlying,
                    side,
                    contractSymbol,
                    entryNbboBid,
                    entryNbboAsk,
                    entryMid,
                    impliedVolatility,
                    delta,
                    correlationId,
                    signalReasons);
        }
    }

    /** Read a TradeProposed JSON fixture from {@code resources/replay/<scenario>.json}. */
    public static TradeProposed read(String scenarioName) {
        String path = "replay/" + scenarioName + ".json";
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("missing fixture resource: " + path);
            }
            return MAPPER.readValue(is, TradeProposedDto.class).toTradeProposed();
        } catch (IOException e) {
            throw new UncheckedIOException("read fixture " + path, e);
        }
    }

    /** Write a TradeProposed JSON fixture into the source tree. Used only by regeneration utilities. */
    public static void write(Path resourcesRoot, String scenarioName, TradeProposed event) {
        Path file = resourcesRoot.resolve("replay").resolve(scenarioName + ".json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, WRITER.writeValueAsString(TradeProposedDto.fromTradeProposed(event)) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("write fixture " + file, e);
        }
    }
}
