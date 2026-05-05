import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '../services/config.service';
import { ProjectionRequest, ProjectionResult, ProjectionRunDocument } from '../models/projection.model';

@Injectable({ providedIn: 'root' })
export class ProjectionApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);

  // BFF route: POST /api/projection/{tenantId}/run — body is forwarded to
  // projection-service /projection/run, which runs a fresh Monte Carlo and
  // caches the result. Returns the new ProjectionResult.
  run(request: ProjectionRequest): Observable<ProjectionResult> {
    return this.http.post<ProjectionResult>(`${this.cfg.apiBase}/projection/${request.tenantId}/run`, request);
  }

  // BFF route: GET /api/projection/{tenantId}/last
  //   Forwards to projection-service GET /projection/{tenantId}/last, which
  //   returns the latest cached ProjectionRunDocument or 404.
  last(): Observable<ProjectionRunDocument | null> {
    return this.http.get<ProjectionRunDocument>(`${this.cfg.apiBase}/projection/${this.cfg.tenantId()}/last`);
  }
}
