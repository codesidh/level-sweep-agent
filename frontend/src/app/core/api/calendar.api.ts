import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '../services/config.service';
import { BlackoutResponse, MarketDay } from '../models/calendar.model';

@Injectable({ providedIn: 'root' })
export class CalendarApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);

  // BFF route: GET /api/calendar/today  (the only calendar endpoint exposed
  // through the BFF in Phase 6 — see CalendarRouteController javadoc).
  today(): Observable<MarketDay> {
    return this.http.get<MarketDay>(`${this.cfg.apiBase}/calendar/today`);
  }

  // TODO(phase 7): no BFF route yet for /calendar/{date} or
  // /calendar/blackout-dates. Calendar-service exposes both at its own
  // hostname but the BFF intentionally limited the surface in Phase 6
  // (see CalendarRouteController javadoc). When Phase 7 widens the BFF, this
  // method calls /api/calendar/blackout-dates?from=&to= and we drop the stub.
  blackoutDates(from: string, to: string): Observable<BlackoutResponse> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<BlackoutResponse>(`${this.cfg.apiBase}/calendar/blackout-dates`, { params });
  }

  // TODO(phase 7): same as above — direct route to /calendar/{date} not yet
  // proxied. The Calendar page falls back to forecast via blackoutDates+ today.
  forDate(date: string): Observable<MarketDay> {
    return this.http.get<MarketDay>(`${this.cfg.apiBase}/calendar/${date}`);
  }
}
