// Phase A: single OWNER tenant. Phase B unlocks per-user tenant after legal
// review (CLAUDE.md guardrails #1 + #4). Every API request still threads
// tenantId so the multi-tenant contract is honoured today.
export type TenantId = string;
