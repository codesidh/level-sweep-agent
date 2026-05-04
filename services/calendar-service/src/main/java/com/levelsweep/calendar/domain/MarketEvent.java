package com.levelsweep.calendar.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable single-day economic / market event.
 *
 * <p>One row in the YAML resource files corresponds to one {@code MarketEvent}.
 * The {@link #type} is the discriminator; {@link #name} is a human-readable
 * label suitable for an alert / log line / dashboard tooltip.
 *
 * <p>Examples loaded by the Phase 6 dataset:
 *
 * <ul>
 *   <li>{@code MarketEvent(2026-01-01, "New Year's Day", HOLIDAY)}
 *   <li>{@code MarketEvent(2026-11-27, "Day after Thanksgiving", EARLY_CLOSE)}
 *   <li>{@code MarketEvent(2026-01-28, "FOMC Meeting (Jan)", FOMC_MEETING)}
 *   <li>{@code MarketEvent(2026-02-18, "FOMC Minutes (Jan)", FOMC_MINUTES)}
 * </ul>
 *
 * <p>Determinism: Phase 6 dataset is hard-coded. Replay parity is not at
 * stake here (calendar-service is not part of the Decision Engine), but the
 * data is still load-once-at-startup so a running pod sees a stable view.
 *
 * @param date  event date in America/New_York. NYSE holidays observed on the
 *              calendar weekday (e.g. July 4 falling on a Sunday → observed
 *              Monday July 5). The YAML file lists the OBSERVED date.
 * @param name  human-readable label (NYSE.gov / federalreserve.gov phrasing)
 * @param type  discriminator — see {@link EventType}
 */
public record MarketEvent(LocalDate date, String name, EventType type) {

    public MarketEvent {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
