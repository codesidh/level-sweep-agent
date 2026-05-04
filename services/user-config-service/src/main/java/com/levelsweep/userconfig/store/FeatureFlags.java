package com.levelsweep.userconfig.store;

import java.util.Objects;

/**
 * Per-tenant Phase B gating flags. All default to {@code false} — Phase A is
 * single-user (owner) operation; Phase B (multi-tenant SaaS) ships gated
 * behind these flags pending RIA / broker-dealer legal review (CLAUDE.md
 * guardrail #1, {@code phase-a-b-feature-flags} skill).
 *
 * <p>The set is intentionally fixed at the record-field level rather than a
 * free-form {@code Map<String, Boolean>} so each new flag requires a code
 * change + a migration column / JSON-shape bump and goes through normal PR
 * review. A typo'd flag name in a tenant_config row should fail at the
 * deserialiser, not silently flip a boolean.
 *
 * @param phaseBMultiTenantOnboarding gate the public sign-up / Auth0 user
 *     creation flow. Phase A: {@code false}. Flip to {@code true} only when
 *     legal counsel signs off on the multi-tenant RIA paperwork.
 * @param phaseBAlpacaOAuth           gate the per-user Alpaca OAuth flow
 *     (Phase A uses the owner's hardcoded paper-trading token). Flip when
 *     each tenant has provisioned their own Alpaca credentials.
 * @param phaseBBilling               gate the Stripe / billing integration.
 *     Phase A is single-user owner; no billing.
 * @param phaseBAiSuggestions         gate the proactive AI suggestion path
 *     (Narrator pushing recommendations vs. Sentinel veto). Phase A keeps
 *     AI strictly advisory + veto-only per CLAUDE.md guardrail #2.
 */
public record FeatureFlags(
        boolean phaseBMultiTenantOnboarding,
        boolean phaseBAlpacaOAuth,
        boolean phaseBBilling,
        boolean phaseBAiSuggestions) {

    /** Phase A defaults — every Phase B path off. */
    public static FeatureFlags defaults() {
        return new FeatureFlags(false, false, false, false);
    }

    /** Equality on FeatureFlags is field-by-field — record-default semantics. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FeatureFlags other)) {
            return false;
        }
        return phaseBMultiTenantOnboarding == other.phaseBMultiTenantOnboarding
                && phaseBAlpacaOAuth == other.phaseBAlpacaOAuth
                && phaseBBilling == other.phaseBBilling
                && phaseBAiSuggestions == other.phaseBAiSuggestions;
    }

    @Override
    public int hashCode() {
        return Objects.hash(phaseBMultiTenantOnboarding, phaseBAlpacaOAuth, phaseBBilling, phaseBAiSuggestions);
    }
}
