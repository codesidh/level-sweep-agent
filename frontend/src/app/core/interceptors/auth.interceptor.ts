import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { ConfigService } from '../services/config.service';

// Phase A: stamp X-Tenant-Id: OWNER on every BFF call. The BFF's
// BypassAuthFilter validates the header and rejects requests without it (401).
// Phase 10 swaps in an Auth0 token bearer here while keeping X-Tenant-Id.
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const cfg = inject(ConfigService);
  if (!req.url.startsWith(cfg.apiBase)) {
    return next(req);
  }
  const tenantId = cfg.tenantId();
  const cloned = req.clone({
    setHeaders: { 'X-Tenant-Id': tenantId },
  });
  return next(cloned);
};
