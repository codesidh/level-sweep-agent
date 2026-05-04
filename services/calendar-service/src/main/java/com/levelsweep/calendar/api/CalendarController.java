package com.levelsweep.calendar.api;

import com.levelsweep.calendar.domain.MarketDay;
import com.levelsweep.calendar.domain.MarketEvent;
import com.levelsweep.calendar.service.CalendarService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calendar Service REST API — three GET endpoints under {@code /calendar}.
 *
 * <pre>
 * GET /calendar/today                                      → MarketDay (today in America/New_York)
 * GET /calendar/{date}                                     → MarketDay (arbitrary date, ISO yyyy-MM-dd)
 * GET /calendar/blackout-dates?from=2026-01-01&amp;to=2026-12-31  → list of MarketEvent
 * </pre>
 *
 * <p>Phase A: <b>owner-only</b> — no authentication. Callers are inside-cluster
 * services (decision-engine Session FSM, ai-agent-service Narrator).
 * NetworkPolicy denies external ingress; Phase 5 BFF (api-gateway-bff) sits
 * in front and validates Auth0 JWT for any future external clients.
 *
 * <p>Multi-tenant: the calendar is tenant-agnostic for Phase A — the NYSE
 * schedule is the same for every tenant. No {@code tenant_id} in the URL or
 * the response. Phase B per-tenant override (institutional tenant adds a
 * blackout date) lands behind {@code phase-a-b-feature-flags}.
 *
 * <p>Time zone handling: the {@code tz} query param on {@code GET
 * /calendar/today} exists for forward compatibility but Phase 6 only
 * supports {@code America/New_York} (the only zone NYSE schedules in). A
 * future tenant in a different zone (Phase B?) would need a richer model
 * than "the calendar is in ET"; today we just validate-and-pass.
 */
@RestController
@RequestMapping("/calendar")
public class CalendarController {

    private static final Logger LOG = LoggerFactory.getLogger(CalendarController.class);

    private static final String DEFAULT_TZ = "America/New_York";

    private final CalendarService service;

    public CalendarController(CalendarService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * Today in the requested zone (Phase 6: only America/New_York is honored).
     *
     * <p>The {@code tz} query param is parsed-but-not-honored: any value other
     * than the NYSE zone returns 400. Bouncing on unsupported input is safer
     * than silently re-interpreting; the Session FSM is a downstream consumer
     * and the cost of returning yesterday's date is a missed trading session.
     */
    @GetMapping("/today")
    public ResponseEntity<?> today(@RequestParam(value = "tz", required = false, defaultValue = DEFAULT_TZ) String tz) {
        if (!DEFAULT_TZ.equals(tz)) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            "Phase 6 supports tz=America/New_York only — got tz=" + tz,
                            "supportedZones",
                            List.of(DEFAULT_TZ)));
        }
        MarketDay day = service.today();
        LOG.debug("calendar/today → {}", day);
        return ResponseEntity.ok(day);
    }

    /**
     * Arbitrary date (ISO {@code yyyy-MM-dd}). Returns the same shape as
     * {@code GET /calendar/today}.
     *
     * <p>Spring's path-variable matching greedily consumes the segment so
     * {@code /calendar/today} (literal) matches the dedicated mapping above
     * and {@code /calendar/2026-12-25} flows here. A bad date format returns
     * 400 with an explanatory body rather than the framework default.
     */
    @GetMapping("/{date}")
    public ResponseEntity<?> forDate(@PathVariable("date") String date) {
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "date must be ISO-8601 yyyy-MM-dd — got '" + date + "'"));
        }
        MarketDay day = service.forDate(parsed);
        LOG.debug("calendar/{} → {}", parsed, day);
        return ResponseEntity.ok(day);
    }

    /**
     * Range of blackout dates (full-closure holidays + FOMC blackout days).
     * Both bounds inclusive.
     *
     * <p>Half-days and weekends are NOT in this list — see
     * {@link CalendarService#blackoutDates(LocalDate, LocalDate)} for the
     * inclusion rules.
     */
    @GetMapping("/blackout-dates")
    public ResponseEntity<?> blackoutDates(@RequestParam("from") String from, @RequestParam("to") String to) {
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = LocalDate.parse(from);
            toDate = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "from/to must be ISO-8601 yyyy-MM-dd"));
        }
        if (fromDate.isAfter(toDate)) {
            return ResponseEntity.badRequest().body(Map.of("error", "from must be <= to"));
        }
        List<MarketEvent> events = service.blackoutDates(fromDate, toDate);
        LOG.debug("calendar/blackout-dates {}→{} returned {} events", fromDate, toDate, events.size());
        return ResponseEntity.ok(Map.of(
                "from", fromDate.toString(),
                "to", toDate.toString(),
                "count", events.size(),
                "events", events));
    }
}
