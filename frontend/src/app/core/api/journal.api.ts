import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '../services/config.service';
import { JournalPage } from '../models/journal.model';

@Injectable({ providedIn: 'root' })
export class JournalApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);

  // BFF route: GET /api/journal/{tenantId}?from=&to=&type=
  // Forwards to journal-service GET /journal/{tenantId} which returns the
  // { tenantId, page, size, total, rows: [Document] } envelope.
  list(opts: { from?: string; to?: string; type?: string; page?: number; size?: number } = {}): Observable<JournalPage> {
    let params = new HttpParams();
    if (opts.from) params = params.set('from', opts.from);
    if (opts.to) params = params.set('to', opts.to);
    if (opts.type) params = params.set('type', opts.type);
    if (opts.page !== undefined) params = params.set('page', String(opts.page));
    if (opts.size !== undefined) params = params.set('size', String(opts.size));
    const url = `${this.cfg.apiBase}/journal/${this.cfg.tenantId()}`;
    return this.http.get<JournalPage>(url, { params });
  }

  // Convenience: list FILL events only (used by Dashboard's "recent fills"
  // panel and by the Journal page's default "today's trades" view).
  listFills(from: string, to: string, size = 50): Observable<JournalPage> {
    return this.list({ from, to, type: 'FILL', size });
  }
}
