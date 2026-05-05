import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '../services/config.service';

// The BFF aggregates four downstreams into a single response so the home
// screen renders in one round-trip (DashboardController.summary). Shape is
// untyped because each section is the raw downstream body — the component
// extracts what it needs and renders an "unavailable" tile when a section
// reports { error: ... }.
export interface DashboardSummary {
  readonly tenant_id: string;
  readonly config: unknown;
  readonly journal: unknown;
  readonly projection: unknown;
  readonly calendar: unknown;
  readonly degraded: boolean;
}

@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);

  summary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${this.cfg.apiBase}/dashboard/${this.cfg.tenantId()}/summary`);
  }
}
