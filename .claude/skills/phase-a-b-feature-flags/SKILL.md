---
name: phase-a-b-feature-flags
description: Rules for gating Phase B (multi-tenant SaaS) functionality behind feature flags. Use when adding code that supports paying customers, billing, public access, KYC, multi-user flows. Triggers on Phase B, feature flag, multi-tenant launch, billing, KYC, customer.
---

# Phase A / Phase B Feature Flags

Phase A: single-user (owner). Phase B: multi-tenant SaaS — **gated on legal counsel completing RIA / broker-dealer review**.

## Rules

1. **Default OFF for all Phase B flags.** Code is shipped behind flags but inactive in Phase A.
2. **Flag check at the boundary** (controller, saga step, tool entry). Inner logic assumes flag is on.
3. **Flag service**: `FeatureFlagService.isEnabled(FlagKey, tenantId)`. Resolution order: tenant override → environment default → hard default OFF.
4. **Phase B flags are documented** in `docs/feature-flags.md` with: name, default, what unlocks, owning service, removal criteria.
5. **No Phase B code path is reachable in production until the corresponding flag is flipped on.**
6. **Compliance gate**: flipping any Phase B flag in `prod` requires written legal sign-off recorded in an ADR.

## Phase B flag inventory (initial)

| Flag | Unlocks | Default |
|---|---|---|
| `phaseB.publicSignup` | Public registration | OFF |
| `phaseB.alpacaOAuth` | Per-user Alpaca OAuth | OFF |
| `phaseB.billing` | Stripe subscriptions | OFF |
| `phaseB.kyc` | Auth0 KYC integration | OFF |
| `phaseB.aiSuggestionTools` | Reviewer/Assistant suggestion tools (with user approval) | OFF |
| `phaseB.operationsAgent` | Operations Agent | OFF |
| `phaseB.crossTenantPatterns` | Anonymized cross-tenant memory | OFF |

## Pattern

```java
public ResponseEntity<X> register(@RequestBody RegisterReq req) {
    if (!flags.isEnabled(PHASE_B_PUBLIC_SIGNUP, req.tenantId())) {
        return ResponseEntity.status(404).build();   // do not even acknowledge endpoint
    }
    // ... Phase B code
}
```

## Anti-patterns to flag

- Phase B code reachable without flag check
- Flag check inside the inner code path (do it at the boundary)
- Hard-coded `if (env.equals("prod"))` instead of flag service
- Flag default ON
- Flipping a flag in prod without ADR + legal sign-off
- "Temporary" Phase B code with a TODO to gate later
