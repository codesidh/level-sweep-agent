package com.levelsweep.shared.domain.signal;

/**
 * Identifies which of the four reference levels (per {@code requirements.md}
 * §4) was swept by the candle under evaluation.
 *
 * <ul>
 *   <li>{@link #PDH} — previous day RTH high; sweep above pierces resistance,
 *       a confirmed close back below it triggers a SHORT (PUT) setup
 *   <li>{@link #PDL} — previous day RTH low; sweep below pierces support, a
 *       confirmed close back above it triggers a LONG (CALL) setup
 *   <li>{@link #PMH} — overnight / pre-market high; same SHORT semantics as PDH
 *   <li>{@link #PML} — overnight / pre-market low; same LONG semantics as PDL
 * </ul>
 */
public enum SweptLevel {
    PDH,
    PDL,
    PMH,
    PML
}
