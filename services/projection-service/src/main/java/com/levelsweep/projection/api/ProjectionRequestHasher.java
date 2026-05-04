package com.levelsweep.projection.api;

import com.levelsweep.projection.domain.ProjectionRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Stable SHA-256 hash over (tenantId, normalised request) — used as both:
 *
 * <ul>
 *   <li>The cache key for the {@code projections.runs} Mongo collection.
 *       Identical inputs from the same tenant collide on the same row.</li>
 *   <li>The deterministic seed source when the client omits {@code seed} on
 *       the request. The hash's leading 8 bytes are read as a big-endian
 *       {@code long} and passed to the {@link com.levelsweep.projection.engine.MonteCarloEngine}.</li>
 * </ul>
 *
 * <p>Normalisation order matches the {@link ProjectionRequest} record order
 * exactly so a JSON field-order shuffle on the wire does not change the hash.
 * The {@code seed} field is INTENTIONALLY OMITTED from the hash input — the
 * hash IS the seed source when no seed is provided, and including the
 * client-supplied seed would mean the cache key flips on every request.
 *
 * <p>Determinism: SHA-256 is bit-stable across JVMs and platforms; the locale
 * is fixed (US, no thousand separators); doubles are formatted with
 * {@link Double#toString(double)} which is round-trip stable per the IEEE 754
 * binary representation. Tests assert a known hash for a known input.
 */
@Component
public class ProjectionRequestHasher {

    /**
     * Compute the canonical hash for a request. The returned hex string is
     * 64 chars (256 bits / 4 bits per hex char).
     */
    public String hash(ProjectionRequest req) {
        // Pipe-delimited payload — pipes never appear in numeric formatting
        // so no escaping needed. Field order is fixed and matches the record's
        // declaration order; changing this order breaks every existing cache
        // key, which is acceptable in Phase 6 (Mongo data is non-canonical).
        String payload = String.join(
                "|",
                req.tenantId(),
                Double.toString(req.startingEquity()),
                Double.toString(req.winRatePct()),
                Double.toString(req.lossPct()),
                Integer.toString(req.sessionsPerWeek()),
                Integer.toString(req.horizonWeeks()),
                Double.toString(req.positionSizePct()),
                Integer.toString(req.simulations()));

        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JDK; this branch is unreachable
            // on a sane runtime. Fail loud rather than silently degrade to
            // a non-deterministic alternative.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Read the leading 8 bytes of the hex hash as a big-endian {@code long}
     * to produce a deterministic seed. The full 256 bits are not needed —
     * 64 bits is the {@link java.util.Random} seed shape.
     */
    public long seedFromHash(String hexHash) {
        if (hexHash == null || hexHash.length() < 16) {
            throw new IllegalArgumentException("hexHash must be at least 16 chars");
        }
        long seed = 0L;
        for (int i = 0; i < 16; i++) {
            int nibble = Character.digit(hexHash.charAt(i), 16);
            if (nibble < 0) {
                throw new IllegalArgumentException("invalid hex digit at " + i);
            }
            seed = (seed << 4) | nibble;
        }
        return seed;
    }
}
