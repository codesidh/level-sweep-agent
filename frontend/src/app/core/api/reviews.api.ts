import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ConfigService } from '../services/config.service';
import { DailyReport, DailyReportOutcome } from '../models/narrative.model';
import { JournalPage } from '../models/journal.model';

@Injectable({ providedIn: 'root' })
export class ReviewsApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);

  // TODO(phase 7): the BFF does not yet expose a dedicated daily-reports
  // route. The DailyReviewer persists into journal.daily_reports MongoDB
  // collection AND emits a DAILY_REPORT_GENERATED audit event into
  // audit_log.events (see DailyReviewer). This method shapes those audit rows
  // into DailyReport objects for the Reviews page. When the BFF grows
  // GET /api/v1/reports/daily?tenantId&limit, swap the URL and remove the
  // event_type filter.
  list(limit = 30): Observable<ReadonlyArray<DailyReport>> {
    const params = new HttpParams().set('type', 'DAILY_REPORT_GENERATED').set('size', String(limit));
    return this.http
      .get<JournalPage>(`${this.cfg.apiBase}/journal/${this.cfg.tenantId()}`, { params })
      .pipe(map((page) => page.rows.map((row) => mapRowToReport(row.payload, row.tenant_id))));
  }
}

function mapRowToReport(payload: Record<string, unknown>, tenantId: string): DailyReport {
  const proposals = Array.isArray(payload['proposals']) ? (payload['proposals'] as DailyReport['proposals']) : [];
  const anomalies = Array.isArray(payload['anomalies']) ? (payload['anomalies'] as ReadonlyArray<string>) : [];
  return {
    tenantId: typeof payload['tenantId'] === 'string' ? (payload['tenantId'] as string) : tenantId,
    sessionDate: stringOrEmpty(payload['sessionDate']),
    summary: stringOrEmpty(payload['summary']),
    anomalies,
    proposals,
    outcome: outcomeOf(payload['outcome']),
    generatedAt: stringOrEmpty(payload['generatedAt']),
    modelUsed: stringOrEmpty(payload['modelUsed']),
    promptHash: stringOrEmpty(payload['promptHash']),
    totalTokensUsed: numberOr(payload['totalTokensUsed']),
    costUsd: typeof payload['costUsd'] === 'number' || typeof payload['costUsd'] === 'string' ? payload['costUsd'] : 0,
  };
}

function stringOrEmpty(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

function numberOr(v: unknown, dflt = 0): number {
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const n = Number(v);
    return Number.isFinite(n) ? n : dflt;
  }
  return dflt;
}

function outcomeOf(v: unknown): DailyReportOutcome {
  if (v === 'COMPLETED' || v === 'SKIPPED_COST_CAP' || v === 'FAILED') return v;
  return 'COMPLETED';
}
