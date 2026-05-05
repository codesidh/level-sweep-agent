# LevelSweep Operator Dashboard (Phase 6)

Angular 19 + Tailwind CSS 3 SPA — the operator's read-only window into the LevelSweepAgent
backend. Hosted on Azure Static Web Apps. Phase A: single OWNER tenant.

## Pages

| Route | Backend (BFF route) |
| --- | --- |
| `/` Dashboard | `/api/calendar/today`, `/api/journal/{tenant}` |
| `/journal` | `/api/journal/{tenant}` |
| `/narratives` | `/api/journal/{tenant}?type=NARRATIVE_GENERATED` *(stub — see TODO)* |
| `/reviews` | `/api/journal/{tenant}?type=DAILY_REPORT_GENERATED` *(stub — see TODO)* |
| `/projections` | `/api/projection/{tenant}/last`, `/api/projection/{tenant}/run` |
| `/calendar` | `/api/calendar/today`, `/api/calendar/blackout-dates` *(TODO)* |
| `/assistant` | `/api/v1/assistant/chat`, `/api/v1/assistant/conversations` |

## Local development

```bash
# Prereqs: Node 20+, npm 10+. Tested with Node 24.

cd frontend
npm install
npm start
```

`npm start` runs `ng serve` with a proxy that forwards `/api/**` to the BFF at
`http://localhost:8090` (its default port — see `services/api-gateway-bff/.../application.yml`).
The proxy injects `X-Tenant-Id: OWNER` so the BFF's `BypassAuthFilter` accepts each request.

Bring up the BFF locally (in another shell):
```bash
./gradlew :api-gateway-bff:bootRun
```
Then open http://localhost:4200.

## Production build

```bash
npm run build
```
Outputs `dist/levelsweep-dashboard/browser`. The CI workflow uploads this directory to Azure
Static Web Apps via the `Azure/static-web-apps-deploy@v1` action.

## Deployment

Pushing to `main` with changes under `frontend/**` triggers
`.github/workflows/dashboard-swa.yml`, which builds and deploys to:

> https://salmon-flower-067f4480f.7.azurestaticapps.net

The SWA is configured (via `src/staticwebapp.config.json`) to:
- Rewrite SPA routes to `/index.html` (excluding `/api/*`).
- Proxy `/api/*` to the BFF (route configured in the SWA portal — Phase 6 requires this
  one-time setup; the workflow does NOT automate it).

## Tech notes

- **Standalone components** everywhere (no NgModule). Lazy-loaded routes via `loadComponent`.
- **Signals** for state. No NgRx. RxJS used only for the HTTP edge.
- **Strict TypeScript**: `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`.
- **Tailwind**: `darkMode: 'class'`. The `<html class="dark">` is hard-coded in `index.html`.
- **HttpInterceptor** stamps `X-Tenant-Id` on every `/api/*` call from a `ConfigService` signal.
  Phase 10 swaps in an Auth0 bearer token alongside the tenant header.
- **No backend hostnames in the SPA bundle** — `apiBase = '/api'` in both env files. Same-origin
  requests; the SWA / proxy.conf.json fronts the BFF.

## TODOs (Phase 7 follow-ups)

- BFF route for `/api/v1/narratives` (currently scrapes the journal feed).
- BFF route for `/api/v1/reports/daily` (currently scrapes the journal feed).
- BFF route for `/api/calendar/blackout-dates` and `/api/calendar/{date}`.
- BFF body-forwarding on `POST /api/projection/{tenant}/run`.
- Wire Auth0 (Phase 10) — replaces `BypassAuthFilter` and the hardcoded `OWNER` tenant.
- SWA route rule that proxies `/api/*` to the BFF (must be configured manually in Azure portal).

## Scripts

- `npm start` — dev server with proxy on http://localhost:4200.
- `npm run build` — production bundle to `dist/`.
- `npm run watch` — dev-mode rebuild on save.
- `npm test` — placeholder; Phase 6 ships without unit tests.
