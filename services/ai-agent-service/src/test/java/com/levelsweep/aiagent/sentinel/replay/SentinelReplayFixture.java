package com.levelsweep.aiagent.sentinel.replay;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Bar;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Direction;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.IndicatorSnapshot;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.LevelSwept;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.Outcome;
import com.levelsweep.aiagent.sentinel.SentinelDecisionRequest.RecentTrade;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Allow;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.DecisionPath;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Fallback;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.FallbackReason;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.ReasonCode;
import com.levelsweep.aiagent.sentinel.SentinelDecisionResponse.Veto;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Replay-parity fixture for the Pre-Trade Sentinel (ADR-0007 §5).
 *
 * <p>A fixture pins the recorded {@code (request, anthropicResponseBody,
 * anthropicHttpStatus, expected)} tuple so the replay harness can reproduce a
 * given Sentinel decision deterministically without any HTTP traffic. The
 * {@code Fetcher} test seam in {@code AnthropicClient} accepts the recorded
 * status + body; the replay runner asserts the saga-visible
 * {@link SentinelDecisionResponse} matches the recorded one (variant +
 * decision_path + fallback_reason + confidence + reason_code) — non-deterministic
 * fields ({@code clientRequestId}, {@code latencyMs}) are explicitly excluded.
 *
 * <p>JSON shape on disk (single file per fixture under
 * {@code src/test/resources/sentinel/replay/<name>.json}):
 *
 * <pre>{@code
 * {
 *   "name": "pdh_long_call_explicit_allow",
 *   "request": { ... },
 *   "anthropic": {
 *     "status": 200,
 *     "body": "..."
 *   },
 *   "expected": {
 *     "type": "Allow|Veto|Fallback",
 *     "decision_path": "...",
 *     "fallback_reason": "...",
 *     "confidence": "0.92",
 *     "reason_code": "STRUCTURE_MATCH",
 *     "reason_text": "..."
 *   }
 * }
 * }</pre>
 *
 * <p>Operator playbook for capturing new fixtures lives at
 * {@code src/test/resources/sentinel/replay/README.md}.
 */
public record SentinelReplayFixture(
        String name,
        SentinelDecisionRequest request,
        String anthropicResponseBody,
        int anthropicHttpStatus,
        ExpectedDecision expected) {

    public SentinelReplayFixture {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(expected, "expected");
        if (name.isBlank()) {
            throw new IllegalArgumentException("fixture name must not be blank");
        }
    }

    /** Resource-classpath location all fixtures live under. */
    public static final String FIXTURE_DIR = "sentinel/replay";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new Jdk8Module())
            // Tolerate forward-compatible additions to the on-disk fixture
            // shape — old fixtures continue to load.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * Discover and load every fixture JSON under {@code resources/sentinel/replay/}.
     * Order is filesystem-deterministic (sorted by name) so the parameterized
     * replay test reports failures with a stable label.
     */
    public static List<SentinelReplayFixture> loadAll() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL dirUrl = cl.getResource(FIXTURE_DIR);
        if (dirUrl == null) {
            // Empty corpus is a hard fail — the test asserts at least 10 fixtures.
            return List.of();
        }
        Path dir;
        try {
            dir = Paths.get(dirUrl.toURI());
        } catch (Exception e) {
            throw new IllegalStateException("cannot resolve fixture dir: " + dirUrl, e);
        }
        List<SentinelReplayFixture> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path p : files) {
                out.add(loadFromPath(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + dir, e);
        }
        return Collections.unmodifiableList(out);
    }

    /** Load one named fixture by classpath lookup — handy for spot tests. */
    public static SentinelReplayFixture load(String name) {
        Objects.requireNonNull(name, "name");
        String resource = FIXTURE_DIR + "/" + name + ".json";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("missing fixture resource: " + resource);
            }
            FixtureDto dto = MAPPER.readValue(is, FixtureDto.class);
            return dto.toFixture();
        } catch (IOException e) {
            throw new UncheckedIOException("reading fixture " + resource, e);
        }
    }

    private static SentinelReplayFixture loadFromPath(Path path) {
        try {
            FixtureDto dto = MAPPER.readValue(path.toFile(), FixtureDto.class);
            return dto.toFixture();
        } catch (IOException e) {
            throw new UncheckedIOException("reading fixture " + path, e);
        }
    }

    /** Decoded {@code expected} block — the saga-visible decision the runner asserts against. */
    public sealed interface ExpectedDecision permits ExpectedAllow, ExpectedVeto, ExpectedFallback {

        /** Confirm the actual response matches every replay-stable field this expectation pins. */
        void assertMatches(SentinelDecisionResponse actual, String fixtureName);
    }

    /** Expected ALLOW outcome — pins decision_path, confidence, reason_code, reason_text. */
    public record ExpectedAllow(
            DecisionPath decisionPath, BigDecimal confidence, ReasonCode reasonCode, String reasonText)
            implements ExpectedDecision {
        @Override
        public void assertMatches(SentinelDecisionResponse actual, String fixtureName) {
            if (!(actual instanceof Allow allow)) {
                throw new AssertionError(diff(fixtureName, "Allow", actual));
            }
            requireEqual(fixtureName, "decision_path", decisionPath, allow.decisionPath());
            requireBigDecimalEqual(fixtureName, "confidence", confidence, allow.confidence());
            requireEqual(fixtureName, "reason_code", reasonCode, allow.reasonCode());
            requireEqual(fixtureName, "reason_text", reasonText, allow.reasonText());
        }
    }

    /** Expected VETO outcome — confidence + reason_code + reason_text are stable. */
    public record ExpectedVeto(BigDecimal confidence, ReasonCode reasonCode, String reasonText)
            implements ExpectedDecision {
        @Override
        public void assertMatches(SentinelDecisionResponse actual, String fixtureName) {
            if (!(actual instanceof Veto veto)) {
                throw new AssertionError(diff(fixtureName, "Veto", actual));
            }
            requireBigDecimalEqual(fixtureName, "confidence", confidence, veto.confidence());
            requireEqual(fixtureName, "reason_code", reasonCode, veto.reasonCode());
            requireEqual(fixtureName, "reason_text", reasonText, veto.reasonText());
        }
    }

    /** Expected fail-OPEN — only the {@link FallbackReason} is asserted. */
    public record ExpectedFallback(FallbackReason reason) implements ExpectedDecision {
        @Override
        public void assertMatches(SentinelDecisionResponse actual, String fixtureName) {
            if (!(actual instanceof Fallback fallback)) {
                throw new AssertionError(diff(fixtureName, "Fallback", actual));
            }
            requireEqual(fixtureName, "fallback_reason", reason, fallback.reason());
        }
    }

    private static <T> void requireEqual(String fixtureName, String field, T expected, T actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(String.format(
                    Locale.ROOT,
                    "fixture[%s] %s mismatch: expected=%s actual=%s",
                    fixtureName,
                    field,
                    expected,
                    actual));
        }
    }

    private static void requireBigDecimalEqual(
            String fixtureName, String field, BigDecimal expected, BigDecimal actual) {
        // BigDecimal.equals compares scale; saga decisions only care about value
        // equivalence so we use compareTo.
        if (expected.compareTo(actual) != 0) {
            throw new AssertionError(String.format(
                    Locale.ROOT,
                    "fixture[%s] %s mismatch: expected=%s actual=%s",
                    fixtureName,
                    field,
                    expected.toPlainString(),
                    actual.toPlainString()));
        }
    }

    private static String diff(String fixtureName, String expectedVariant, SentinelDecisionResponse actual) {
        return String.format(
                Locale.ROOT,
                "fixture[%s] variant mismatch: expected=%s actual=%s (%s)",
                fixtureName,
                expectedVariant,
                actual.getClass().getSimpleName(),
                actual);
    }

    // ---------------- DTOs (Jackson on-disk shape) ----------------

    /** Top-level on-disk shape. */
    record FixtureDto(
            @JsonProperty("name") String name,
            @JsonProperty("request") RequestDto request,
            @JsonProperty("anthropic") AnthropicDto anthropic,
            @JsonProperty("expected") ExpectedDto expected) {

        SentinelReplayFixture toFixture() {
            int status = anthropic == null ? 0 : anthropic.status;
            String body = anthropic == null ? null : anthropic.body;
            return new SentinelReplayFixture(name, request.toRequest(), body, status, expected.toExpected());
        }
    }

    /** Anthropic recording — status 0 means simulate transport failure. */
    record AnthropicDto(@JsonProperty("status") int status, @JsonProperty("body") String body) {}

    /** Mirror of {@link SentinelDecisionRequest} for round-trip stability. */
    record RequestDto(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("trade_id") String tradeId,
            @JsonProperty("signal_id") String signalId,
            @JsonProperty("direction") Direction direction,
            @JsonProperty("level_swept") LevelSwept levelSwept,
            @JsonProperty("indicator_snapshot") IndicatorSnapshotDto indicatorSnapshot,
            @JsonProperty("recent_trades_window") List<RecentTradeDto> recentTradesWindow,
            @JsonProperty("vix_close_prev") BigDecimal vixClosePrev,
            @JsonProperty("now_utc") Instant nowUtc) {

        @JsonCreator
        public RequestDto {
            // canonical constructor; nullable handling deferred to toRequest().
        }

        SentinelDecisionRequest toRequest() {
            List<RecentTrade> trades = recentTradesWindow == null
                    ? List.of()
                    : recentTradesWindow.stream()
                            .map(RecentTradeDto::toRecentTrade)
                            .toList();
            return new SentinelDecisionRequest(
                    tenantId,
                    tradeId,
                    signalId,
                    direction,
                    levelSwept,
                    indicatorSnapshot.toSnapshot(),
                    trades,
                    vixClosePrev,
                    nowUtc);
        }
    }

    record IndicatorSnapshotDto(
            @JsonProperty("ema13") BigDecimal ema13,
            @JsonProperty("ema48") BigDecimal ema48,
            @JsonProperty("ema200") BigDecimal ema200,
            @JsonProperty("atr14") BigDecimal atr14,
            @JsonProperty("rsi2") BigDecimal rsi2,
            @JsonProperty("regime") String regime,
            @JsonProperty("recent_bars") List<BarDto> recentBars) {

        IndicatorSnapshot toSnapshot() {
            List<Bar> bars = recentBars == null
                    ? List.of()
                    : recentBars.stream().map(BarDto::toBar).toList();
            return new IndicatorSnapshot(ema13, ema48, ema200, atr14, rsi2, regime, bars);
        }
    }

    record BarDto(
            @JsonProperty("ts") Instant ts,
            @JsonProperty("close") BigDecimal close,
            @JsonProperty("volume") long volume) {

        Bar toBar() {
            return new Bar(ts, close, volume);
        }
    }

    record RecentTradeDto(
            @JsonProperty("trade_id") String tradeId,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("r_multiple") BigDecimal rMultiple,
            @JsonProperty("ts") Instant ts) {

        RecentTrade toRecentTrade() {
            return new RecentTrade(tradeId, outcome, rMultiple, ts);
        }
    }

    /** Serialized {@code expected} block. */
    record ExpectedDto(
            @JsonProperty("type") String type,
            @JsonProperty("decision_path") String decisionPath,
            @JsonProperty("fallback_reason") String fallbackReason,
            @JsonProperty("confidence") BigDecimal confidence,
            @JsonProperty("reason_code") String reasonCode,
            @JsonProperty("reason_text") String reasonText) {

        ExpectedDecision toExpected() {
            return switch (type) {
                case "Allow" -> new ExpectedAllow(
                        DecisionPath.valueOf(decisionPath),
                        confidence,
                        ReasonCode.valueOf(reasonCode),
                        reasonText == null ? "" : reasonText);
                case "Veto" -> new ExpectedVeto(
                        confidence, ReasonCode.valueOf(reasonCode), reasonText == null ? "" : reasonText);
                case "Fallback" -> new ExpectedFallback(FallbackReason.valueOf(fallbackReason));
                default -> throw new IllegalArgumentException("unknown expected.type: " + type);
            };
        }
    }
}
