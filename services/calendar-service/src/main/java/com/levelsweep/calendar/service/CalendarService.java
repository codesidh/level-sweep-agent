package com.levelsweep.calendar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.levelsweep.calendar.domain.EventType;
import com.levelsweep.calendar.domain.MarketDay;
import com.levelsweep.calendar.domain.MarketEvent;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Loads the static NYSE holiday + FOMC calendar at startup and answers per-date
 * questions for the rest of the platform.
 *
 * <p>Caching strategy:
 *
 * <ul>
 *   <li>Phase A — the underlying data structure ({@code TreeMap<LocalDate,
 *       List<MarketEvent>>}) is already O(log n) and < 100 entries, so the
 *       {@code @Cacheable} annotations are paranoia, not necessity. They
 *       match the Spring-idiom for "memoize this" so the controller tests
 *       can verify the cache is wired without exercising the lookup cost.
 *   <li>Phase 7 — when (if) we swap in a managed economic-calendar API for
 *       CPI / NFP / earnings data, the {@code @Cacheable} layer prevents a
 *       per-request external call. The signature stays unchanged — no
 *       caller-visible breakage.
 * </ul>
 *
 * <p>Time zone: NYSE schedules are in America/New_York. {@link #today()}
 * uses the configured {@link Clock} (injected via the constructor — defaults
 * to {@code Clock.systemDefaultZone()}; tests inject a fixed clock).
 *
 * <p>Thread safety: every field is final, every collection is immutable
 * (deeply, via {@code List.copyOf} / {@code Map.copyOf}). The
 * {@link #loadCalendar()} method runs once at startup under
 * {@link PostConstruct}.
 *
 * <p>Failure mode: if the YAML files are missing or malformed at startup,
 * {@link #loadCalendar()} throws and Spring fails the context refresh — the
 * pod never reaches Ready and K8s rolls back. This is intentional. A
 * silently-empty calendar would cause the Session FSM to falsely conclude
 * "every day is a trading day" and trade through Christmas.
 */
@Service
public class CalendarService {

    private static final Logger LOG = LoggerFactory.getLogger(CalendarService.class);

    /** America/New_York — NYSE / FOMC schedules are in this zone. */
    public static final ZoneId NYSE_ZONE = ZoneId.of("America/New_York");

    private final Clock clock;
    private final String holidaysResource;
    private final String fomcResource;

    /** Lookup keyed by date → events on that date. Immutable. */
    private Map<LocalDate, List<MarketEvent>> eventsByDate = Map.of();

    public CalendarService(
            Clock clock,
            @Value("${calendar.resource.holidays:calendar/nyse-holidays-2026-2030.yml}") String holidaysResource,
            @Value("${calendar.resource.fomc:calendar/fomc-2026-2030.yml}") String fomcResource) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.holidaysResource = Objects.requireNonNull(holidaysResource, "holidaysResource");
        this.fomcResource = Objects.requireNonNull(fomcResource, "fomcResource");
    }

    @PostConstruct
    void loadCalendar() {
        // JavaTimeModule is mandatory for LocalDate (re)de-serialization. The
        // module is on the classpath transitively via spring-boot-starter
        // (jackson-datatype-jsr310 is a Spring Boot managed dep), but the
        // YAMLFactory-based ObjectMapper we build here does NOT auto-register
        // it — Spring's autoconfiguration only patches the framework-owned
        // ObjectMapper bean, not ad-hoc mappers like this one.
        ObjectMapper yaml = JsonMapper.builder(new YAMLFactory())
                .addModule(new JavaTimeModule())
                .build();
        Map<LocalDate, List<MarketEvent>> tmp = new HashMap<>();

        // --- NYSE holidays + early closes ---
        NyseFile nyse = readYaml(yaml, holidaysResource, NyseFile.class);
        for (NyseEntry h : nullToEmpty(nyse.holidays)) {
            addEvent(tmp, new MarketEvent(h.date(), h.name(), EventType.HOLIDAY));
        }
        for (NyseEntry e : nullToEmpty(nyse.earlyCloses)) {
            addEvent(tmp, new MarketEvent(e.date(), e.name(), EventType.EARLY_CLOSE));
        }

        // --- FOMC meetings + minutes ---
        FomcFile fomc = readYaml(yaml, fomcResource, FomcFile.class);
        for (NyseEntry m : nullToEmpty(fomc.meetings)) {
            addEvent(tmp, new MarketEvent(m.date(), m.name(), EventType.FOMC_MEETING));
        }
        for (NyseEntry m : nullToEmpty(fomc.minutes)) {
            addEvent(tmp, new MarketEvent(m.date(), m.name(), EventType.FOMC_MINUTES));
        }

        // Freeze: TreeMap → range queries on `blackoutDates(from, to)` are O(log n + k).
        TreeMap<LocalDate, List<MarketEvent>> frozen = new TreeMap<>();
        for (Map.Entry<LocalDate, List<MarketEvent>> e : tmp.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.eventsByDate = Collections.unmodifiableMap(frozen);

        long holidayCount = countByType(EventType.HOLIDAY);
        long earlyCloseCount = countByType(EventType.EARLY_CLOSE);
        long fomcMeetingCount = countByType(EventType.FOMC_MEETING);
        long fomcMinutesCount = countByType(EventType.FOMC_MINUTES);
        LOG.info(
                "calendar loaded: {} holidays, {} early-closes, {} FOMC meetings, {} FOMC minutes (range {} → {})",
                holidayCount,
                earlyCloseCount,
                fomcMeetingCount,
                fomcMinutesCount,
                frozen.isEmpty() ? "n/a" : frozen.firstKey(),
                frozen.isEmpty() ? "n/a" : frozen.lastKey());
    }

    /**
     * Today in {@link #NYSE_ZONE}. Not {@code @Cacheable} — the value is
     * date-dependent and we want a fresh answer once midnight ET ticks
     * over, even if the JVM has been up for weeks.
     */
    public MarketDay today() {
        LocalDate today = LocalDate.now(clock.withZone(NYSE_ZONE));
        return forDate(today);
    }

    /**
     * Per-date market-day descriptor.
     *
     * <p>Cached because it's the hot path for both endpoints and because a
     * future caller (Session FSM) may invoke this on every 2-minute bar
     * during RTH. The cache key is the date; the underlying map lookup is
     * O(log n) but the cache short-circuits even that.
     */
    @Cacheable(cacheNames = "calendar.forDate", key = "#date")
    public MarketDay forDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        List<MarketEvent> events = eventsByDate.getOrDefault(date, List.of());

        boolean isHoliday = events.stream().anyMatch(e -> e.type() == EventType.HOLIDAY);
        boolean isHalfDay = events.stream().anyMatch(e -> e.type() == EventType.EARLY_CLOSE);
        boolean isFomc =
                events.stream().anyMatch(e -> e.type() == EventType.FOMC_MEETING || e.type() == EventType.FOMC_MINUTES);

        boolean weekend = MarketDay.isWeekend(date);
        boolean isTradingDay = !weekend && !isHoliday;

        String holidayName = events.stream()
                .filter(e -> e.type() == EventType.HOLIDAY)
                .map(MarketEvent::name)
                .findFirst()
                .orElse(null);

        List<String> eventNames = events.stream().map(MarketEvent::name).toList();

        return new MarketDay(date, isTradingDay, isHoliday, holidayName, isHalfDay, isFomc, eventNames);
    }

    /**
     * Range query: every date {@code from <= d <= to} that the Session FSM
     * should treat as a blackout. Includes:
     *
     * <ul>
     *   <li>NYSE full-closure holidays (no RTH session)
     *   <li>FOMC meeting + minutes-release days (BLACKOUT, not closure —
     *       trading is permitted but the Session FSM holds entries until the
     *       statement / minutes drop, per requirements §8 ARMING / BLACKOUT)
     * </ul>
     *
     * <p>NYSE half-days (early closes) are NOT in the blackout list — those
     * are still trading days, just shorter. The Session FSM handles the
     * 13:00-ET-instead-of-16:00-ET window via the {@code isHalfDay} flag.
     *
     * <p>Weekends are NOT in the blackout list — they're not market days at
     * all, so labeling them "blackout" would be misleading. The Session FSM
     * already short-circuits on non-RTH days via the trading-day check.
     *
     * @param from inclusive, must be non-null and {@code <= to}
     * @param to   inclusive, must be non-null
     */
    @Cacheable(cacheNames = "calendar.blackoutDates", key = "#from.toString() + '_' + #to.toString()")
    public List<MarketEvent> blackoutDates(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be <= to (got from=" + from + " to=" + to + ")");
        }

        List<MarketEvent> out = new ArrayList<>();
        // The map is a TreeMap under the hood, but we exposed it as Map; rather
        // than down-cast, just iterate over the date keys we care about. The
        // dataset is < 100 entries so the linear pass is trivial.
        for (Map.Entry<LocalDate, List<MarketEvent>> e : eventsByDate.entrySet()) {
            LocalDate d = e.getKey();
            if (d.isBefore(from) || d.isAfter(to)) {
                continue;
            }
            for (MarketEvent ev : e.getValue()) {
                if (ev.type() == EventType.HOLIDAY
                        || ev.type() == EventType.FOMC_MEETING
                        || ev.type() == EventType.FOMC_MINUTES) {
                    out.add(ev);
                }
            }
        }
        out.sort((a, b) -> a.date().compareTo(b.date()));
        return List.copyOf(out);
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private long countByType(EventType type) {
        return eventsByDate.values().stream()
                .flatMap(List::stream)
                .filter(e -> e.type() == type)
                .count();
    }

    private static <T> List<T> nullToEmpty(List<T> in) {
        return in == null ? List.of() : in;
    }

    private static void addEvent(Map<LocalDate, List<MarketEvent>> acc, MarketEvent ev) {
        acc.computeIfAbsent(ev.date(), d -> new ArrayList<>()).add(ev);
    }

    private static <T> T readYaml(ObjectMapper yaml, String resourcePath, Class<T> type) {
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            return yaml.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "calendar-service failed to load YAML resource '" + resourcePath
                            + "' — pod cannot reach Ready without the static calendar dataset",
                    e);
        }
    }

    // -----------------------------------------------------------------------
    // YAML deserialization helpers — Jackson maps record components by name.
    // -----------------------------------------------------------------------

    /** YAML root for {@code nyse-holidays-2026-2030.yml}. */
    public record NyseFile(List<NyseEntry> holidays, List<NyseEntry> earlyCloses) {}

    /** YAML root for {@code fomc-2026-2030.yml}. */
    public record FomcFile(List<NyseEntry> meetings, List<NyseEntry> minutes) {}

    /** Single calendar entry — date + name. */
    public record NyseEntry(LocalDate date, String name) {}
}
