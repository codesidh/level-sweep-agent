import { Injectable, signal } from '@angular/core';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ConfigService {
  // Signal so future tenant-switcher (Phase B) re-flows down through every API
  // service without manual subscription bookkeeping.
  readonly tenantId = signal<string>(environment.defaultTenantId);

  readonly apiBase = environment.apiBase;
}
