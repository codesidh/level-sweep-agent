package com.levelsweep.userconfig.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.levelsweep.userconfig.store.FeatureFlags;

/**
 * Wire shape for {@link FeatureFlags} on the REST boundary.
 *
 * <p>Defined separately from the domain record so that field renames /
 * deprecations on the public API can land without disturbing the persistence
 * record (and vice versa). Field names are kebab-case in JSON to match
 * CLAUDE.md guardrail #1's flag taxonomy ({@code phase-b-multi-tenant-onboarding}, …).
 *
 * <p>The {@link #toDomain()} and {@link #fromDomain(FeatureFlags)} helpers
 * keep mapping centralised; controllers never reach into the domain record
 * directly.
 */
public record FeatureFlagsDto(
        @JsonProperty("phase-b-multi-tenant-onboarding") boolean phaseBMultiTenantOnboarding,
        @JsonProperty("phase-b-alpaca-oauth") boolean phaseBAlpacaOAuth,
        @JsonProperty("phase-b-billing") boolean phaseBBilling,
        @JsonProperty("phase-b-ai-suggestions") boolean phaseBAiSuggestions) {

    @JsonCreator
    public FeatureFlagsDto {
        // Compact-canonical constructor + @JsonCreator so Jackson uses the
        // record components directly. No defaulting here: a missing field in
        // the inbound JSON serialises as the boolean default false, which is
        // the Phase A safe default per CLAUDE.md guardrail #1.
    }

    public FeatureFlags toDomain() {
        return new FeatureFlags(phaseBMultiTenantOnboarding, phaseBAlpacaOAuth, phaseBBilling, phaseBAiSuggestions);
    }

    public static FeatureFlagsDto fromDomain(FeatureFlags flags) {
        return new FeatureFlagsDto(
                flags.phaseBMultiTenantOnboarding(),
                flags.phaseBAlpacaOAuth(),
                flags.phaseBBilling(),
                flags.phaseBAiSuggestions());
    }
}
