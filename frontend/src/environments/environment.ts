export const environment = {
  production: true,
  // Both dev and prod use same-origin /api: prod is fronted by SWA route rules
  // that proxy /api/* to the BFF; dev is fronted by `proxy.conf.json` doing the
  // same locally to http://localhost:8090. The frontend never embeds a literal
  // backend hostname (CLAUDE.md guardrail #6 — no credentials/URLs leak).
  apiBase: '/api',
  // Phase A operates as a single OWNER tenant (CLAUDE.md guardrail #1). Phase
  // 10 will pull this from the Auth0 JWT instead of hard-coding it.
  defaultTenantId: 'OWNER',
};
