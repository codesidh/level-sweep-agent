// Production build (used by ng build / ng serve --configuration=production).
// The dashboard is deployed to Azure Static Web Apps; the BFF runs on AKS and
// is exposed via a Cloudflare Tunnel that gives it a public HTTPS URL without
// requiring DNS/cert ownership in dev. Phase 7+ replaces the trycloudflare URL
// with a managed custom domain + TLS via APIM or Application Gateway.
//
// IMPORTANT: trycloudflare URLs are anonymous and rotate on tunnel restart.
// When the cloudflared deployment in api-gateway-bff namespace cycles, the
// new URL must be pulled from `kubectl logs deploy/cloudflared-bff` and this
// constant updated, then the dashboard rebuilt + redeployed.
export const environment = {
  production: true,
  apiBase: 'https://dolls-employ-envelope-magnificent.trycloudflare.com/api',
  // Phase A operates as a single OWNER tenant (CLAUDE.md guardrail #1). Phase
  // 10 will pull this from the Auth0 JWT instead of hard-coding it.
  defaultTenantId: 'OWNER',
};
