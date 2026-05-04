package com.levelsweep.calendar.domain;

/**
 * Discriminator for {@link MarketEvent}.
 *
 * <p>The set is intentionally narrow for Phase 6 — only the events the Session
 * FSM cares about. CPI / NFP / earnings-season events are listed for forward
 * compatibility but Phase 6 ships data only for {@link #HOLIDAY},
 * {@link #EARLY_CLOSE}, {@link #FOMC_MEETING}, and {@link #FOMC_MINUTES}.
 *
 * <p>UPPER_SNAKE matches the audit-trail event-type discriminator convention
 * used by {@code journal-service.AuditRecord.eventType} so dashboards and
 * downstream consumers can use the same vocabulary across services.
 */
public enum EventType {

    /** NYSE full closure — no RTH session. */
    HOLIDAY,

    /** NYSE half-day — RTH ends 13:00 ET (Black Friday, Christmas Eve, July 3 if weekday). */
    EARLY_CLOSE,

    /** FOMC scheduled meeting (statement release ~14:00 ET, presser ~14:30 ET). */
    FOMC_MEETING,

    /** FOMC minutes release (~14:00 ET, three weeks after the meeting). */
    FOMC_MINUTES,

    /** CPI (Consumer Price Index) release. Phase 7 wires data; enum reserved now. */
    CPI,

    /** Non-Farm Payrolls release. Phase 7 wires data; enum reserved now. */
    NFP
}
