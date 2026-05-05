import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '../services/config.service';
import { ProjectionRequest, ProjectionResult, ProjectionRunDocument } from '../models/projection.model';

@Injectable({ providedIn: 'root' })
export class ProjectionApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);

  // BFF route: POST /api/projection/{tenantId}/run
  //   Forwards to projection-service POST /projection/{tenantId}/run, which
  //   triggers a Monte Carlo recompute. NOTE: the BFF's run endpoint takes no
  //   body — projection-service's POST /projection/run does (the canonical
  //   path). The BFF hides the body. To run with custom params we hit the
  //   service-direct path via the BFF? No — only /run is proxied. So we POST
  //   to a custom path the BFF will need to add. For now we model the
  //   canonical service path that ProjectionController actually exposes:
  //   POST /projection/run with a body. The BFF must grow a body-forwarding
  //   variant; tracked as TODO below.
  //
  // TODO(phase 7): the BFF's POST /api/projection/{tenantId}/run does NOT
  // forward a request body. Until it does, `run()` will rely on
  // projection-service's default tenant config and the operator must POST
  // their parameters via projection-service directly. For Phase 6 we wire
  // this method to the future shape and expect a parallel BFF change.
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
